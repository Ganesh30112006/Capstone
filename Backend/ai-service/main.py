"""AI service HTTP surface.

Two independent entry points share one model stack:

  * this FastAPI app — synchronous scoring, forecasting and retraining, called
    by operators through the gateway; and
  * the RabbitMQ worker in `stream_consumer` — asynchronous scoring of the
    backend telemetry branch, entirely off the ingestion write path.

Neither is on the telemetry storage path, so AI being slow or down never blocks
a reading from being stored or a deterministic alert from firing.
"""

import importlib.util
import logging
import os
from contextlib import asynccontextmanager

import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, PlainTextResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

import broker
import config
import metrics as ai_metrics
import model_integrity
import security
import stream_consumer
from stream_processor import stream_processor
from forecast_ledger import METRICS, forecast_ledger
from receipt_store import receipt_store
from chronos_forecaster import chronos_forecaster
from isolation_forest_model import isolation_forest
from ml_service import orchestrator
from models import (
    AnomalyAnalysisRequest,
    AnomalyAnalysisResponse,
    ForecastRequest,
    ForecastResponse,
    PredictionRequest,
    PredictionResponse,
    TrainingRequest,
    TrainingResponse,
)

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s [%(threadName)s] %(levelname)s %(name)s - %(message)s",
)
logger = logging.getLogger(__name__)

# Metric objects live in `metrics` so the model modules can record to the same
# registry without importing this app. Request counters are recorded by
# security.MetricsMiddleware, which has access to the matched route template.
ANOMALIES_DETECTED = ai_metrics.ANOMALIES_DETECTED
WORKER_CONNECTED = ai_metrics.WORKER_CONNECTED

# orjson serialises several times faster than the standard encoder, which is
# worth having on /api/ai/reports and /api/ai/predictions — those return one
# nested document per tracked device, so encoding is a real share of the
# response time. Importing ORJSONResponse always succeeds; it only asserts on
# the missing dependency when instantiated, so the probe has to be for orjson.
if importlib.util.find_spec("orjson") is not None:
    from fastapi.responses import ORJSONResponse as FastestJSONResponse
else:  # pragma: no cover - orjson is an optional accelerator
    from fastapi.responses import JSONResponse as FastestJSONResponse

    logger.info("orjson not installed; falling back to the standard JSON encoder")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    # Reported once, at startup, where an operator will actually see it. A
    # service missing its origin secret or its broker credentials behaves
    # identically to a hardened one until the day it matters.
    broker.audit_credentials()
    # Settings that are each valid alone and incoherent together. A detector
    # weighted below the overall threshold can never fire, and the service goes
    # on answering every request while silently covering one failure mode fewer.
    for severity, message in config.audit_detection_settings():
        (logger.error if severity == "error" else logger.warning)(
            "Detection configuration: %s", message)
    if not model_integrity.is_enabled():
        logger.warning(
            "AI_MODEL_INTEGRITY_KEY is not set: the model artifact is loaded without "
            "verification, and loading a joblib artifact unpickles it — anything that "
            "can write the models volume can execute code in this container."
        )

    # Started here rather than at import time so the worker thread is tied to
    # the application lifecycle and can be shut down cleanly. One worker, on
    # the backend branch: a second one used to score the broadcast branch as
    # well, which meant every reading was scored twice.
    # Receipts from before this start are replayed into the live scores before
    # the consumer starts, so old and new evidence never interleave.
    forecast_ledger.attach_store(receipt_store)
    replayed = forecast_ledger.replay(receipt_store.start())
    logger.info("Forecast receipts: %d replayed; storage %s", replayed, receipt_store.status()["state"])

    stream_consumer.start_consumer()
    logger.info("AI service ready")
    yield
    logger.info("Stopping the AI worker")
    stream_consumer.stop_consumer()
    # Last, so receipts scored during shutdown are written too.
    receipt_store.stop()


app = FastAPI(
    title="Smart Environmental Monitoring — AI Service",
    description="Hybrid anomaly detection (rules + Isolation Forest + Chronos-2 residuals) and forecasting",
    version="3.2.0",
    lifespan=lifespan,
    default_response_class=FastestJSONResponse,
)

# Middleware is applied outermost-last, so the registrations below read
# innermost-first. The order is deliberate: cheap rejections happen before
# expensive ones, and nothing pays for a model slot before it has proved it is
# allowed to ask for inference at all.
#
#   admission  — innermost: only requests that passed every check queue for a slot
#   origin     — reject unauthorised callers before they occupy the queue
#   body size  — cap bytes before any parser allocates for them
#   metrics    — count every response, including the rejections above
#   headers    — outermost, so error responses carry them too
app.add_middleware(security.InferenceAdmissionMiddleware)
app.add_middleware(security.GatewayOriginMiddleware)
app.add_middleware(security.BodySizeLimitMiddleware)
app.add_middleware(security.MetricsMiddleware)
app.add_middleware(security.SecurityHeadersMiddleware)

