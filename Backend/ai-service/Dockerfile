# --- build stage ------------------------------------------------------------
# Compilers exist only here. The previous single-stage image installed gcc, g++
# and libc6-dev to build wheels and then shipped them, which leaves a working
# toolchain inside a network-reachable container: anyone who achieves execution
# there can compile whatever they bring. They are not needed at runtime.
FROM python:3.11-slim-bookworm AS build

ENV PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update && \
    apt-get install -y --no-install-recommends gcc g++ libc6-dev && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
# Installed into a virtualenv so the whole dependency tree can be copied to the
# runtime stage as one directory, without the build stage's apt packages.
RUN python -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir -r requirements.txt


# --- runtime stage ----------------------------------------------------------
FROM python:3.11-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/opt/venv/bin:$PATH" \
    AI_OVERALL_THRESHOLD=0.75 \
    DEVICE_MAP=cpu \
    # Keeps torch from spawning a thread per core and then contending with the
    # web server and both consumer threads for them.
    AI_TORCH_THREADS=2 \
    # Home and cache land under /app, which is writable; nothing else needs to be.
    HOME=/app \
    HF_HOME=/app/.cache/huggingface

WORKDIR /app

# curl is the only runtime package, and only because HEALTHCHECK uses it.
RUN adduser --disabled-password --gecos '' --uid 10001 appuser && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /opt/venv /opt/venv

# Application code is owned by root and only readable by the user that runs it.
# The service has no reason to rewrite its own source, and a process that cannot
# modify its code cannot persist a change to it.
COPY --chown=root:root . .

# The two paths the service legitimately writes: the model artifact (retraining)
# and the Hugging Face cache (first Chronos load). Everything else stays
# read-only to appuser, so `--read-only` with tmpfs on /tmp is a supported way
# to run this image.
RUN mkdir -p /app/models/isolation_forest/v2 /app/.cache/huggingface && \
    chown -R appuser:appuser /app/models /app/.cache && \
    chmod -R a-w /app/*.py

USER 10001
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1

# One worker, deliberately. Each additional worker process would start its own
# pair of RabbitMQ consumers, and both would score every reading — duplicating
# every anomaly event the alert service receives. Scale this service by adding
# tasks with distinct queues, not by adding workers to one process.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", \
     "--workers", "1", "--no-server-header", "--proxy-headers"]
