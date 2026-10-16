#!/usr/bin/env bash
# Runs the benchmark against Kalin (all engines) and the baselines on one machine.
# Server on CPUs $SERVER_CPUS, wrk on $CLIENT_CPUS. Requires wrk (https://github.com/wg/wrk) and taskset.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SERVER_CPUS=${SERVER_CPUS:-0-11}
CLIENT_CPUS=${CLIENT_CPUS:-12-31}
LOOPS=${LOOPS:-12}
WRK=${WRK:-wrk}
B=$ROOT/benchmarks/baselines
BENCH=$ROOT/benchmarks/scripts/bench.sh
run() {
  label=$1; shift
  taskset -c "$SERVER_CPUS" "$@" > /dev/null 2>&1 &
  pid=$!
  sleep 3
  WRK="taskset -c $CLIENT_CPUS $WRK" THREADS=${THREADS:-20} DURATION=${DURATION:-10s} "$BENCH" "$label" 8080
  kill $pid; wait $pid 2>/dev/null || true; sleep 1
}
export JAVA_OPTS="-XX:+UseParallelGC -Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --add-opens=java.base/java.lang=ALL-UNNAMED -Dio.netty.noUnsafe=false"
[ -x "$B/netty/build/install/netty/bin/netty" ] && run netty "$B/netty/build/install/netty/bin/netty"
[ -x "$B/jetty/build/install/jetty/bin/jetty" ] && run jetty "$B/jetty/build/install/jetty/bin/jetty"
[ -x "$B/javalin/build/install/javalin/bin/javalin" ] && run javalin "$B/javalin/build/install/javalin/bin/javalin"
K=$ROOT/benchmarks/build/install/benchmarks/bin/benchmarks
run kalin-EVENT_LOOP/EPOLL "$K" engine=EVENT_LOOP transport=EPOLL loops=$LOOPS
run kalin-EVENT_LOOP/NIO "$K" engine=EVENT_LOOP transport=NIO loops=$LOOPS
run kalin-LOOM/NIO "$K" engine=LOOM
