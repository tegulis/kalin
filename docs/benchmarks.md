# Benchmarks

TechEmpower-style tests on one machine: the server and the load generator ([wrk](https://github.com/wg/wrk)) run on
separate CPU sets. Numbers are indicative, not TechEmpower results; rerun them on your hardware.

## Setup

- 32-core x86-64 VM, Linux, JDK 25 (`-XX:+UseParallelGC -Xms512m -Xmx512m`), loopback networking.
- Server pinned to 12 cores (`taskset -c 0-11`), wrk to the other 20 (`taskset -c 12-31`, `-t20 -c256 -d10s`).
- `plaintext`: `GET /plaintext` → `Hello, World!`, pipelined 16 deep (as in TechEmpower).
- `json`: `GET /json` → `{"message":"Hello, World!"}`, one request per round trip.
- Kalin: `benchmarks` module (`Tfb.kt`), endpoints marked `nonBlocking`, 12 event loops, compression off.
- Baselines (`benchmarks/baselines`): the TechEmpower **Netty** entry (Netty 4.2.19, native epoll), a plain
  **Jetty** 12.1 core handler, **Javalin** 7.2.3 with virtual threads.

## Results

| Server | plaintext (req/s) | json (req/s) | json p99 |
|---|---:|---:|---:|
| **Kalin EVENT_LOOP / EPOLL** | **8,210,000** | **1,253,000** | **0.63 ms** |
| Kalin EVENT_LOOP / NIO | 8,080,000 | 1,161,000 | 1.52 ms |
| Kalin LOOM / NIO | 7,504,000 | 949,000 | 0.68 ms |
| Netty (TechEmpower entry) | 2,185,000 | 1,044,000 | 0.83 ms |
| Jetty 12 | 487,000 | 352,000 | 1.35 ms |
| Javalin 7 | 497,000 | 268,000 | 3.06 ms |

At these rates the pipelined plaintext test is limited by wrk, not by Kalin.

### Ordinary (blocking-allowed) handlers

The same `Hello, World!` route without the `nonBlocking` marker, so it runs on a virtual thread:

| Engine | req/s | pipelined 16 (req/s) |
|---|---:|---:|
| LOOM / NIO (default) | 941,000 | 7,370,000 |
| EVENT_LOOP / EPOLL | 920,000 | 945,000 |

`LOOM` runs every request of a connection on that connection's virtual thread, so pipelined requests are processed
back to back. `EVENT_LOOP` hands each request to a new virtual thread and back, which serializes pipelined requests.
That is why `LOOM` is the default; use `EVENT_LOOP` for non-blocking endpoints and for many idle connections (SSE).

### Startup and footprint

`benchmarks` module, `FootprintKt` (one route, after one request and a full GC, `-XX:+UseSerialGC`):

| Engine | `Kalin.start()` | heap used |
|---|---:|---:|
| LOOM / NIO | ~55 ms | 5.5 MB |
| EVENT_LOOP / NIO | ~70 ms | 6.8 MB |
| EVENT_LOOP / EPOLL | ~110 ms | 7.3 MB |

The epoll transport spends about 50 ms generating FFM downcall stubs; `Kalin.create` starts that work in the
background so it overlaps with application startup.

## What made the difference

- Byte-level parsing into reused buffers, pre-encoded status lines and a cached `Date` header.
- Responses of pipelined requests are batched into one write; a whole response (head and body) is one `write`.
- Native epoll (edge-triggered) through FFM `critical` downcalls that read and write Java heap arrays directly, no
  copies through direct buffers. `EPOLLOUT` is only registered while a write is incomplete.
- No clock reads on the hot path: deadlines and the `Date` header use a coarse clock maintained by the timer wheel
  (this matters on machines with a slow clock source such as HPET).
- JSON: kotlinx.serialization with serializers looked up once per type; small values are encoded without the
  library's shared synchronized buffer pool, which otherwise becomes contended across event loops.
- Handlers on virtual threads write their response directly to the socket and hand the connection back to the event
  loop without a wakeup when no further input is waiting.

## Reproduce

```sh
# build wrk (https://github.com/wg/wrk) and put it on PATH or set WRK=/path/to/wrk
./gradlew :benchmarks:installDist
benchmarks/baselines/fetch-netty.sh
./gradlew -p benchmarks/baselines installDist
benchmarks/scripts/compare.sh            # SERVER_CPUS, CLIENT_CPUS, LOOPS, THREADS, DURATION can be overridden
```

Run a single server with `benchmarks/build/install/benchmarks/bin/benchmarks engine=EVENT_LOOP transport=EPOLL
loops=12` and measure it with `benchmarks/scripts/bench.sh <label> 8080`.
