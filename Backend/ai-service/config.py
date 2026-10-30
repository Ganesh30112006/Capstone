import os
from pathlib import Path


def _flag(name: str, default: bool) -> bool:
    """Environment flags are read strictly: only an explicit 'false' disables a
    protection. A typo must not silently turn one off."""
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"false", "0", "no", "off"}


# Paths
BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR / "models"
# v2 bundles the scaler with the forest in one Pipeline. v1 stored the forest
# alone, which reloaded into a detector that silently scored everything 0.0, so
# the directory is versioned alongside the artifact layout.
IF_MODEL_PATH = MODELS_DIR / "isolation_forest" / "v3" / "model.joblib"
METADATA_PATH = MODELS_DIR / "isolation_forest" / "v3" / "metadata.json"
# Sidecar holding the HMAC of the artifact above. See model_integrity: joblib
# is pickle, so loading an artifact executes whatever is inside it.
IF_SIGNATURE_PATH = MODELS_DIR / "isolation_forest" / "v3" / "model.joblib.sig"

# Hardware Constraints
DEVICE_MAP = "cpu"  # Assuming container runs on Fargate CPU

# Deterministic Thresholds
TEMP_MAX = float(os.environ.get('AI_TEMP_MAX', 45.0))
TEMP_MIN = float(os.environ.get('AI_TEMP_MIN', -5.0))
AQ_MAX = float(os.environ.get('AI_AQ_MAX', 300.0))
OX_MAX = float(os.environ.get('AI_OX_MAX', 24.0))
OX_MIN = float(os.environ.get('AI_OX_MIN', 15.0))
HUM_MAX = float(os.environ.get('AI_HUM_MAX', 100.0))
HUM_MIN = float(os.environ.get('AI_HUM_MIN', 0.0))
PRESSURE_MIN = float(os.environ.get('AI_PRESSURE_MIN', 900.0))
PRESSURE_MAX = float(os.environ.get('AI_PRESSURE_MAX', 1100.0))

# Isolation Forest Hyperparameters
IF_CONTAMINATION = float(os.environ.get('AI_IF_CONTAMINATION', 0.03))

# How many trees. Scoring walks every tree for every reading, so this is the
# main lever on the service's throughput: measured through the shipped scoring
# path, 10 trees cost 31 us per reading, 50 cost 106, and 200 cost 1014.
#
# That cost used to be dominated by a fixed per-call overhead instead, which
# made tree count matter much less than it does now — scikit-learn re-validates
# each tree on every call, so a 50-tree model spent ~1,000 us per reading almost
# regardless of the arithmetic. The service now bypasses that (see _FastScorer),
# which cut per-reading cost about 8x and left tree count as a roughly linear
# dial rather than a rounding error.
#
# 50 rather than scikit-learn's 100, and re-checked against the faster path:
# recall on multivariate outliers — the failure mode this detector is solely
# responsible for — is 100% at every size from 10 to 200, so that is not what
# decides it. 50 is the smallest size that also holds 100% on drift (25 trees
# drops to 90%) while keeping false alarms at 0.9%.
# See benchmarks/model_sizing.py.
IF_N_ESTIMATORS = int(os.environ.get('AI_IF_N_ESTIMATORS', 50))

# Sensitivity of the score calibration; see isolation_forest_model.train. Lower
# is more sensitive. 0.30 was chosen by sweeping it against labelled data:
# below it the detector accuses ordinary readings more often, above it recall on
# borderline drift falls away.
IF_CALIBRATION_FACTOR = float(os.environ.get('AI_IF_CALIBRATION_FACTOR', 0.30))

# Smallest dataset worth fitting. Below this the StandardScaler has almost no
# variance to work with and the forest has nothing to isolate against, so the
# result is a model that looks trained and detects nothing. Refusing is more
# honest than returning SUCCESS for a detector that cannot work.
MIN_TRAINING_SAMPLES = int(os.environ.get('AI_MIN_TRAINING_SAMPLES', 50))

