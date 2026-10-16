#!/usr/bin/env bash
# Downloads the TechEmpower Framework Benchmarks "netty" entry (BSD-3-Clause) into netty/src.
set -euo pipefail
cd "$(dirname "$0")/netty"
base=https://raw.githubusercontent.com/TechEmpower/FrameworkBenchmarks/master/frameworks/Java/netty/src/main/java/hello
mkdir -p src/main/java/hello/loom
for f in Constants HelloServerHandler HelloServerInitializer HelloWebServer HttpResponses IoMultiplexer JsonUtils Message; do
  curl -fsSL -o "src/main/java/hello/$f.java" "$base/$f.java"
done
for f in HelloLoomServerInitializer LoomSupport MultithreadVirtualEventExecutorGroup VirtualThreadHelloServerHandler VirtualThreadNettyScheduler; do
  curl -fsSL -o "src/main/java/hello/loom/$f.java" "$base/loom/$f.java"
done
echo "Fetched the TFB netty entry"
