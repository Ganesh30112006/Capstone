"""Request and response schemas, and the input bounds that go with them.

Validation here is a security control, not a formality. Two properties are
load-bearing:

**Non-finite values are refused.** JSON as most encoders emit it permits bare
`NaN` and `Infinity`, and Pydantic accepts them into a `float` by default. A
`NaN` reading then passes every rule check, because every comparison against
`NaN` is false — `NaN > TEMP_MAX` is false and so is `NaN < TEMP_MIN`. The
detector reported "Parameters normal" for it. `Infinity` was worse in a
different way: it survived scoring and then crashed response serialisation,
which JSON cannot represent. So a device — or anything posing as one — could
switch off its own anomaly detection by sending a value that is not a number.
`allow_inf_nan=False` turns both into a 422 at the edge.

**Every collection and magnitude is bounded.** Request cost here scales with
input size: history length drives the Chronos context, horizon drives the
decode length, and training rows drive a DataFrame and a forest fit. Unbounded
inputs are unbounded allocations, and a small request body can still ask for a
very large amount of work.
"""

from typing import Annotated, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field

import config

# A float that must be an actual number. See the module docstring: this single
# constraint closes a complete detection bypass.
FiniteFloat = Annotated[float, Field(allow_inf_nan=False)]

# Bounded to the physically meaningful range for each instrument, wide enough to
# admit a genuinely broken sensor — that reading is what the rule engine exists
# to flag — but not values that only exist to overflow something downstream.
Temperature = Annotated[float, Field(allow_inf_nan=False, ge=-273.15, le=1000.0)]
Humidity = Annotated[float, Field(allow_inf_nan=False, ge=-10.0, le=200.0)]
Pressure = Annotated[float, Field(allow_inf_nan=False, ge=0.0, le=2000.0)]
AirQuality = Annotated[float, Field(allow_inf_nan=False, ge=0.0, le=10_000.0)]
Oxygen = Annotated[float, Field(allow_inf_nan=False, ge=0.0, le=100.0)]

# Device identifiers reach logs, dictionary keys and event payloads. The
# allow-list keeps control characters out of log lines (a newline in an
# identifier forges a log entry) and bounds the key space of the per-device
# tables both workers hold.
DeviceUid = Annotated[
    str,
    Field(min_length=1, max_length=config.MAX_DEVICE_UID_LENGTH, pattern=r"^[A-Za-z0-9._:-]+$"),
]

class StrictModel(BaseModel):
    """Rejects unknown fields instead of ignoring them.

    A misspelled field silently defaulting is how a caller ends up scoring
    against 20.9% oxygen it never sent. Refusing is louder and cheaper to debug.
    """

    model_config = ConfigDict(extra="forbid")


class SensorReading(StrictModel):
    temperature: Temperature
    humidity: Humidity
    pressure: Pressure
    airQuality: Optional[AirQuality] = 50.0
    oxygen: Optional[Oxygen] = 20.9


# ----------------- BACKWARD COMPATIBLE --------------
class PredictionRequest(StrictModel):
    deviceUid: DeviceUid
    readings: Optional[Annotated[List[SensorReading], Field(max_length=config.MAX_HISTORY_READINGS)]] = None
    temperature: Optional[Temperature] = None
    humidity: Optional[Humidity] = None
    airQuality: Optional[AirQuality] = None
    oxygen: Optional[Oxygen] = None
    pressure: Optional[Pressure] = None


class PredictionResponse(BaseModel):
    deviceUid: str
    anomaly: bool
    sensor: Optional[str] = None
    value: Optional[FiniteFloat] = None
    confidence: FiniteFloat
    message: str


class TrainingRequest(StrictModel):
    readings: Annotated[
        List[SensorReading], Field(max_length=config.MAX_TRAINING_READINGS)
    ]


class TrainingResponse(BaseModel):
    status: str
    samples_trained: int
    message: str


# ----------------- NEW ENDPOINTS -------------------
class ForecastRequest(StrictModel):
    deviceUid: DeviceUid
    history: Annotated[List[SensorReading], Field(max_length=config.MAX_HISTORY_READINGS)]
    # Bounded because horizon is the transformer's decode length: it multiplies
    # inference cost directly, so an unbounded value is an unbounded CPU spend
    # purchased with a few bytes of request body.
    horizon: int = Field(
        default=1, ge=1, le=config.MAX_FORECAST_HORIZON,
        description="Number of steps to forecast",
    )


class ForecastResponse(BaseModel):
    deviceUid: str
    expected_values: Dict[str, FiniteFloat]
    reasons: List[str]


class AnomalyLevel(BaseModel):
    detected: bool
    score: FiniteFloat
    severity: str
    sensor: Optional[str] = None


class ModelInfo(BaseModel):
    forecastModel: str
    anomalyModel: str
    version: str


class AnomalyAnalysisRequest(StrictModel):
    deviceUid: DeviceUid
    current: SensorReading
    history: Optional[Annotated[List[SensorReading], Field(max_length=config.MAX_HISTORY_READINGS)]] = None


class AnomalyAnalysisResponse(BaseModel):
    deviceUid: str
    timestamp: str
    forecast: Optional[Dict[str, FiniteFloat]] = None
    anomaly: AnomalyLevel
    model: ModelInfo
    reasons: List[str]