# The gateway is the only browser-facing origin; direct browser access to this
# service is not part of the architecture. An explicit allow-list replaces the
# previous wildcard, which was paired with allow_credentials and is rejected by
# browsers anyway.
_allowed_origins = [
    origin.strip()
    for origin in os.environ.get("AI_CORS_ALLOWED_ORIGINS", "").split(",")
    if origin.strip()
]
if _allowed_origins:
    # Registered last, so preflight is answered before anything else runs.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=_allowed_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST"],
        allow_headers=["Authorization", "Content-Type"],
    )


@app.get("/metrics")
def metrics():
    WORKER_CONNECTED.labels("scoring").set(1 if stream_consumer.is_connected() else 0)
    ai_metrics.STREAM_TRACKED_DEVICES.set(stream_processor.tracked_devices())
    ai_metrics.MODEL_AVAILABLE.labels("isolation_forest").set(1 if isolation_forest.is_available else 0)
    ai_metrics.MODEL_AVAILABLE.labels("chronos").set(1 if chronos_forecaster.is_available else 0)
    return PlainTextResponse(generate_latest(), media_type=CONTENT_TYPE_LATEST)


def _overall_status(worker_connected: bool) -> str:
    """UP only when this service can actually do its job.

    DOWN is reserved for the anomaly detector being unavailable, because then
    nothing can be scored by any route, HTTP included. A broker that is
    unreachable is DEGRADED rather than DOWN: the scoring endpoints still
    answer, so the service is useful, just not consuming.

    This used to take two workers and report UP when either was connected. With
    one worker there is nothing to fall back to, so a disconnected worker is
    now visible as DEGRADED rather than hidden behind its twin.
    """
    if not isolation_forest.is_available:
        return "DOWN"
    if not worker_connected:
        return "DEGRADED"
    if isolation_forest.degraded_reason:
        return "DEGRADED"
    if any(severity == "error" for severity, _ in config.audit_detection_settings()):
        return "DEGRADED"
    return "UP"


@app.get("/health/live")
def liveness():
    """For the orchestrator: is this process able to serve at all?

    Deliberately independent of the broker. Restarting the service cannot fix
    a broker that is down, and cycling it during an outage would throw away
    the flatline detector's per-device history -- which is learned from the
    stream and takes time to rebuild -- for no gain.
    """
    return {"status": "UP", "service": "ai-service"}


@app.get("/health/ready")
def readiness():
    """For the operator and any load balancer: is it doing its job?

    503 when it is not, so "not consuming telemetry" cannot be mistaken for
    "consuming it fine" by anything that only looks at the response code.
    """
    worker_connected = stream_consumer.is_connected()
    status = _overall_status(worker_connected)
    body = {
        "status": status,
        "service": "ai-service",
        "scoringWorkerConnected": worker_connected,
        "detectorAvailable": isolation_forest.is_available,
    }
    return JSONResponse(status_code=200 if status == "UP" else 503, content=body)


@app.get("/health")
def health_check():
    """What this service can currently do, and what it cannot.

    The reasoning that produced an unconditional UP here was sound about one
    thing and wrong about another. It is right that a disconnected worker must
    not make an orchestrator cycle a process that is serving requests
    perfectly well -- restarting it does not reconnect a broker that is down.
    But the conclusion drawn from that was to report UP regardless, which made
    one field answer two different questions: "should you restart me?" and "am
    I doing my job?".

    Those come apart exactly when it matters. With the broker down, both
    consumers are dead and not a single reading is being scored, while
    `status` still read UP and the operator console showed AI Inference as
    operational -- the detail block underneath it saying, truthfully,
    "reconnecting to the broker".

    So the two questions now have two endpoints. /health/live answers the
    orchestrator and stays up as long as the process can serve, so nothing
    cycles this service over a broker outage. /health/ready and the `status`
    below answer the operator, and say DEGRADED when telemetry is not being
    consumed. The gateway maps anything that is not UP to a degraded badge,
    so the console stops claiming a capability the service does not have.
    """
    worker_connected = stream_consumer.is_connected()
    return {
        "status": _overall_status(worker_connected),
        "service": "ai-service",
        "models": {
            "chronos": "LOADED" if chronos_forecaster.is_available else "UNLOADED",
            "isolationForest": "LOADED" if isolation_forest.is_available else "UNLOADED",
            # Surfaced because a detector trained on synthetic data, or one that
            # has degraded, still answers every request without complaining.
            "isolationForestDegraded": isolation_forest.degraded_reason,
        },
        # A service running without origin enforcement or artifact verification
        # looks identical to a hardened one from the outside. It should not.
        "security": {
            "gatewayOriginEnforced": bool(
                config.GATEWAY_ENFORCE_ORIGIN and config.GATEWAY_INTERNAL_SECRET
            ),
            "modelIntegrityEnforced": model_integrity.is_enabled(),
            "maxRequestBytes": config.MAX_REQUEST_BYTES,
            "inferenceSlots": config.MAX_CONCURRENT_INFERENCE,
        },
        # Empty on a coherent deployment. Non-empty means the service is
        # running and answering while some failure mode it was deployed to
        # catch cannot be reported at all — which is invisible from every other
        # field here.
        "detectionConfigFindings": [
            {"severity": severity, "message": message}
            for severity, message in config.audit_detection_settings()
        ],
        "messaging": {
            "scoringWorkerConnected": worker_connected,
            "trackedStreamDevices": stream_processor.tracked_devices(),
            "detail": "consuming telemetry" if worker_connected else "reconnecting to the broker",
        },
    }


