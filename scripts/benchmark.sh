#!/usr/bin/env bash
# Starts the packaged server for each scenario, drives it with the load
# generator, and prints one line of results per scenario.
#
#   bash scripts/benchmark.sh                  # full run (about 2 minutes)
#   SECONDS_PER_RUN=5 bash scripts/benchmark.sh
#
# Needs a JDK and Maven. Builds the jar and compiles the load generator if
# they are missing. Client and server run on the same machine, so they compete
# for CPU; treat results as a relative picture, not an absolute ceiling.
set -u
cd "$(dirname "$0")/.."

PORT="${PORT:-6399}"
SECS="${SECONDS_PER_RUN:-10}"
JAR=target/mini-redis.jar

[ -f "$JAR" ] || mvn -B -q package -DskipTests || exit 1
mvn -B -q test-compile || exit 1

SERVER_PID=""
stop_server() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null
    wait "$SERVER_PID" 2>/dev/null
    SERVER_PID=""
  fi
}
trap stop_server EXIT

run() { # maxKeys clients readPercent keys
  local max_keys="$1" clients="$2" read="$3" keys="$4"
  java -jar "$JAR" "$PORT" "$max_keys" 127.0.0.1 >/dev/null 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 50); do
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break
    sleep 0.2
  done
  printf 'maxKeys=%-6s ' "$max_keys"
  java -cp target/test-classes com.miniredis.bench.LoadGenerator \
    --port "$PORT" --clients "$clients" --read-percent "$read" --keys "$keys" --seconds "$SECS"
  stop_server
  sleep 0.5
}

echo "Environment: $(java -version 2>&1 | head -1), $(nproc 2>/dev/null || echo '?') logical CPUs"
echo "Each run: 3 s warmup, ${SECS} s measured, 32-byte values, uniform random keys."
echo
echo "--- Scaling with client count (80% GET / 20% SET, unlimited store) ---"
for c in 1 10 50 200; do run 0 "$c" 80 10000; done
echo
echo "--- Read/write mix at 50 clients (unlimited store) ---"
for r in 100 50; do run 0 50 "$r" 10000; done
echo
echo "--- LRU under pressure: 50 clients, 80/20, 10,000 keys of traffic ---"
run 10000 50 80 10000      # everything fits: no eviction
run 1000  50 80 10000      # 10% of the keys fit: constant eviction
