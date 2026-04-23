# ECE 422C — Lab 5 — Spring 2026

## Networked Battleship

**Due April 8, 2026 @ 11:59pm**

---

## Overview

In Lab 4 you built a complete single-player, text-based Battleship game. Now you will transform it into a two-player networked game with a modern browser-based frontend.

Lab 5 has three integrated components:

1. **Java backend** — a multi-threaded server built on `java.net.ServerSocket` that manages game state, enforces rules, and routes messages between two connected clients.
2. **In-game chat** — players can send chat messages to each other at any time during play, processed concurrently with game events.
3. **React frontend** — a browser UI that communicates with the server over a persistent TCP connection using the browser's native `WebSocket` API, replacing the terminal entirely.

You will gain practical experience with:

- Client–server architecture and raw TCP socket programming
- Java multi-threading (`Thread`, `synchronized`, `BlockingQueue`)
- Concurrent I/O and shared-state coordination
- Newline-delimited JSON as a lightweight message protocol
- React state management and real-time UI updates

---

## Learning Objectives

- Design and implement a multi-threaded Java server using only `java.net.ServerSocket` and `java.io` streams
- Use blocking queues and producer–consumer patterns to decouple reading, processing, and writing
- Apply synchronization primitives to protect shared game state
- Implement a bidirectional JSON message protocol over a raw TCP stream
- Build a real-time React frontend that reflects live server state without polling

---

## Background

### TCP Sockets in Java

A `ServerSocket` listens on a port. Each call to `serverSocket.accept()` blocks until a client connects and returns a `Socket` for that connection. You get an `InputStream` and `OutputStream` from the socket, which you wrap in a `BufferedReader` and `PrintWriter` for line-oriented text I/O:

```java
ServerSocket serverSocket = new ServerSocket(8080);
Socket client = serverSocket.accept();  // blocks until a client connects

BufferedReader in  = new BufferedReader(
    new InputStreamReader(client.getInputStream()));
PrintWriter   out  = new PrintWriter(
    new OutputStreamWriter(client.getOutputStream()), true); // autoFlush=true

String line = in.readLine();  // blocks until client sends a line (or disconnects)
out.println(line);            // sends a line back
```

`readLine()` blocks until a newline arrives, or returns `null` when the connection closes. Because reading blocks, each client must have its own dedicated reader thread so the server can serve both players simultaneously.

### The Browser WebSocket API and Your Server

Browsers cannot open raw TCP sockets — the security model forbids it. They open WebSocket connections instead, which begin with an HTTP upgrade handshake before becoming a persistent, full-duplex channel. Your server must handle this handshake so the browser's `new WebSocket("ws://localhost:8080")` can connect.

The opening handshake is a standard HTTP `GET` with specific headers. The server reads the request, computes a one-time SHA-1 accept key, and sends back a `101 Switching Protocols` response. After that, messages travel in WebSocket frames — a small binary envelope around your text payload. The server must encode outgoing strings as frames and strip the frame header from incoming ones before parsing them as JSON.

All of this is provided in `WebSocketUtil.java`, a helper class we supply. It is pure `java.net`, `java.io`, `java.security`, and `java.util.Base64` — no external libraries. You do not need to understand the frame format in detail. Treat it as a black box with three methods:

```java
WebSocketUtil.handshake(InputStream in, OutputStream out); // call once per connection
String msg = WebSocketUtil.readFrame(InputStream in);       // null on disconnect
WebSocketUtil.writeFrame(OutputStream out, String message); // send one message
```

### Newline-Delimited JSON

All messages are JSON objects, one per WebSocket frame. A message has at minimum a `"type"` field that the receiver switches on:

```
{"type":"FIRE","row":0,"col":4}
{"type":"SHOT_RESULT","shooter":1,"row":0,"col":4,"hit":true,"sunkShip":"Destroyer"}
{"type":"CHAT","from":"Alice","text":"Nice shot!"}
```

You will parse and build JSON using ordinary `String` methods — no JSON library is required or permitted. The message set is small and flat enough that manual parsing is straightforward.

### Concurrency Model

Each client connection has its own reader thread. A shared `GameServer` object holds the authoritative game state. Because two reader threads may call into `GameServer` concurrently (e.g., player 1 fires while player 2 sends a chat message), you must protect shared state with `synchronized` blocks or methods, or by routing all mutations through a `BlockingQueue` consumed by a dedicated game-logic thread. Both approaches are valid; choose one and justify it in your writeup.

