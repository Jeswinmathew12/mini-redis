# mini-redis

A from-scratch, educational in-memory key-value store in Java, inspired by
Redis. Built incrementally as a backend/systems portfolio project — no
frameworks, just the JDK, Maven, and JUnit 5.

Clients talk to it over plain TCP using a simple line-based text protocol,
so you can drive it with `telnet` or `netcat`.

## Supported commands

| Command | Reply | Notes |
|---|---|---|
| `SET key value` | `OK` | Creates or overwrites. Clears any existing TTL. Values may contain spaces. |
| `GET key` | value, or `(nil)` | `(nil)` if absent or expired. |
| `DEL key` | `OK` / `(nil)` | `(nil)` if the key was absent or already expired. |
| `EXPIRE key seconds` | `OK` / `(nil)` | `(nil)` if the key is absent. A non-positive TTL deletes immediately. |
| `DBSIZE` | key count | Includes keys that have expired but not yet been reclaimed. |
| `INFO` | one line | `keys=… max_keys=… evictions=… expirations=…`. `max_keys=0` means unlimited. |

Command names are case-insensitive; keys and values are not. A malformed
command returns `ERR <reason>` and leaves the connection open. Lines end at
`\n` (a preceding `\r` is dropped); a lone `\r` is not a line break.

```
SET session123 Jeswin
OK
EXPIRE session123 10
OK
GET session123
Jeswin
... 10 seconds later ...
GET session123
(nil)
```

## Architecture

```
TCP client
    │
    ▼
Server ──────────── accept loop, one thread per client
    │
    ▼
ClientHandler ───── reads a line, writes a reply, until disconnect
    │
    ▼
CommandParser ───── text ──► validated Command (no sockets)
    │
    ▼
CommandExecutor ─── Command ──► reply string (no sockets)
    │
    ▼
Store ───────────── HashMap + LRU list + expiry index, one lock
    ▲
    │
ExpirationSweeper ─ daemon thread reclaiming expired keys
```

Networking, protocol, and storage are separate packages, so parsing and
execution are unit-testable without opening a port.

### Design notes

- **One lock guards the whole store.** The key map, the recency list and the
  expiry index are plain collections touched only under a single
  `ReentrantLock`, so every operation, including the background sweeper, is
  atomic with respect to every other. "Is it expired? then remove it" and
  "find the least recently used key, then evict it" can never race with a
  concurrent write. The cost is that reads don't run in parallel, which is
  unavoidable here anyway: a read must reorder the recency list.
- **Exact LRU in O(1).** A doubly linked list with sentinel head and tail
  orders keys by recency. Each node carries its own links, so touching or
  removing a known node never searches. A `GET` hit or a `SET` moves the key
  to the front; `DEL`, `EXISTS`, `EXPIRE` and misses do not. `Node` is the
  only thing in the map, the list and the expiry index, and a single
  `remove()` takes it out of all three, so they cannot drift apart.
- **Eviction prefers dead keys.** Capacity is a key count. Inserting a new
  key into a full store first reclaims the expired entry with the earliest
  deadline; only if none has expired does it evict the least recently used
  key. Overwriting an existing key never evicts. Evictions and expirations
  are counted separately.
- **Value and TTL live in one node**, so `SET` replaces both under the lock
  and no reader can see a value with the wrong TTL.
- **Expired keys are reclaimed two ways:** lazily when an operation touches
  one, and actively by a background sweeper. Lazy alone would leak keys that
  nobody ever reads again.
- **The sweeper reads a deadline-ordered index.** Keys with a TTL are also
  listed in a `TreeSet` ordered by deadline (one entry per key, removed
  eagerly when the TTL changes). Each pass pops at most 20 expired entries
  from the front, so its cost is proportional to what expired and it never
  scans live keys, however many there are. If a pass uses at least 25% of
  its budget it runs again immediately; otherwise it sleeps, so an idle
  server uses effectively no CPU.
- **Input and connections are bounded.** A command line may be at most 1 MiB;
  a longer line gets `ERR line too long` and the connection is closed, so one
  client cannot exhaust the server's memory. At most 1000 clients connect at
  once; extras get `ERR max clients reached`. TCP keepalive lets the OS
  reclaim connections whose peer vanished.