@app.get("/api/ai/model-info")
def model_info():
    return orchestrator.get_model_info()


@app.post("/api/ai/predict-anomaly", response_model=PredictionResponse)
def predict_anomaly_legacy(request: PredictionRequest):
    """Single-reading scoring, kept for compatibility with existing callers."""
    try:
        result = orchestrator.predict_legacy(request)
        if result.get("anomaly"):
            ANOMALIES_DETECTED.inc()
        return PredictionResponse(**result)
    except HTTPException:
        raise
    except Exception:
        logger.exception("Legacy prediction failed")
        raise HTTPException(status_code=500, detail="Prediction failed")


@app.post("/api/ai/anomaly-analysis", response_model=AnomalyAnalysisResponse)
def analyze_anomaly(request: AnomalyAnalysisRequest):
    """Full hybrid analysis: rules, Isolation Forest and forecast residuals."""
    try:
        result = orchestrator.analyze_anomaly(request)
        if result.anomaly.detected:
            ANOMALIES_DETECTED.inc()
        return result
    except HTTPException:
        raise
    except Exception:
        logger.exception("Anomaly analysis failed for %s", request.deviceUid)
        raise HTTPException(status_code=500, detail="Anomaly analysis failed")


@app.post("/api/ai/forecast", response_model=ForecastResponse)
def generate_forecast(request: ForecastRequest):
    if not request.history:
        raise HTTPException(
            status_code=400,
            detail="Forecasting requires historical readings; supply at least one in 'history'.",
        )
    try:
        return orchestrator.forecast(request)
    except HTTPException:
        raise
    except Exception:
        logger.exception("Forecast failed for %s", request.deviceUid)
        raise HTTPException(status_code=500, detail="Forecast failed")


@app.post("/api/ai/train", response_model=TrainingResponse)
def train_anomaly_model(request: TrainingRequest, http_request: Request):
    if not request.readings:
        raise HTTPException(status_code=400, detail="Cannot train on an empty dataset")

    # Retraining replaces the live detector for the whole fleet, so who did it
    # and how much data they supplied is worth a durable record. The identity
    # comes from the gateway-verified header, and is scrubbed before it is
    # written because it arrives over the network like everything else.
    actor = security.scrub(http_request.headers.get(config.HEADER_USER_ID) or "unknown")
    logger.warning("Model retraining requested by user %s with %d readings",
                   actor, len(request.readings))

    try:
        df = pd.DataFrame([reading.model_dump() for reading in request.readings])
        samples_trained = orchestrator.train_model(df)
        logger.warning("Model retrained by user %s on %d samples", actor, samples_trained)

        return TrainingResponse(
            status="SUCCESS",
            samples_trained=samples_trained,
            message="Isolation Forest retrained on the supplied historical data.",
        )
    except HTTPException:
        raise
    except ValueError as exc:
        # A well-formed request carrying a dataset that cannot produce a usable
        # model. The caller can fix that; it is not a server fault.
        raise HTTPException(status_code=422, detail=str(exc))
    except Exception:
        logger.exception("Model retraining failed")
        raise HTTPException(status_code=500, detail="Model retraining failed")


@app.get("/api/ai/predictions")
def list_predictions():
    """Latest cached forecast per device, produced by the live-stream worker."""
    return {"predictions": stream_processor.all_predictions()}


