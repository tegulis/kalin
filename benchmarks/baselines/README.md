# Benchmark baselines

Separate Gradle build (not part of the Kalin build) with the servers Kalin is compared against:

- `netty`: the TechEmpower Framework Benchmarks netty entry (fetch it with `./fetch-netty.sh`)
- `jetty`: a plain Jetty 12 core handler
- `javalin`: Javalin 7 with virtual threads

```sh
./fetch-netty.sh
../../gradlew -p . installDist
```

Then run each `*/build/install/*/bin/*` start script and point `../scripts/bench.sh` at port 8080.
