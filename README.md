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

Command names are case-insensitive; keys and values are not. A malformed
command returns `ERR <reason>` and leaves the connection open.

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
Store ───────────── ConcurrentHashMap of Entry(value, expiresAt)
    ▲
    │
ExpirationSweeper ─ daemon thread reclaiming expired keys
```

Networking, protocol, and storage are separate packages, so parsing and
execution are unit-testable without opening a port.

### Design notes

- **Value and TTL live in one immutable `Entry`.** Holding them in two
  parallel maps would make `SET` a two-step update that a reader could
  observe half-finished.
- **Expired keys are reclaimed two ways:** lazily when an operation touches
  one, and actively by a background sweeper. Lazy alone would leak keys that
  nobody ever reads again.
- **The sweeper is deliberately cheap.** Each pass examines at most 20
  entries and resumes where the last pass stopped. If a pass finds ≥25%
  expired it runs again immediately; otherwise it sleeps. An idle server
  uses effectively no CPU.
- **Reclamation uses compare-and-remove** (`remove(key, entry)`), never a
  bare `remove(key)`. Otherwise a thread that observed an expired entry can
  delete a value another thread wrote a moment later.

## Requirements

- JDK 21+
- Maven 3.9+

## Build and run

```bash
mvn test                                        # run the test suite
mvn package -DskipTests                         # build
java -cp target/classes com.miniredis.Main      # listen on 6380
java -cp target/classes com.miniredis.Main 7000 # or a port of your choice
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
concurrent writes to catch lost-update bugs.

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

- LRU eviction
- Persistence / snapshotting
- Docker
- GitHub Actions CI
