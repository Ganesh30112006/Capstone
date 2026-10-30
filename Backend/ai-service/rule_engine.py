"""Deterministic bounds on what an instrument can physically report.

This is the only detector that is always available, needs no model and cannot
degrade, which makes it the floor the rest of the stack is measured against. It
is also, for that reason, the layer that must not be bypassable.

It was. Every threshold here is a comparison, and every comparison against
`NaN` evaluates false — `NaN > TEMP_MAX` is false and so is `NaN < TEMP_MIN` —
so a reading of "not a number" passed all five checks and was reported as
normal. The Isolation Forest then failed on it and the residual detector had
nothing to compare, so the hybrid score came back 0.0 with the message
"Parameters normal". A single non-numeric value switched off the entire
detector for that reading.

Schema validation now rejects non-finite values at the HTTP edge, but readings
also arrive over the broker, where they are parsed by `float()` — which happily
accepts the strings "NaN" and "Infinity". So the guard belongs here as well:
this is where the bypass existed, and this is the layer that has to hold when
the ones above it are not in the path.
"""

import math
from typing import Dict, List, Optional

import config


class RuleResult:
    def __init__(self, is_anomaly: bool, severity: str, reasons: List[str], max_sensor: Optional[str]):
        self.is_anomaly = is_anomaly
        self.severity = severity
        self.reasons = reasons
        self.max_sensor = max_sensor


class RuleEngine:

    def evaluate(self, readings: Dict[str, float]) -> RuleResult:
        reasons = []
        is_hard_anomaly = False
        max_sensor = None

        temp = readings.get('temperature', 25.0)
        hum = readings.get('humidity', 50.0)
        aq = readings.get('airQuality', 50.0)
        ox = readings.get('oxygen', 20.9)
        press = readings.get('pressure', 1013.25)

        # Checked before any threshold comparison, because a non-finite value
        # silently passes all of them. An instrument that reports NaN or
        # infinity is broken, which is exactly what this engine exists to catch,
        # so it is treated as a critical anomaly rather than waved through.
        for name, value in (("temperature", temp), ("humidity", hum), ("airQuality", aq),
                            ("oxygen", ox), ("pressure", press)):
            if not _is_finite(value):
                is_hard_anomaly = True
                max_sensor = max_sensor or name
                reasons.append(
                    f"{name} reported a non-numeric value ({value!r}); the sensor is faulty "
                    "or the reading was tampered with."
                )

        if _is_finite(temp) and (temp > config.TEMP_MAX or temp < config.TEMP_MIN):
            is_hard_anomaly = True
            max_sensor = 'temperature'
            reasons.append(f"Temperature {temp}°C violates hard boundaries [{config.TEMP_MIN}, {config.TEMP_MAX}].")

        if _is_finite(hum) and (hum > config.HUM_MAX or hum < config.HUM_MIN):
            is_hard_anomaly = True
            max_sensor = 'humidity'
            reasons.append(f"Humidity {hum}% violates hard boundaries [{config.HUM_MIN}, {config.HUM_MAX}].")

        if _is_finite(aq) and aq > config.AQ_MAX:
            is_hard_anomaly = True
            max_sensor = 'airQuality'
            reasons.append(f"Air Quality {aq} exceeds extreme limit of {config.AQ_MAX}.")

        if _is_finite(ox) and (ox > config.OX_MAX or ox < config.OX_MIN):
            is_hard_anomaly = True
            max_sensor = 'oxygen'
            reasons.append(f"Oxygen {ox}% violates hard boundaries [{config.OX_MIN}, {config.OX_MAX}].")

        if _is_finite(press) and (press > config.PRESSURE_MAX or press < config.PRESSURE_MIN):
            is_hard_anomaly = True
            max_sensor = 'pressure'
            reasons.append(f"Pressure {press}hPa violates hard boundaries [{config.PRESSURE_MIN}, {config.PRESSURE_MAX}].")

        severity = "CRITICAL" if is_hard_anomaly else "NORMAL"
        return RuleResult(is_anomaly=is_hard_anomaly, severity=severity, reasons=reasons, max_sensor=max_sensor)


def _is_finite(value) -> bool:
    """True only for a real number. Anything else is not comparable."""
    try:
        return math.isfinite(float(value))
    except (TypeError, ValueError):
        return False


rule_engine = RuleEngine()
