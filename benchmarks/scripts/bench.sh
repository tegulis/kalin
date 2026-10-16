#!/usr/bin/env bash
# Runs wrk against a server already listening on $PORT.
# Usage: bench.sh <label> [port]
set -euo pipefail
WRK=${WRK:-wrk}
PORT=${2:-8080}
LABEL=$1
DIR=$(cd "$(dirname "$0")" && pwd)
THREADS=${THREADS:-8}
DURATION=${DURATION:-10s}
# warm up
$WRK -t4 -c64 -d5s "http://127.0.0.1:$PORT/plaintext" > /dev/null
$WRK -t4 -c64 -d3s "http://127.0.0.1:$PORT/json" > /dev/null
pt=$($WRK -t$THREADS -c256 -d$DURATION --latency -s "$DIR/pipeline.lua" "http://127.0.0.1:$PORT/plaintext" -- 16)
js=$($WRK -t$THREADS -c256 -d$DURATION --latency "http://127.0.0.1:$PORT/json")
pt_rps=$(echo "$pt" | awk '/Requests\/sec/ {print $2}')
js_rps=$(echo "$js" | awk '/Requests\/sec/ {print $2}')
pt_p99=$(echo "$pt" | awk '/ 99%/ {print $2}')
js_p99=$(echo "$js" | awk '/ 99%/ {print $2}')
pt_err=$(echo "$pt" | grep -E 'Non-2xx|Socket errors' || true)
js_err=$(echo "$js" | grep -E 'Non-2xx|Socket errors' || true)
printf "%-28s plaintext(pipelined16): %12s req/s p99 %8s | json: %12s req/s p99 %8s %s %s\n" "$LABEL" "$pt_rps" "$pt_p99" "$js_rps" "$js_p99" "$pt_err" "$js_err"