# Anomaly Scoring Weights.
#
# Under the noisy-OR combiner in anomaly_scorer, a weight is the ceiling of what
# one detector can assert on its own: a detector whose weight is below the
# threshold can never fire alone, however certain it is. So these three numbers
# are one decision, not three, and they were chosen by sweeping them together
# against labelled data — see benchmarks/detection_quality.py, section 4.
#
#   residual 0.8 >= 0.75  the forecast residual alone can raise an anomaly
#   forest   0.4 <  0.75  the Isolation Forest needs corroboration
#
# The forest deliberately cannot fire alone. That is what the measurements say:
# every configuration in which it could scored worse, because it is trained on
# the fleet's own distribution and flags ordinary seasonal movement away from
# it. It is corroborating evidence, and it is the only detector that catches a
# reading whose individual values are all plausible and whose combination is
# not.
#
# Re-run the sweep after installing Chronos. These were measured against a
# deliberately weak stand-in for the residual detector, so they are a
# conservative floor rather than a tuned optimum.
#
# There is no rule weight. Rules are a hard override, not a vote — a reading
# outside an instrument's physical envelope is a certainty, and the knob that
# used to weight it existed only to be set to a value that broke everything
# else. Removed rather than kept as a setting that does nothing.
W_IF = float(os.environ.get('AI_WEIGHT_IF', 0.4))
W_RESIDUAL = float(os.environ.get('AI_WEIGHT_RESIDUAL', 0.8))
# Above the threshold, so a frozen instrument raises an anomaly on its own. It
# has to: a flat series is exactly the case where the residual detector reports
# perfect agreement, so a stuck sensor looks healthier than a working one to
# every other detector in the stack.
W_FLATLINE = float(os.environ.get('AI_WEIGHT_FLATLINE', 0.9))
ANOMALY_THRESHOLD = float(os.environ.get('AI_OVERALL_THRESHOLD', 0.75))

# Flatline detection. The alarm point is learned per device and per channel — a
# multiple of the longest run that channel has previously completed — because a
# fixed threshold cannot work here.
#
# The earlier version assumed "a real instrument dithers in its last digit even
# in a still room". Measured, that is false: instruments quantise, and a healthy
# oxygen sensor in calm conditions held one value for 1,362 consecutive readings
# while every other channel kept moving. A fixed threshold is therefore either
# above that, and blind to real freezes elsewhere, or below it, and permanently
# accusing a working sensor. See flatline_detector for the measurements.
FLATLINE_RUN_MULTIPLIER = float(os.environ.get('AI_FLATLINE_RUN_MULTIPLIER', 4.0))
# Floor, so a channel that has only ever shown very short runs still needs a
# substantial one before it is accused.
FLATLINE_MIN_RUN = int(os.environ.get('AI_FLATLINE_MIN_RUN', 30))
# Completed runs a channel must show before it can be judged against itself.
FLATLINE_WARMUP_RUNS = int(os.environ.get('AI_FLATLINE_WARMUP_RUNS', 5))
# Optional hard ceiling on the learned alarm point. 0 disables it, which is the
# default and the recommended setting.
#
# This was 3000 and described as a backstop for a channel frozen before startup.
# It never did that: an un-warmed channel is skipped before the ceiling is even
# consulted, so the only channels it affected were healthy ones whose learned
# threshold had grown past it — precisely the quantised-stable channels the
# learned baseline exists to protect. Measured, a channel with a natural run of
# 1,400 readings was accused after 3,000 with nothing wrong.
#
# Set it only for a site that knows its instruments genuinely dither, where a
# guaranteed upper bound on detection latency is worth accepting false alarms
# on the steadiest channels.
FLATLINE_ABSOLUTE_RUN = int(os.environ.get('AI_FLATLINE_ABSOLUTE_RUN', 0))
# Two readings count as identical within this. Not a tolerance band: the point
# is bit-identical repetition, and a wider window would swallow real slow drift.
FLATLINE_EPSILON = float(os.environ.get('AI_FLATLINE_EPSILON', 1e-9))
# Bound on the per-device table this detector keeps, like the stream worker's.
FLATLINE_MAX_DEVICES = int(os.environ.get('AI_FLATLINE_MAX_DEVICES', 500))

# Bound on the broker worker's forecast table. Same reasoning as the two above:
# device identifiers come off the wire, so any per-device map that is never
# pruned grows with every identifier the service has ever been shown.
FORECAST_CACHE_MAX_DEVICES = int(os.environ.get('AI_FORECAST_CACHE_MAX_DEVICES', 500))

