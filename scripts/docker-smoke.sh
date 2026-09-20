#!/usr/bin/env bash
# Starts the image, drives it over TCP, checks its health status and that it
# shuts down promptly, then removes the container. Used by CI; also runnable
# locally:  bash scripts/docker-smoke.sh [image] [host-port]
set -u

IMAGE="${1:-mini-redis}"
HOST_PORT="${2:-16380}"
NAME="mini-redis-smoke-$$"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; }
trap cleanup EXIT

fail() {
  echo "SMOKE TEST FAILED: $1"
  echo "--- container logs ---"
  docker logs "$NAME" 2>&1 | tail -30
  exit 1
}

echo "Starting $IMAGE with a 3-key limit on 127.0.0.1:$HOST_PORT"
docker run -d --name "$NAME" -p "127.0.0.1:$HOST_PORT:6380" "$IMAGE" 6380 3 0.0.0.0 >/dev/null \
  || fail "container did not start"

echo "Waiting for the health check to report healthy (up to 90s)"
status=""
for _ in $(seq 1 45); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "$NAME" 2>/dev/null)"
  [ "$status" = "healthy" ] && break
  [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" = "true" ] || fail "container exited early"
  sleep 2
done
[ "$status" = "healthy" ] || fail "health status is '$status', expected 'healthy'"
echo "ok: healthy"

id_out="$(docker exec "$NAME" id -u)"
[ "$id_out" != "0" ] || fail "container runs as root"
echo "ok: runs as non-root (uid $id_out)"

# Talk to the server through the published port.
exec 3<>"/dev/tcp/127.0.0.1/$HOST_PORT" || fail "cannot connect to the published port"
send() {
  printf '%s\r\n' "$1" >&3
  local reply
  IFS= read -r -t 5 reply <&3 || reply="<no reply>"
  printf '%s' "${reply%$'\r'}"
}
expect() {
  local got
  got="$(send "$1")"
  [ "$got" = "$2" ] || fail "'$1' returned '$got', expected '$2'"
  echo "ok: $1 -> $got"
}

expect "SET a 1" "OK"
expect "SET b 2" "OK"
expect "SET c 3" "OK"
expect "GET a" "1"            # a is now the most recently used
expect "SET d 4" "OK"         # store is full: evicts b, the least recently used
expect "GET b" "(nil)"
expect "GET a" "1"
expect "DBSIZE" "3"
expect "INFO" "keys=3 max_keys=3 evictions=1 expirations=0"
exec 3>&-

echo "Stopping the container (SIGTERM); a clean shutdown takes well under 5s"
start="$(date +%s)"
docker stop "$NAME" >/dev/null || fail "docker stop failed"
elapsed=$(( $(date +%s) - start ))
[ "$elapsed" -le 5 ] || fail "shutdown took ${elapsed}s; the JVM likely did not handle SIGTERM"
echo "ok: stopped in ${elapsed}s"

echo "SMOKE TEST PASSED"
