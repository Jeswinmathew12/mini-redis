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
- **Input is bounded.** A command line may be at most 1 MiB (configurable on
  `Server`). A longer line gets `ERR line too long` and the connection is
  closed, so one client cannot exhaust the server's memory.

## Requirements

- JDK 21+
- Maven 3.9+

## Build and run

```bash
mvn test                                        # run the test suite
mvn package -DskipTests                         # build
java -cp target/classes com.miniredis.Main      # listen on 6380
java -cp target/classes com.miniredis.Main 7000 # or a port of your choice
java -cp target/classes com.miniredis.Main 6380 1000  # port, then max keys (LRU)
```

With a key limit, inserting a new key into a full store evicts the least
recently used key (expired keys are reclaimed first). A limit of `0`, or
leaving it out, means no limit. Use `INFO` to watch evictions and expirations.

```
$ java -cp target/classes com.miniredis.Main 6380 3
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

- Persistence / snapshotting
- Docker
- GitHub Actions CI