# Chronos Model Configuration
CHRONOS_MODEL = "amazon/chronos-2"

# RabbitMQ Config
RABBITMQ_HOST = os.environ.get('RABBITMQ_HOST', 'localhost')
RABBITMQ_PORT = int(os.environ.get('RABBITMQ_PORT', 5672))
RABBITMQ_USER = os.environ.get('RABBITMQ_USERNAME', 'guest')
RABBITMQ_PASS = os.environ.get('RABBITMQ_PASSWORD', 'guest')
RABBITMQ_EXCHANGE = "weather.events"

# How many recent readings per device the async worker keeps as forecast
# context. Chronos needs a window to forecast against; with an empty history the
# residual detector contributes nothing and scoring falls back to rules plus the
# isolation forest alone.
AI_HISTORY_WINDOW = int(os.environ.get('AI_HISTORY_WINDOW', 48))

# Live-stream AI worker (iot.telemetry.stream, the direct AWS IoT Core branch).
AI_STREAM_FORECAST_EVERY = int(os.environ.get('AI_STREAM_FORECAST_EVERY', 10))
AI_STREAM_REPORT_EVERY = int(os.environ.get('AI_STREAM_REPORT_EVERY', 30))
AI_STREAM_MIN_HISTORY = int(os.environ.get('AI_STREAM_MIN_HISTORY', 8))
AI_STREAM_FORECAST_HORIZON = int(os.environ.get('AI_STREAM_FORECAST_HORIZON', 6))

# How long a standing anomaly goes without being re-announced on the bus. A
# station reporting every second re-detects the same condition every second;
# the event says the condition exists, and repeating it does not make it truer.
# Set to 0 to announce every detection.
AI_ANOMALY_REASSERT_SECONDS = int(os.environ.get('AI_ANOMALY_REASSERT_SECONDS', 300))
AI_STREAM_MAX_DEVICES = int(os.environ.get('AI_STREAM_MAX_DEVICES', 500))

# ---------------------------------------------------------------------------
# Forecast receipts (forecast_ledger.py, receipt_store.py)
# ---------------------------------------------------------------------------
# Below this many paired receipts a skill score is withheld rather than estimated.
AI_LEDGER_MIN_SCORED = int(os.environ.get('AI_LEDGER_MIN_SCORED', 30))
# Raw receipts held in memory for the live chart and the in-memory export.
AI_LEDGER_MAX_RECEIPTS = int(os.environ.get('AI_LEDGER_MAX_RECEIPTS', 20000))
# Readings fitted for the trend-line baseline, at forecast issue.
AI_LEDGER_TREND_POINTS = int(os.environ.get('AI_LEDGER_TREND_POINTS', 8))
# Time-of-day slot width for the "same time yesterday" baseline, and how far
# back a slot's value may be to count as yesterday's (hours, inclusive).
AI_LEDGER_DAILY_BUCKET_MINUTES = int(os.environ.get('AI_LEDGER_DAILY_BUCKET_MINUTES', 5))
AI_LEDGER_DAILY_MIN_HOURS = float(os.environ.get('AI_LEDGER_DAILY_MIN_HOURS', 20))
AI_LEDGER_DAILY_MAX_HOURS = float(os.environ.get('AI_LEDGER_DAILY_MAX_HOURS', 28))

# Where receipts are kept so the scores survive a restart. The AI service owns
# its own database, like every other service. Empty disables persistence; the
# page then says its scores only go back to the last start.
AI_DB_URL = os.environ.get(
    'AI_DB_URL',
    'postgresql://{user}:{password}@{host}:5432/ai_db'.format(
        user=os.environ.get('POSTGRES_USER', 'postgres'),
        password=os.environ.get('POSTGRES_PASSWORD', ''),
        host=os.environ.get('AI_DB_HOST', 'localhost'),
    ),
)
# How much history is replayed into the live scores at start-up.
AI_LEDGER_REPLAY_DAYS = float(os.environ.get('AI_LEDGER_REPLAY_DAYS', 7))
# Receipts waiting to be written; beyond this the oldest are dropped and counted.
AI_LEDGER_WRITE_QUEUE = int(os.environ.get('AI_LEDGER_WRITE_QUEUE', 50000))