@app.get("/api/ai/predictions/{device_uid}")
def get_prediction(device_uid: str):
    prediction = stream_processor.latest_prediction(device_uid)
    if prediction is None:
        raise HTTPException(
            status_code=404,
            detail="No forecast yet for this device; it needs more live readings.",
        )
    return prediction


@app.get("/api/public/forecasts/skill")
def get_public_forecast_skill():
    """How the live forecasts have scored against the readings they predicted.

    Declared before the per-station route so "skill" is never read as a
    station id.
    """
    return forecast_ledger.summary()


@app.get("/api/public/forecasts/{station_id}/receipts")
def get_public_forecast_receipts(station_id: str, metric: str = "temperature", limit: int = 60):
    """One station's recent forecasts beside what actually arrived, for the live chart."""
    if not security.valid_device_uid(station_id):
        raise HTTPException(status_code=400, detail="Not a valid station id.")
    if metric not in METRICS:
        raise HTTPException(status_code=400, detail=f"metric must be one of {', '.join(METRICS)}.")
    return {"stationId": station_id, "metric": metric, "receipts": forecast_ledger.recent(station_id, metric, limit)}


@app.get("/api/ai/forecast-receipts")
def get_forecast_receipts(days: float = 7, limit: int = 500_000):
    """Receipts as CSV, for offline analysis (operators only, via the gateway).

    From the database when it can answer, otherwise from memory -- and the
    X-Receipts-Source header says which, because a memory export only goes
    back to the last restart.
    """
    days = min(max(days, 0.01), 90.0)
    limit = min(max(limit, 1), 2_000_000)
    stored = receipt_store.export(days, limit)
    source = "database" if stored is not None else "memory"
    return Response(
        content=forecast_ledger.receipts_csv(stored),
        media_type="text/csv",
        headers={
            "Content-Disposition": 'attachment; filename="forecast-receipts.csv"',
            "X-Receipts-Source": source,
        },
    )


@app.get("/api/public/forecasts/{station_id}")
def get_public_forecast(station_id: str):
    """The latest short-range forecast for a station, for the public portal.

    A projection of the operator prediction: the expected next readings per
    metric and how they were produced, without event ids or model internals.

    The horizon is counted in readings, not hours or days, because that is what
    the model forecasts: the next ``horizon`` readings from the last
    ``historyPoints``. At one reading a second that is seconds ahead. A page
    that labelled these steps "today" and "tomorrow" would be claiming a
    weather forecast this model does not make.
    """
    prediction = stream_processor.latest_prediction(station_id)
    if prediction is None:
        raise HTTPException(
            status_code=404,
            detail="No forecast yet for this station; it needs more live readings.",
        )
    return {
        "stationId": prediction.get("deviceUid", station_id),
        "generatedAt": prediction.get("generatedAt"),
        "horizonReadings": prediction.get("horizon"),
        "historyPoints": prediction.get("historyPoints"),
        "method": prediction.get("method"),
        "predictions": prediction.get("predictions") or {},
    }


@app.get("/api/ai/reports")
def list_reports():
    return {"reports": stream_processor.all_reports()}


@app.get("/api/ai/reports/{device_uid}")
def get_report(device_uid: str):
    report = stream_processor.latest_report(device_uid) or stream_processor.generate_report_now(device_uid)
    if report is None:
        raise HTTPException(
            status_code=404,
            detail="No readings retained for this device; nothing to report on yet.",
        )
    return report


@app.post("/api/ai/reports/{device_uid}")
def generate_report(device_uid: str):
    """Force a fresh rolling report from whatever window is currently retained."""
    report = stream_processor.generate_report_now(device_uid)
    if report is None:
        raise HTTPException(
            status_code=404,
            detail="No readings retained for this device; nothing to report on yet.",
        )
    return report


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Reports which field was rejected and why, but never the value.

    FastAPI's default handler includes the offending `input` in the response.
    For a service whose inputs are telemetry that is mostly harmless, but it
    reflects attacker-controlled bytes straight back into a response body, and
    it is not information the caller needs to fix their request.
    """
    errors = [
        {"field": ".".join(str(part) for part in error.get("loc", ())),
         "message": error.get("msg", "Invalid value")}
        for error in exc.errors()[:20]
    ]
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "status": 422,
            "error": "Unprocessable Entity",
            "message": "The request payload failed validation.",
            "errors": errors,
            "path": request.url.path,
        },
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    """Never return a stack trace to a caller."""
    logger.exception("Unhandled error on %s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "status": 500,
            "error": "Internal Server Error",
            "message": "An unexpected error occurred",
            "path": request.url.path,
        },
    )
