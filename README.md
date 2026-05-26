# tickloop

> Real-time JVM toolkit. Tick-based scheduler with main-thread safety guarantees,
> async I/O offload, and lock-free inter-thread messaging.

Building blocks for low-latency real-time servers: game backends, trading
engines, live ops platforms — any system that owns local state on one thread
and ticks at a fixed rate.

> Work in progress. v0.1.0 in development.

## Why?

Many real-time servers follow the same pattern that nobody bothers to
extract into a reusable library:

1. One thread owns the state of truth (the "main thread" or "game thread")
2. That thread runs a tight loop at fixed rate (e.g. 20 Hz, every 50 ms)
3. Each tick: drain incoming events, run business logic, emit outgoing events
4. Async I/O (DB, network, files) happens off the main thread, results funnel back
5. Slow ticks are bugs that ripple latency to every connected user

Minecraft does it. Factorio does it. MMORPGs do it. High-frequency trading
engines do it. Live event platforms do it. They each reinvent the same
primitives.

tickloop is a library that gives you those primitives without forcing a
particular game engine or framework. You bring the business logic. tickloop
handles the timing, threading, and queueing.

## Status

Phase 1 of 9. Scaffold + build infrastructure in place. Core API and tests
coming next.

## License

MIT. See [LICENSE](LICENSE).