# ---------------------------------------------------------------------------
# Security
# ---------------------------------------------------------------------------
# Shared secret proving a request was proxied by the API Gateway, mirroring the
# GatewayAuthenticationFilter every Java service runs. Without this the AI
# service is the one component in the platform that accepts anything reaching
# its port — including POST /api/ai/train, which replaces the live detector.
GATEWAY_INTERNAL_SECRET = os.environ.get('GATEWAY_INTERNAL_SECRET', '')
GATEWAY_ENFORCE_ORIGIN = _flag('GATEWAY_ENFORCE_ORIGIN', True)

# Header names must match what the gateway attaches after it verifies the JWT.
HEADER_GATEWAY_AUTH = 'X-Gateway-Auth'
HEADER_USER_ID = 'X-User-Id'
HEADER_USER_ROLE = 'X-User-Role'

# Key for the artifact HMAC. Unset means integrity is not enforced, which is
# logged loudly at startup: an unsigned pickle is an arbitrary-code-execution
# primitive for anyone who can write to the models volume.
MODEL_INTEGRITY_KEY = os.environ.get('AI_MODEL_INTEGRITY_KEY', '')
MODEL_INTEGRITY_REQUIRED = _flag('AI_MODEL_INTEGRITY_REQUIRED', False)

# Largest request body accepted, before parsing. Inference and training both
# scale with body size, so an unbounded body is an unbounded allocation.
MAX_REQUEST_BYTES = int(os.environ.get('AI_MAX_REQUEST_BYTES', 2 * 1024 * 1024))
# Largest broker message accepted, for the same reason on the AMQP side.
MAX_MESSAGE_BYTES = int(os.environ.get('AI_MAX_MESSAGE_BYTES', 256 * 1024))

# Payload shape bounds. These cap work per request; the body-size limit alone
# does not, because a small body can still request a huge forecast horizon.
MAX_HISTORY_READINGS = int(os.environ.get('AI_MAX_HISTORY_READINGS', 2000))
MAX_TRAINING_READINGS = int(os.environ.get('AI_MAX_TRAINING_READINGS', 100_000))
MAX_FORECAST_HORIZON = int(os.environ.get('AI_MAX_FORECAST_HORIZON', 48))
# Matches device-service's own 3–100 character bound on deviceUid.
MAX_DEVICE_UID_LENGTH = int(os.environ.get('AI_MAX_DEVICE_UID_LENGTH', 100))

# Concurrent model calls admitted. Inference is CPU-bound, so letting every
# arriving request start one turns a burst into thrashing that makes each
# request slower than queueing would have. Excess is refused with 503 and
# Retry-After rather than accepted into a queue that cannot drain.
MAX_CONCURRENT_INFERENCE = int(os.environ.get('AI_MAX_CONCURRENT_INFERENCE', 4))
INFERENCE_QUEUE_TIMEOUT = float(os.environ.get('AI_INFERENCE_QUEUE_TIMEOUT', 5.0))

# RabbitMQ transport security. Credentials and telemetry cross the network in
# the clear without this.
RABBITMQ_SSL = _flag('RABBITMQ_SSL', False)
RABBITMQ_VHOST = os.environ.get('RABBITMQ_VHOST', '/')


# ---------------------------------------------------------------------------
# Coherence
# ---------------------------------------------------------------------------