- **Shutdown is orderly.** `stop()` closes the listening socket first, then
  every client, then waits for the accept loop and handler threads. A client
  accepted in the instant between those steps is closed rather than leaked.
  The JVM shutdown hook calls it, so `docker stop` (SIGTERM) shuts down
  cleanly.
- **Failures are contained and logged.** An unexpected error while running a
  command replies `ERR internal error` and keeps the connection; the sweeper
  logs a failure once and keeps retrying; nothing is silently swallowed.
- **The sweeper cannot starve clients.** A mass expiry is reclaimed in short
  bursts (at most 25 ms back to back), then the thread sleeps.

## Requirements

- JDK 21+
- Maven 3.9+

## Build and run

```bash
mvn test                                        # run the test suite
mvn package                                     # test, then build target/mini-redis.jar
java -jar target/mini-redis.jar                 # listen on 127.0.0.1:6380
java -jar target/mini-redis.jar 7000            # or a port of your choice
java -jar target/mini-redis.jar 6380 1000       # port, then max keys (LRU)
java -jar target/mini-redis.jar 6380 1000 0.0.0.0   # port, max keys, bind address
```

Arguments are `[port [maxKeys [bindAddress]]]`:

- **maxKeys** - with a limit, inserting a new key into a full store evicts
  the least recently used key (expired keys are reclaimed first). `0`, or
  leaving it out, means no limit. Use `INFO` to watch evictions and expirations.
- **bindAddress** - defaults to `127.0.0.1`, so the server is reachable only
  from this machine (it has no authentication). Pass `0.0.0.0` to accept
  remote clients. **Inside a container you must pass `0.0.0.0`**, otherwise a
  published port cannot reach the server.

```
$ java -jar target/mini-redis.jar 6380 3
SET a 1 / SET b 2 / SET c 3      (store is full)
GET a                            (a is now the most recently used)
SET d 4                          (evicts b, the least recently used)
GET b
(nil)
INFO
keys=3 max_keys=3 evictions=1 expirations=0
```

Then connect:

```bash
nc localhost 6380       # or: telnet localhost 6380
```

On Windows, `nc` is not built in. Use `ncat` (`winget install Insecure.Nmap`),
or drive it from PowerShell:

```powershell
$c = New-Object Net.Sockets.TcpClient("localhost",6380); $s = $c.GetStream()
$r = New-Object IO.StreamReader $s; $w = New-Object IO.StreamWriter $s; $w.AutoFlush = $true
function cmd($x) { $w.Write("$x`r`n"); $r.ReadLine() }
cmd "SET name Jeswin"
cmd "GET name"
```

## Docker

```bash
docker build -t mini-redis .
docker run --rm -p 127.0.0.1:6380:6380 mini-redis                       # no key limit
docker run --rm -p 127.0.0.1:6380:6380 mini-redis 6380 100000 0.0.0.0   # LRU, 100k keys
docker stop <container>                                                 # SIGTERM: clean shutdown
```

The image is a two-stage build (Maven + JDK to compile, JRE only to run),
runs as a non-root user, and has a health check that sends `DBSIZE` over TCP.
Container arguments are the same `[port [maxKeys [bindAddress]]]` as above;
the default is `6380 0 0.0.0.0`. The bind address must stay `0.0.0.0` inside a
container. Because the server has no authentication, publish the port on the
host's loopback (`-p 127.0.0.1:6380:6380`) unless you mean to expose it.
Logs go to stderr, so `docker logs` shows them. The health check assumes port
6380; if you change the port, change it in the `Dockerfile` too.

## Tests

```bash
mvn test
```

Coverage includes protocol parsing, command execution, real-socket
integration tests, and concurrency tests that race expiry against
concurrent writes. LRU is tested three ways: one test per contract rule, a
seeded random-operation test that checks the store against a naive reference
model after every step (results, exact recency order, counters), and a
multi-threaded stress test that audits map/list/index consistency while
workers run.

## Project structure

```
src/main/java/com/miniredis/
  Main.java              entry point
  protocol/              command parsing (no sockets)
  server/                TCP server, client handler, executor
  store/                 key-value store and expiration sweeper
src/test/java/com/miniredis/
  protocol/ server/ store/
```

## Not yet implemented

- GitHub Actions CI

Persistence is deliberately out of scope: this is a cache-style store, like
Redis with no snapshotting configured.
