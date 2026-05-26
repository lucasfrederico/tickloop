# echo-game

A tiny TCP server demonstrating `tickloop`. It broadcasts the current tick
number to every connected client every second, and rebroadcasts anything
a client sends as a chat-style message.

## Why this demo

Three patterns from the library are shown working together on a real
(if small) workload:

- **`TickLoop`** runs the broadcast at a fixed 1 Hz rate.
- **`TickQueue`** carries newly accepted sockets from the acceptor thread
  into the loop thread. The acceptor never touches the connected-clients
  map directly; it goes through the queue.
- **`runOnMain`** is used by per-client reader threads to bring incoming
  messages back to the loop thread, which then rebroadcasts them.

The loop thread is the single owner of the `clients` map. Every mutation
of that state happens on the loop thread, by design — that is the
contract `tickloop` enforces.

## Run

From the repository root:

```bash
mvn -q -pl examples/echo-game compile exec:java \
  -Dexec.mainClass=io.lucasfrederico.tickloop.examples.echo.EchoServer
```

Or build the jar and run directly:

```bash
mvn -q -pl examples/echo-game -am package
java -jar examples/echo-game/target/tickloop-example-echo-game-0.1.0-SNAPSHOT.jar
```

## Connect

In another terminal:

```bash
nc localhost 8765
```

You should see something like:

```
[server] welcome client 1. tick rate: 1 Hz.
[server] tick=12 clients=1
[server] tick=13 clients=1
```

Open another terminal and connect a second client. Both will see each
other's messages and the same tick counter.

## What's intentionally NOT here

This is a demo, not a production server. Missing on purpose:

- Backpressure when a client's `PrintWriter` blocks (slow consumer)
- Authentication or rate limiting
- TLS
- Graceful client kick on send error (only detected on next write)
- Non-blocking I/O (the acceptor is a dedicated thread; reader threads are
  one per client). For thousands of clients, swap in `ServerSocketChannel`
  + a single I/O thread; `tickloop` itself doesn't change.