Outbound writes have their own hazard: if two threads call `writeFrame` on the same output stream simultaneously, their bytes can interleave mid-frame. The standard fix is a dedicated per-client writer thread fed by a `LinkedBlockingQueue<String>`, so exactly one thread writes to any given socket's output stream.

---

## Game Rules

- Each player's fleet is placed randomly on a **10×10 grid** (rows A–J, columns 1–10). Use `ShipPlacementGenerator` from Lab 4 in random mode.
- Players alternate turns. Player 1 (first to connect) goes first.
- On your turn, fire at a coordinate on your **opponent's** board. The server responds with hit or miss. If a ship is sunk, the server announces which one.
- The first player to sink all opponent ships wins. There is no shot limit.
- Players may send chat messages **at any time**, including when it is not their turn. Chat does not consume a turn.
- If a client disconnects, the remaining player is notified and the game ends.

---

## Architecture

```
Browser (React)               Browser (React)
      |                             |
  WebSocket                     WebSocket
  (TCP + WS framing)            (TCP + WS framing)
      |                             |
  +---+-----------------------------+---+
  |          BattleshipServer           |
  |                                     |
  |  accept loop  →  ClientHandler x2   |
  |  (main thread)    (one Thread each) |
  |                         |           |
  |              LinkedBlockingQueue    |
  |              per client (outbox)    |
  |                         |           |
  |                   WriterThread x2   |
  |                                     |
  |               GameServer            |
  |           Board x2, Fleet x2        |
  +-------------------------------------+
```

You are free to adjust this architecture, but you must justify your decisions in your writeup.

---

## Required Components

### 1. `BattleshipServer.java`

The entry point. Creates a `ServerSocket` on a configurable port (default `8080`). Calls `accept()` in a loop, but stops accepting after exactly two clients — any further connection is closed immediately with an `ERROR` message. Spawns a `ClientHandler` thread for each accepted connection and passes both sockets to `GameServer`.

```java
ServerSocket server = new ServerSocket(port);
Socket c1 = server.accept();  // player 1
Socket c2 = server.accept();  // player 2
// construct GameServer with both sockets, start threads
```

### 2. `ClientHandler.java`

One per client. Runs on its own thread. Calls `WebSocketUtil.handshake(...)` first, then loops on `WebSocketUtil.readFrame(in)`. Parses each frame into a `Message` and calls `gameServer.handleMessage(playerNumber, message)`. When `readFrame` returns `null` (client disconnected), calls `gameServer.handleDisconnect(playerNumber)` and exits.

### 3. `WriterThread.java`

One per client. Runs on its own thread. Blocks on `outbox.take()` from a `LinkedBlockingQueue<String>` and calls `WebSocketUtil.writeFrame(out, json)`. A sentinel value (e.g., an empty string constant) in the queue causes the thread to close the socket and exit.

To send a message to a client from any other thread, call `outbox.put(jsonString)`.

### 4. `GameServer.java`

Holds the authoritative state: both boards, both fleets, whose turn it is, and the game phase (`WAITING`, `PLAYING`, `FINISHED`). Provides `handleMessage(int playerNum, Message msg)` and `handleDisconnect(int playerNum)`.

Key responsibilities:

- Send `ASSIGN` to each client immediately on connect.
- Once both clients send `READY`, place ships via `ShipPlacementGenerator` and send `GAME_START` to each player (with their own board's ship locations).
- On `FIRE`: verify it is the sender's turn (send `ERROR` otherwise), update the defender's board and fleet, broadcast `SHOT_RESULT` to both clients, then broadcast `TURN_CHANGE`.
- Detect win condition and broadcast `GAME_OVER`.
- Forward `CHAT` messages to both clients immediately, without waiting for game-logic synchronization.
- On disconnect, send `OPPONENT_DISCONNECTED` to the other client, then signal all threads to exit.

Because `handleMessage` is called from two different threads, all shared state must be protected. Do not hold a lock while calling `writeFrame` — do all I/O through the `WriterThread` outboxes.

### 5. `Message.java`

A data class for a parsed message:

```java
public class Message {
    public String type;
    public int    row, col;       // used by FIRE
    public String text;           // used by CHAT
    // add fields as needed
}
```

Provide `static Message parse(String json)` and per-type static factory methods that return a JSON string (e.g., `Message.assignJson(int playerNum)`). You may use `String.split`, `indexOf`, `substring`, and `replace` to parse — no JSON library is permitted.

### 6. `WebSocketUtil.java` (provided — do not modify)

Handles the WebSocket HTTP upgrade handshake and WebSocket frame encoding/decoding using only standard Java library classes.

### 7. Message Protocol

Every message is a JSON object with a `"type"` field. Required types:

| Direction | `"type"` | Additional fields |
|---|---|---|
| Client → Server | `"READY"` | — |
| Client → Server | `"FIRE"` | `"row"` (int, 0-indexed), `"col"` (int, 0-indexed) |
| Client → Server | `"CHAT"` | `"text"` (string) |
| Server → Client | `"ASSIGN"` | `"playerNumber"` (1 or 2) |
| Server → Client | `"WAITING"` | — (sent to player 1 while player 2 has not yet connected) |
| Server → Client | `"GAME_START"` | `"myBoard"` (10×10 array, 1 = ship cell, 0 = water), `"turn"` (1 or 2) |
| Server → Client | `"SHOT_RESULT"` | `"shooter"` (int), `"row"`, `"col"`, `"hit"` (bool), `"sunkShip"` (string or `null`) |
| Server → Client | `"TURN_CHANGE"` | `"turn"` (int) |
| Server → Client | `"CHAT"` | `"from"` (string), `"text"` (string) |
| Server → Client | `"GAME_OVER"` | `"winner"` (int), `"finalBoard"` (defender's board as 10×10 array with 0/1/2 for water/ship/hit) |
| Server → Client | `"OPPONENT_DISCONNECTED"` | — |
| Server → Client | `"ERROR"` | `"message"` (string) |

JSON field names must match this table exactly (case-sensitive). Document any additional types you add in `PROTOCOL.md`.

### 8. React Frontend (`frontend/`)

A single-page React application using the browser's native `WebSocket` class — no WebSocket library needed.

**Lobby screen**
- Player name text field and a "Connect" button
- Status text: "Waiting for opponent…" until `GAME_START` arrives

**Game screen — two boards side by side**
- **My Board** — shows your ships (distinct color per ship class), opponent's hits (red), misses (grey). Not clickable.
- **Opponent's Board** — shows your hits (red), misses (grey), unknown cells (blue). Clicking an unknown cell fires a shot — only on your turn.
- Prominent turn indicator: "Your Turn" / "Opponent's Turn"
- Fleet status panel for both players (ship name, size, afloat/sunk)
- Shot history list (most recent first)

**Chat panel** — scrollable log, name + timestamp, text input and Send button, works at all times regardless of turn

**End screen** — winner/loser declaration, final boards displayed

**Responsiveness:** Usable at 1280×800 or wider.

---

## Concurrency Requirements (Graded)

1. **Each client runs on its own dedicated reader thread.** The `accept()` loop must not block on client I/O.
2. **Each client has a dedicated `WriterThread`** fed by a `LinkedBlockingQueue`. No two threads write to the same socket output stream.
3. **Chat must not be delayed by game logic.** Your writeup must explain specifically how your design guarantees this.
4. **No data races on shared game state.** All mutations to board, fleet, and turn variables must be protected. No busy-waiting.
5. **Graceful shutdown.** When the game ends or a client disconnects, all threads exit cleanly — no thread blocks indefinitely after game over.

---

## Provided Files (do not modify)

| File | Purpose |
|---|---|
| `GameConfiguration.java` | Ship names, sizes, board size (same as Lab 4) |
| `ShipPlacementGenerator.java` | Random ship placement (random mode only) |
| `WebSocketUtil.java` | WebSocket handshake and frame codec — pure standard Java |

## Starter Files (fill in the `// TODO` sections)

| File | What you fill in |
|---|---|
| `BattleshipServer.java` | `main`, port binding, accept loop |
| `ClientHandler.java` | Per-client thread: handshake, read loop, dispatch |
| `WriterThread.java` | `BlockingQueue` drain loop, frame writes, shutdown |
| `GameServer.java` | Game state, `handleMessage`, `handleDisconnect`, broadcast helpers |
| `Message.java` | `parse(String)` and JSON-building helpers |
| `frontend/src/App.jsx` | WebSocket setup, message dispatch, top-level state |
| `frontend/src/components/Board.jsx` | Grid rendering and click handling |
| `frontend/src/components/Chat.jsx` | Chat panel |
| `frontend/src/components/FleetStatus.jsx` | Ship status panel |

**Reuse from Lab 4:** Copy and adapt `Board.java` and `Fleet.java`. You will need to add a method to serialize board state to a 2D int array for the `GAME_START` and `GAME_OVER` messages, but the core logic is unchanged.

---

## Project Structure

```
Lab5-NetworkedBattleship/
├── Documents/
│   ├── BUILD.md
│   ├── HINTS.md
│   ├── QUICKSTART.md
│   ├── README.md
│   ├── WRITEUP_template.md 
│   ├── PROTOCOL_template.md 
├── src/
│   ├── BattleshipServer.java       (starter)
│   ├── ClientHandler.java          (starter)
│   ├── WriterThread.java           (starter)
│   ├── GameServer.java             (starter)
│   ├── Message.java                (starter)
│   ├── Board.java                  (adapt from Lab 4)
│   ├── Fleet.java                  (adapt from Lab 4)
│   ├── GameConfiguration.java      (provided — do not modify)
│   ├── ShipPlacementGenerator.java (provided — do not modify)
│   └── WebSocketUtil.java          (provided — do not modify)
└── frontend/
    ├── SETUP.md
    ├── index.html
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── App.jsx                 (starter)
        ├── index.css
        ├── main.jsx
        └── components/
            ├── Board.jsx           (starter)
            ├── Chat.jsx            (starter)
            └── FleetStatus.jsx     (starter)
```

---

## Building and Running

### Backend

```bash
cd src
javac *.java
java BattleshipServer          # default port 8080
java BattleshipServer 9090     # custom port
```

The server prints to stdout when clients connect and when the game starts.

### Frontend

```bash
cd frontend
npm install
npm run dev    # starts Vite dev server at http://localhost:5173
```

Open two browser tabs — each gets its own WebSocket connection to the Java server.

### Running Both Together

```bash
# Terminal 1
cd src && javac *.java && java BattleshipServer

# Terminal 2
cd frontend && npm run dev
```

---

## Testing the Server Without a Browser

Since the server speaks standard TCP with WebSocket framing, you can test it with `wscat` before building the frontend:

```bash
npm install -g wscat

# Terminal 1 — player 1
wscat -c ws://localhost:8080

# Terminal 2 — player 2
wscat -c ws://localhost:8080
```

Type JSON messages directly in each terminal:

```
> {"type":"READY"}
< {"type":"GAME_START","myBoard":[[1,1,1,1,1,0,...],...],"turn":1}
> {"type":"FIRE","row":3,"col":5}
< {"type":"SHOT_RESULT","shooter":1,"row":3,"col":5,"hit":false,"sunkShip":null}
```

Validate the entire server protocol before touching React.

---

## Message Protocol Design

Before writing any code, finalize your message protocol. For each type document:

- **Direction** (client→server, server→client, or both)
- **Fields** (name, Java type, nullable?)
- **When it is sent** (triggering condition)
- **What the receiver does upon receiving it**

Submit this as `PROTOCOL.md` in the root of your submission.

---

## Autograder

The autograder tests the server by connecting two programmatic WebSocket clients. It verifies:

1. `ASSIGN` sent to each client immediately on connect.
2. `GAME_START` sent to both after both send `READY`, with valid `myBoard` data.
3. `FIRE` from the correct player produces `SHOT_RESULT` broadcast to both, then `TURN_CHANGE`.
4. `FIRE` from the wrong player (out of turn) produces `ERROR` and does not advance the turn.
5. Win condition triggers `GAME_OVER` with the correct `winner`.
6. `CHAT` from either client is broadcast to both promptly, even while a `FIRE` is being processed concurrently.

JSON field names must match the protocol table exactly (case-sensitive). The autograder does not test the React frontend. That you will demonstrate to either the instructor or a TA in office hours/recitation.

---

## Suggested Implementation Order

### Week 1 — Backend

1. Accept a single TCP connection, perform the WebSocket handshake, echo messages back. Verify with `wscat`.
2. Accept two connections; log each message with its player number.
3. Implement `Message.parse` for `READY`, `FIRE`, `CHAT`. Write a standalone test (a `main` that round-trips JSON strings).
4. Implement `WriterThread`. Send `ASSIGN` to each client immediately on connect.
5. Implement the `READY` → `GAME_START` handshake. Verify both clients receive correct board data.
6. Implement `FIRE`: validate turn, update boards/fleets, broadcast `SHOT_RESULT` and `TURN_CHANGE`.
7. Implement win detection and `GAME_OVER`.
8. Implement `CHAT` forwarding. Confirm it is not blocked by concurrent game-logic processing.
9. Implement disconnect handling (`OPPONENT_DISCONNECTED`, graceful thread shutdown).

### Week 2 — Frontend

10. Open `new WebSocket("ws://localhost:8080")` in `App.jsx`, handle `ASSIGN`, display player number.
11. Render `Board.jsx` with static data. Confirm the 10×10 grid looks right.
12. Handle `GAME_START`: populate both boards, render your ships.
13. Wire up clicks: clicking Opponent's Board sends `{"type":"FIRE","row":r,"col":c}`.
14. Handle `SHOT_RESULT`: update the correct cell on both boards.
15. Handle `TURN_CHANGE`: update the turn indicator and enable/disable clicking.
16. Add `FleetStatus.jsx`: update on `SHOT_RESULT` events that include a `sunkShip`.
17. Add `Chat.jsx`: send `CHAT` on submit, render incoming `CHAT` messages with timestamp.
18. Handle `GAME_OVER`: show end screen with winner and final boards.
19. Handle `OPPONENT_DISCONNECTED`: display an appropriate message.

---

## Grading

| Component | Points |
|---|---|
| Server: connection handshake (`ASSIGN`, `READY`, `GAME_START`) | 10 |
| Server: `FIRE` processing and `SHOT_RESULT` broadcast | 20 |
| Server: win detection and `GAME_OVER` | 10 |
| Server: `CHAT` forwarding without blocking game logic | 10 |
| Server: graceful disconnect handling | 5 |
| Server: thread safety (no data races, correct synchronization) | 15 |
| Frontend: both boards rendered correctly | 10 |
| Frontend: turn indicator and click gating | 5 |
| Frontend: chat panel functional | 5 |
| Frontend: fleet status panel | 5 |
| Design deliverables (`PROTOCOL.md`, UML, JavaDoc, writeup)     | 5      |
| **Total** | **100** |

---

## Design Deliverables

Submit to Canvas by the due date (in addition to GradeScope):

1. **`PROTOCOL.md`** — Full message protocol documentation.
2. **UML class diagram** — All server-side classes and their relationships. Mark which extend `Thread` or implement `Runnable`.
3. **JavaDoc** — Run `javadoc -d docs src/*.java` and submit the `docs/` folder as a ZIP.
4. **Project writeup** — Follow the template in the file `WRITEUP_template.md`). 

---

## Common Mistakes

- **Holding a lock while doing I/O.** If a `synchronized` block calls `writeFrame`, the thread holds the lock for the entire duration of a potentially slow network write, blocking all other threads that need the same lock. Do all I/O through the `WriterThread` outbox — put a JSON string in the queue and return immediately.
- **Calling `writeFrame` from multiple threads on the same stream.** Concurrent writes interleave mid-frame. Use `WriterThread`.
- **Race on turn state.** Both reader threads call `handleMessage` concurrently. Turn-checking and turn-advancing must be atomic — a single `synchronized` method is the simplest correct solution.
- **Gating `CHAT` on the game-logic lock.** Chat must reach clients immediately. If forwarding chat requires acquiring the same lock as `FIRE` processing, chat can be delayed. Design accordingly.
- **Broadcasting `SHOT_RESULT` to only one client.** Both the attacker (to update their opponent-board view) and the defender (to update their own-board view) need the result.
- **Leaving threads blocked after game over.** A reader thread stuck in `readFrame` will keep the JVM alive indefinitely. Close the socket from `GameServer` when the game ends — this unblocks `readFrame` with a null return or an IOException.
- **React stale closure.** Use the functional `setState` form when deriving new state from old: `setBoard(prev => ...)`.
- **Hot-module reload creating multiple WebSockets.** Return a cleanup function from `useEffect` that calls `ws.close()`.

---

## Checklist

- [ ] Re-read the requirements after finishing to confirm all are met.
- [ ] Server accepts exactly two clients; a third is rejected with `ERROR` and closed.
- [ ] `ASSIGN` is sent to each client immediately on connect.
- [ ] `GAME_START` is sent only after **both** clients send `READY`.
- [ ] `FIRE` from the wrong player returns `ERROR` and does not change turn.
- [ ] `SHOT_RESULT` is broadcast to **both** clients.
- [ ] `GAME_OVER` is sent on win detection with the correct `winner` field.
- [ ] `CHAT` is broadcast to both clients and is not delayed by game logic.
- [ ] Disconnecting one client sends `OPPONENT_DISCONNECTED` to the other.
- [ ] All threads terminate cleanly after the game ends.
- [ ] No `synchronized` block is held while calling `writeFrame`.
- [ ] React frontend: both boards display correctly from `GAME_START`.
- [ ] React frontend: clicking only works on Opponent's Board, only on your turn, only on untargeted cells.
- [ ] React frontend: fleet panels update when a ship is sunk.
- [ ] React frontend: chat sends and receives at all times.
- [ ] React frontend: `GAME_OVER` shows winner and final boards.
- [ ] `PROTOCOL.md` documents every message type completely.
- [ ] UML diagram uploaded to Canvas.
- [ ] JavaDoc ZIP uploaded to Canvas.
- [ ] Project writeup submitted.
- [ ] All `.java` files have the standard course header comment.
- [ ] GradeScope submission includes all `.java` and frontend source files.

---

## Bonus (No Extra Credit — Just Fun)

- **Spectator mode** — a third client connects as a read-only observer receiving both boards and chat in real time.
- **Play Again** — after `GAME_OVER`, both players click "Play Again" and the server resets without disconnecting.
- **Animated shots** — CSS animation on hit (explosion) and miss (splash) when `SHOT_RESULT` arrives.
- **Sound effects** — browser `AudioContext` plays audio on each shot result.

---

## Academic Integrity

You may discuss high-level design with classmates, but all code you submit must be your own. Do not share source files. The protocol design is part of the assignment — do not copy another team's `PROTOCOL.md`. AI code-generation tools are permitted for boilerplate (e.g., CSS layout), but you must understand every line you submit and be prepared to explain it in office hours.

---

## Tips

**Design the protocol first.** Before writing any Java, write every message as a JSON string by hand and agree on every field name. Renaming fields after both sides are partially implemented is painful.

**Test with `wscat` before building the frontend.** `npm install -g wscat`, then open two terminals and connect each as a player. Type raw JSON to drive the entire game flow and validate all server behavior before touching React.

**Parsing JSON by hand is simpler than it looks.** Your messages are flat objects with a handful of scalar fields. A small helper method that extracts a named field from a flat JSON string — using `indexOf`, `substring`, and `trim` — is enough for this protocol. Write it once and reuse it.

**One `LinkedBlockingQueue` per client for outbound messages.** Never call `writeFrame` from more than one thread on the same output stream — the bytes will interleave mid-frame. Put all outgoing messages into the client's outbox queue and let `WriterThread` be the only thread that writes to that stream.

**Close sockets to unblock threads.** A thread stuck in `readFrame` (which is ultimately a `SocketInputStream.read()`) will not wake up on its own when the game ends. Closing the underlying `Socket` from `GameServer` causes `readFrame` to return `null` or throw, letting the reader thread exit its loop and terminate.

**React `useEffect` cleanup.** Open the WebSocket inside a `useEffect(() => { ... }, [])` and return a cleanup function that calls `ws.close()`. Without it, hot-module reload opens a new socket on every save without closing the old one.

**Serialize `myBoard` efficiently.** The `GAME_START` message needs a 10×10 array of 0s and 1s. Build it with a nested `StringBuilder` loop rather than trying to use `Arrays.deepToString` (which adds spaces). A helper method `boardToJson(char[][] board)` that returns a properly formatted JSON array string is worth writing early since you will use it in both `GAME_START` and `GAME_OVER`.
