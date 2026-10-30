"""Prometheus metrics shared by the HTTP surface and both consumer threads.

Kept in one module so the model code can record without importing the FastAPI
app, and so a metric is never registered twice under different names.

The AI branch is allowed to lag, which makes it exactly the kind of component
that fails quietly. These counters are how that becomes visible: a detector
that has switched itself off still serves 200s, and only `ai_model_available`
says otherwise.
"""

from prometheus_client import Counter, Gauge, Histogram

# --- HTTP -------------------------------------------------------------------
# `endpoint` carries the matched *route template* ("/api/ai/reports/{device_uid}"),
# never the raw path. A label whose values come from the URL is unbounded: every
# distinct device id — or every random string an attacker sends — becomes a
# permanent time series in this process's registry, which is a memory-exhaustion
# vector dressed up as monitoring.
REQUEST_COUNT = Counter(
    "ai_service_requests_total", "Total requests", ["method", "endpoint", "status"]
)
REQUEST_LATENCY = Histogram(
    "ai_service_request_latency_seconds", "Request latency", ["method", "endpoint"]
)

# --- Security ---------------------------------------------------------------
SECURITY_REJECTIONS = Counter(
    "ai_service_security_rejections_total",
    "Requests refused by the hardening layer",
    ["reason"],
)
INFERENCE_IN_FLIGHT = Gauge(
    "ai_inference_in_flight", "Model calls currently admitted"
)
INFERENCE_REJECTED = Counter(
    "ai_inference_rejected_total", "Inference requests shed because all slots were busy"
)
INFERENCE_QUEUE_WAIT = Histogram(
    "ai_inference_queue_wait_seconds", "Time spent waiting for an inference slot"
)
INVALID_READINGS = Counter(
    "ai_invalid_readings_total",
    "Readings refused as unscoreable",
    ["reason"],
)

# --- Detection --------------------------------------------------------------
ANOMALIES_DETECTED = Counter("ai_service_anomalies_total", "Anomalies detected")

# Every detection is counted above, announced or not, so suppression can be
# measured rather than assumed: detected minus announced is what this holds.
ANOMALIES_SUPPRESSED = Counter(
    "ai_service_anomalies_suppressed_total",
    "Anomalies detected but not re-announced, the condition already standing",
)

MODEL_AVAILABLE = Gauge(
    "ai_model_available",
    "1 when a model can score, 0 when it has degraded to unavailable",
    ["model"],
)

# --- Chronos ----------------------------------------------------------------
CHRONOS_INFERENCES = Counter(
    "ai_chronos_inferences_total", "Batched Chronos inference calls"
)
CHRONOS_SERIES = Counter(
    "ai_chronos_series_total", "Individual feature series forecast by Chronos"
)
CHRONOS_LATENCY = Histogram(
    "ai_chronos_inference_seconds", "Chronos batched inference latency"
)
CHRONOS_FAILURES = Counter(
    "ai_chronos_failures_total", "Chronos inference calls that raised"
)

# --- Live-stream worker -----------------------------------------------------
STREAM_READINGS = Counter(
    "ai_stream_readings_total", "Readings processed by the live-stream worker"
)
STREAM_EVENTS = Counter(
    "ai_stream_events_total", "Events published by the live-stream worker", ["type"]
)
STREAM_FORECAST_REUSE = Counter(
    "ai_stream_forecast_reuse_total",
    "Readings scored against a cached forecast instead of a fresh inference",
)
STREAM_TRACKED_DEVICES = Gauge(
    "ai_stream_tracked_devices", "Devices currently held in the stream worker's table"
)

# --- Workers ----------------------------------------------------------------
WORKER_CONNECTED = Gauge(
    "ai_service_worker_connected", "1 when a RabbitMQ worker is connected", ["worker"]
)