def audit_detection_settings():
    """Settings that are individually valid and jointly incoherent.

    Every value above is in range on its own. The failures this catches are
    *relationships* between them, and the reason it exists is that this exact
    class of bug has shipped here three times:

      * the original scorer summed a rule weight into the denominator, capping
        every achievable score at 0.500 against a threshold of 0.75 — both
        models could be pinned at maximum certainty and the service stayed
        silent, with the whole test suite passing;
      * the flatline detector's score floor was set below
        ANOMALY_THRESHOLD / W_FLATLINE, so it reported a frozen sensor
        internally while the service reported nothing; and
      * FLATLINE_WARMUP_RUNS at 0 was measured raising alarms on 95% of
        readings from a healthy instrument.

    None of those are visible in a single setting. What they share is that the
    service keeps running and answering, and simply never reports the thing it
    was deployed to report — so this returns findings rather than raising, and
    the caller logs them where an operator will see them.

    Returns a list of (severity, message), severity being "error" or "warning".
    """
    findings = []

    if not 0.0 < ANOMALY_THRESHOLD <= 1.0:
        findings.append((
            "error",
            f"AI_OVERALL_THRESHOLD is {ANOMALY_THRESHOLD}. Scores are bounded to "
            f"[0, 1], so at this value the service "
            f"{'flags every reading' if ANOMALY_THRESHOLD <= 0 else 'can never flag one'}."
        ))

    # Under the noisy-OR combiner a detector's weight is the ceiling of what it
    # can assert alone, so a weight below the threshold means that detector can
    # never raise an anomaly by itself however certain it is.
    for name, weight, alone in (
        ("AI_WEIGHT_FLATLINE", W_FLATLINE, True),
        ("AI_WEIGHT_RESIDUAL", W_RESIDUAL, False),
    ):
        if weight < ANOMALY_THRESHOLD:
            detail = (
                " Nothing else in the stack detects a frozen sensor — rules, the "
                "Isolation Forest and the forecast residual all measured 0% recall "
                "on it, and a flat series drives the residual to perfect agreement "
                "— so this silently removes that failure mode from coverage."
                if alone else
                " This detector now needs corroboration to fire."
            )
            findings.append((
                "error" if alone else "warning",
                f"{name} is {weight}, below AI_OVERALL_THRESHOLD "
                f"({ANOMALY_THRESHOLD}), so it can never raise an anomaly alone.{detail}"
            ))

    for name, weight in (("AI_WEIGHT_IF", W_IF), ("AI_WEIGHT_RESIDUAL", W_RESIDUAL),
                         ("AI_WEIGHT_FLATLINE", W_FLATLINE)):
        if not 0.0 <= weight <= 1.0:
            findings.append((
                "error",
                f"{name} is {weight}; a noisy-OR weight outside [0, 1] is not a "
                f"probability and makes the combined score meaningless."
            ))

    if FLATLINE_WARMUP_RUNS < 1:
        findings.append((
            "error",
            f"AI_FLATLINE_WARMUP_RUNS is {FLATLINE_WARMUP_RUNS}. A channel is then "
            f"judged before it has shown that it can change at all, which measured "
            f"alarms on 95% of readings from a healthy instrument."
        ))

    if FLATLINE_RUN_MULTIPLIER <= 0 or FLATLINE_MIN_RUN < 1:
        findings.append((
            "error",
            f"AI_FLATLINE_RUN_MULTIPLIER={FLATLINE_RUN_MULTIPLIER} and "
            f"AI_FLATLINE_MIN_RUN={FLATLINE_MIN_RUN} put the alarm point at or below "
            f"a single repeated reading, which every quantised instrument produces."
        ))

    if FLATLINE_ABSOLUTE_RUN and FLATLINE_ABSOLUTE_RUN < FLATLINE_MIN_RUN:
        findings.append((
            "warning",
            f"AI_FLATLINE_ABSOLUTE_RUN ({FLATLINE_ABSOLUTE_RUN}) is below "
            f"AI_FLATLINE_MIN_RUN ({FLATLINE_MIN_RUN}), so the ceiling overrides the "
            f"floor and every channel is accused after {FLATLINE_ABSOLUTE_RUN} "
            f"repeats regardless of its own behaviour."
        ))

    for name, value in (("AI_FLATLINE_MAX_DEVICES", FLATLINE_MAX_DEVICES),
                        ("AI_FORECAST_CACHE_MAX_DEVICES", FORECAST_CACHE_MAX_DEVICES),
                        ("AI_MAX_CONCURRENT_INFERENCE", MAX_CONCURRENT_INFERENCE)):
        if value < 1:
            findings.append((
                "warning",
                f"{name} is {value}; it is clamped to 1 at use, because a bound of "
                f"zero cannot hold the item being added."
            ))

    if MIN_TRAINING_SAMPLES < 2:
        findings.append((
            "warning",
            f"AI_MIN_TRAINING_SAMPLES is {MIN_TRAINING_SAMPLES}. Below two samples the "
            f"scaler has no variance to fit and the result is a model that reports "
            f"itself trained and detects nothing."
        ))

    return findings
