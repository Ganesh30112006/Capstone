#!/usr/bin/env bash
#
# Bring the whole platform up locally for a real-time end-to-end run.
#
# Infrastructure (PostgreSQL, RabbitMQ, LocalStack SQS) runs in Docker via
# docker-compose.yml; the services themselves run natively from the jars in
# */target, which avoids nine multi-stage image builds while running exactly the
# same code.
#
# LocalStack's SQS queue is the seam where real hardware will attach. AWS IoT
# Core's rule action writes telemetry into that same queue, so nothing
# downstream of it can tell a simulated station from a real one -- which is the
# point: the pipeline being exercised here is the pipeline that will carry the
# ESP32's readings.
#
#   usage: scripts/run-local.sh [start|stop|status|logs <service>]

set -uo pipefail

BACKEND="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$BACKEND/logs"
PIDS="$LOGS/pids"

# Populated by load_env, because every port is overridable and the gateway's
# upstream URLs have to be built from the same values the services bind to.
# A port pinned in one place and assumed in another is how a gateway ends up
# proxying to whatever else happens to be listening.
JAVA_SERVICES=()

load_env() {
  set -a
  # shellcheck disable=SC1091
  source "$BACKEND/.env"
  set +a
  # The compose file addresses infrastructure by container name; running the
  # services natively they are all on localhost instead.
  export RABBITMQ_HOST=localhost
  export AWS_SQS_ENDPOINT=http://localhost:4566

  export AUTH_SERVICE_PORT="${AUTH_SERVICE_PORT:-8083}"
  export DEVICE_SERVICE_PORT="${DEVICE_SERVICE_PORT:-8080}"
  export SENSOR_SERVICE_PORT="${SENSOR_SERVICE_PORT:-8081}"
  export ANALYTICS_SERVICE_PORT="${ANALYTICS_SERVICE_PORT:-8084}"
  export ALERT_SERVICE_PORT="${ALERT_SERVICE_PORT:-8085}"
  export IOT_INGESTION_PORT="${IOT_INGESTION_PORT:-8086}"
  export IOT_STREAM_PORT="${IOT_STREAM_PORT:-8087}"
  export GATEWAY_PORT="${GATEWAY_PORT:-8443}"
  export AI_SERVICE_PORT="${AI_SERVICE_PORT:-8000}"

  export AUTH_SERVICE_URL=http://localhost:$AUTH_SERVICE_PORT
  export DEVICE_SERVICE_URL=http://localhost:$DEVICE_SERVICE_PORT
  export SENSOR_SERVICE_URL=http://localhost:$SENSOR_SERVICE_PORT
  export ANALYTICS_SERVICE_URL=http://localhost:$ANALYTICS_SERVICE_PORT
  export ALERT_SERVICE_URL=http://localhost:$ALERT_SERVICE_PORT
  export AI_SERVICE_URL=http://localhost:$AI_SERVICE_PORT
  export IOT_STREAM_SERVICE_URL=http://localhost:$IOT_STREAM_PORT
  export IOT_STREAM_WS_URL=ws://localhost:$IOT_STREAM_PORT

  JAVA_SERVICES=(
    "auth-service:$AUTH_SERVICE_PORT"
    "device-service:$DEVICE_SERVICE_PORT"
    "sensor-service:$SENSOR_SERVICE_PORT"
    "analytics-service:$ANALYTICS_SERVICE_PORT"
    "alert-service:$ALERT_SERVICE_PORT"
    "iot-ingestion-service:$IOT_INGESTION_PORT"
    "iot-stream-service:$IOT_STREAM_PORT"
    "api-gateway:$GATEWAY_PORT"
  )
}

start_java() {
  local name="$1" port="$2"
  local jar
  jar="$(ls "$BACKEND/$name"/target/"$name"-*.jar 2>/dev/null | head -1)"
  if [ -z "$jar" ]; then
    echo "  $name: NO JAR (run: mvn -o package -DskipTests)"
    return 1
  fi
  nohup java -XX:+UseG1GC -Xmx512m -jar "$jar" >"$LOGS/$name.log" 2>&1 &
  echo "$!" >"$PIDS/$name.pid"
  echo "  $name -> :$port (pid $!)"
}

start_ai() {
  cd "$BACKEND/ai-service" || return 1
  nohup python -m uvicorn main:app --host 0.0.0.0 --port "$AI_SERVICE_PORT" --workers 1 \
    >"$LOGS/ai-service.log" 2>&1 &
  echo "$!" >"$PIDS/ai-service.pid"
  echo "  ai-service -> :$AI_SERVICE_PORT (pid $!)"
  cd "$BACKEND" || return 1
}

cmd_start() {
  mkdir -p "$LOGS" "$PIDS"
  load_env

  echo "infrastructure:"
  ( cd "$BACKEND" && docker compose up -d postgres rabbitmq localstack >/dev/null 2>&1 )
  for _ in $(seq 1 45); do
    local health
    health="$(cd "$BACKEND" && docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null)"
    if [ "$(echo "$health" | grep -c healthy)" -ge 3 ]; then break; fi
    sleep 2
  done
  ( cd "$BACKEND" && docker compose ps --format '  {{.Service}}: {{.Status}}' )

  echo "services:"
  for entry in "${JAVA_SERVICES[@]}"; do
    start_java "${entry%%:*}" "${entry##*:}"
  done
  start_ai

  echo
  echo "waiting for health endpoints..."
  cmd_wait
}

# A service is only reported up when its own actuator says so; a listening port
# is not the same claim as a started application context.
cmd_wait() {
  local deadline=$(( $(date +%s) + 180 ))
  local pending=("${JAVA_SERVICES[@]}" "ai-service:$AI_SERVICE_PORT")
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local still=()
    for entry in "${pending[@]}"; do
      local name="${entry%%:*}" port="${entry##*:}" url
      if [ "$name" = "ai-service" ]; then url="http://localhost:$port/health"; else url="http://localhost:$port/actuator/health"; fi
      if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
        echo "  UP    $name"
      else
        still+=("$entry")
      fi
    done
    pending=("${still[@]}")
    [ "${#pending[@]}" -eq 0 ] && { echo "all services up"; return 0; }
    sleep 3
  done
  echo "still down after 180s:"
  for entry in "${pending[@]}"; do echo "  DOWN  ${entry%%:*}  (tail $LOGS/${entry%%:*}.log)"; done
  return 1
}

cmd_status() {
  load_env
  for entry in "${JAVA_SERVICES[@]}" "ai-service:$AI_SERVICE_PORT"; do
    local name="${entry%%:*}" port="${entry##*:}" url
    if [ "$name" = "ai-service" ]; then url="http://localhost:$port/health"; else url="http://localhost:$port/actuator/health"; fi
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then echo "  UP    $name :$port"; else echo "  DOWN  $name :$port"; fi
  done
}

cmd_stop() {
  if [ -d "$PIDS" ]; then
    for f in "$PIDS"/*.pid; do
      [ -e "$f" ] || continue
      local pid; pid="$(cat "$f")"
      kill "$pid" 2>/dev/null && echo "  stopped $(basename "$f" .pid) ($pid)"
      rm -f "$f"
    done
  fi
  echo "infrastructure left running; 'docker compose down' to stop it too"
}

case "${1:-start}" in
  start)  cmd_start ;;
  stop)   cmd_stop ;;
  status) cmd_status ;;
  wait)   load_env; cmd_wait ;;
  logs)   tail -f "$LOGS/${2:-api-gateway}.log" ;;
  *)      echo "usage: $0 [start|stop|status|wait|logs <service>]"; exit 1 ;;
esac
