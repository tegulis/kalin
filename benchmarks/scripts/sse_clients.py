#!/usr/bin/env python3
"""Opens N concurrent SSE connections (using several loopback source addresses) and keeps them open."""
import asyncio, sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 10000
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8090
PER_SOURCE = 25000

async def client(i, received, ready):
    src = f"127.0.0.{1 + i // PER_SOURCE}"
    try:
        reader, writer = await asyncio.open_connection("127.0.0.1", PORT, local_addr=(src, 0))
        writer.write(b"GET /events HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n")
        await writer.drain()
        buf = b""
        while b"data: 1" not in buf:
            chunk = await reader.read(4096)
            if not chunk:
                return
            buf += chunk
        ready.append(i)
        while True:
            chunk = await reader.read(4096)
            if not chunk:
                return
            if b"broadcast" in chunk:
                received.append(i)
    except Exception as e:
        print("client error", i, e, file=sys.stderr)

async def main():
    received, ready = [], []
    tasks = []
    for i in range(N):
        tasks.append(asyncio.create_task(client(i, received, ready)))
        if i % 500 == 0:
            await asyncio.sleep(0.01)
    for _ in range(600):
        if len(ready) >= N:
            break
        await asyncio.sleep(0.1)
    print(f"connected={len(ready)}", flush=True)
    reader, writer = await asyncio.open_connection("127.0.0.1", PORT)
    writer.write(b"GET /stats HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
    print((await reader.read(-1)).decode().split("\r\n\r\n", 1)[1], flush=True)
    reader, writer = await asyncio.open_connection("127.0.0.1", PORT)
    writer.write(b"POST /broadcast HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
    print("broadcast sent to", (await reader.read(-1)).decode().split("\r\n\r\n", 1)[1], flush=True)
    for _ in range(300):
        if len(received) >= len(ready):
            break
        await asyncio.sleep(0.1)
    print(f"received={len(received)}", flush=True)
    for t in tasks:
        t.cancel()

asyncio.run(main())
