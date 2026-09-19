# mini-redis

A from-scratch, educational in-memory key-value store in Java, inspired by
Redis. Built incrementally as a backend/systems portfolio project — no
frameworks, just the JDK, Maven, and JUnit 5.

## Current stage: Stage 1 — Project foundation

At this point the project contains only the core in-memory store, with no
networking, expiry, eviction, or persistence yet. Those are built up in
later stages.

Implemented:
- `Store` interface (`com.miniredis.store.Store`) — the KV contract
- `InMemoryStore` (`com.miniredis.store.InMemoryStore`) — a
  `ConcurrentHashMap`-backed implementation of `SET` / `GET` / `DEL` /
  `EXISTS`
- Unit tests for the store

Not yet implemented (by design, coming in later stages):
- TCP server / client protocol
- `EXPIRE` and key expiration
- LRU eviction
- Multiple simultaneous clients
- Persistence / snapshotting
- Docker / CI

## Requirements

- JDK 21+
- Maven 3.9+

## Build and test

```bash
mvn test    # run the unit tests
mvn package # build the project
```

## Project structure

```
src/main/java/com/miniredis/store/   core key-value store
src/test/java/com/miniredis/store/   unit tests
```
