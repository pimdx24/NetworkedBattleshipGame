# Lab 5 Design Writeup — Networked Battleship

**Name(s):** Maddox Lin
**EID(s):** ml55892
**Date submitted:** 4/13/2026

---

## What This Document Is

2–3 pages of honest reflection on the decisions you made and the problems you hit.  Not a summary of the lab spec — we wrote the spec, we know what it says.

Full credit requires:

- Two specific design choices with a stated reason and a named alternative
- A complete message sequence diagram covering one full game
- Three concurrency questions answered with specific references to your code
- Two concrete bugs (symptom → cause → fix → lesson learned)
- A testing section that describes what you actually ran, not what you planned to run

Vague entries ("I synchronized the shared state") earn no credit. Entries like "I made `handleMessage` synchronized because both reader threads call it concurrently and I need turn/readyCount/boards to be updated atomically — a `BlockingQueue` approach would have also worked but requires a fifth thread and complicates the CHAT bypass" earn full credit.

---

## Section 1 — Design Choices (two required, ~100 words each)

### 1.A — `synchronized` on `handleMessage` vs. a dedicated game-logic thread

*What I chose:* I made the `synchronized(this)` block in `GameServer.handleMessage` the sole gate for all game-state mutations. The fields `readyCount`, `turn`, `phase`, `boards`, and `fleets` are only written inside that block.

*What I considered instead:* A dedicated game-logic thread that drains a `LinkedBlockingQueue<Message>`. Both reader threads enqueue messages, and a fifth thread dequeues and processes them sequentially.

*Why:* The `synchronized` approach requires no additional thread and makes the CHAT bypass straightforward: `handleMessage` checks `"CHAT".equals(msg.type)` *before* entering the synchronized block, calls `broadcast()` immediately, and returns. With a BlockingQueue consumer thread, CHAT would still need to jump the queue somehow — either by using a separate chat-only queue (two queues to manage) or by inserting a priority mechanism. The synchronization overhead for this game is negligible; the simpler design wins.

---

### 1.B — CHAT forwarded outside the game-logic lock

*What I chose:* In `GameServer.handleMessage`, the very first statement is `if ("CHAT".equals(msg.type)) { broadcast(chatMsg); return; }`. This check and the two `WriterThread.send()` calls happen with no lock held at all.

*What I considered instead:* Acquiring `synchronized(this)` first and dispatching on `msg.type` inside — treating CHAT exactly like FIRE.

*Why:* `FIRE` processing can hold the lock for a nontrivial amount of work: bounds checking, `fireShot()`, `registerHit()`, win detection, and enqueuing multiple messages. If a CHAT arrived while that synchronized block was running, it would block until the lock was released. Since `WriterThread.send()` is itself thread-safe (`LinkedBlockingQueue.offer` is non-blocking), there is nothing shared that CHAT needs to protect. Putting CHAT outside the lock means the maximum delay for a chat message is the time for two non-blocking queue insertions, regardless of what the game-logic thread is doing.

---

## Section 2 — Message Protocol

### 2.1 — Full message sequence for one complete game

```
[Tab 1]  →  [Server]   TCP connect + WebSocket HTTP upgrade (GET /)
[Server] →  [Tab 1]    HTTP/1.1 101 Switching Protocols
[Server] →  [Tab 1]    ASSIGN { playerNumber: 1 }
[Server] →  [Tab 1]    WAITING

[Tab 2]  →  [Server]   TCP connect + WebSocket HTTP upgrade (GET /)
[Server] →  [Tab 2]    HTTP/1.1 101 Switching Protocols
[Server] →  [Tab 2]    ASSIGN { playerNumber: 2 }

[Tab 1]  →  [Server]   READY { name: "Alice" }
[Tab 2]  →  [Server]   READY { name: "Bob" }
[Server] →  [Tab 1]    GAME_START { myBoard: [[1,1,1,1,1,0,...], ...], turn: 1 }
[Server] →  [Tab 2]    GAME_START { myBoard: [[0,0,1,0,...], ...],     turn: 1 }

Turn 1: Alice fires at B6 (row 1, col 5) miss
[Tab 1]  →  [Server]   FIRE { row: 1, col: 5 }
[Server] →  [Tab 1]    SHOT_RESULT { shooter: 1, row: 1, col: 5, hit: false, sunkShip: null }
[Server] →  [Tab 2]    SHOT_RESULT { shooter: 1, row: 1, col: 5, hit: false, sunkShip: null }
[Server] →  [Tab 1]    TURN_CHANGE { turn: 2 }
[Server] →  [Tab 2]    TURN_CHANGE { turn: 2 }

Turn 2: Bob fires at A1 (row 0, col 0) hit — no TURN_CHANGE; Bob fires again
[Tab 2]  →  [Server]   FIRE { row: 0, col: 0 }
[Server] →  [Tab 1]    SHOT_RESULT { shooter: 2, row: 0, col: 0, hit: true,  sunkShip: null }
[Server] →  [Tab 2]    SHOT_RESULT { shooter: 2, row: 0, col: 0, hit: true,  sunkShip: null }
(no TURN_CHANGE — a hit keeps the same player's turn)

Turn 3: Bob fires at A2 (row 0, col 1) miss; concurrent chat from Alice ---
[Tab 1]  →  [Server]   CHAT { text: "Lucky shot!" }
[Server] →  [Tab 1]    CHAT { from: "Alice", text: "Lucky shot!" }
[Server] →  [Tab 2]    CHAT { from: "Alice", text: "Lucky shot!" }
[Tab 2]  →  [Server]   FIRE { row: 0, col: 1 }
[Server] →  [Tab 1]    SHOT_RESULT { shooter: 2, row: 0, col: 1, hit: false, sunkShip: null }
[Server] →  [Tab 2]    SHOT_RESULT { shooter: 2, row: 0, col: 1, hit: false, sunkShip: null }
[Server] →  [Tab 1]    TURN_CHANGE { turn: 1 }
[Server] →  [Tab 2]    TURN_CHANGE { turn: 1 }

(additional turns omitted; Alice sinks Carrier, Battleship, Cruiser, Submarine) ---

Final turn: Alice fires at I6 (row 8, col 5) hit, sinks Destroyer
[Tab 1]  →  [Server]   FIRE { row: 8, col: 5 }
[Server] →  [Tab 1]    SHOT_RESULT { shooter: 1, row: 8, col: 5, hit: true, sunkShip: "Destroyer" }
[Server] →  [Tab 2]    SHOT_RESULT { shooter: 1, row: 8, col: 5, hit: true, sunkShip: "Destroyer" }
[Server] →  [Tab 1]    GAME_OVER { winner: 1, finalBoard: [[2,2,2,2,2,0,...], ...] }
[Server] →  [Tab 2]    GAME_OVER { winner: 1, finalBoard: [[2,2,2,2,2,0,...], ...] }
```

### 2.2 — Any message types you added or changed

No changes. All field names and types follow the protocol table in the README exactly.

---

## Section 3 — Concurrency

**3.1 — How does your implementation ensure that a CHAT message sent by player 2 is never delayed by a FIRE message being processed for player 1?**

In `GameServer.handleMessage`, the first line is `if ("CHAT".equals(msg.type)) { broadcast(chatMsg); return; }` — this check and the two `writers[i].send()` calls complete without acquiring any lock. The game-logic lock (`synchronized(this)`) is only entered for READY and FIRE. If CHAT were handled inside `synchronized(this)` like FIRE, it would block whenever the other reader thread held the lock during `handleFire` — which performs board updates, fleet checks, win detection, and broadcasts. By dispatching CHAT before acquiring the lock, a CHAT message's only latency is the time for two non-blocking `LinkedBlockingQueue.offer` calls, regardless of what `handleFire` is doing.

---

**3.2 — Two `ClientHandler` threads call `handleMessage` concurrently. What shared state could be corrupted without synchronization, and what is the specific failure mode?**

The most dangerous unsynchronized race involves `turn` and `handleFire`. Without `synchronized(this)`, both reader threads can enter `handleFire` simultaneously. Suppose player 1's thread reads `turn == 1`, passes the `playerNum != turn` check, then is preempted. Player 2's thread also reads `turn == 1`, also passes the check (since turn hasn't advanced yet), and both proceed to fire. Both call `defenderBoard.fireShot()`, potentially on the same cell, causing an `IllegalStateException` from the second call — or on different cells, silently processing two shots in what should have been a single turn. A similar race on `readyCount` could cause `handleReady` to be entered twice by both threads when `readyCount` is 1: thread 1 reads `readyCount == 1`, thread 2 reads `readyCount == 1`, both increment to 2 independently, and `handleReady` generates two separate sets of ship placements — the second set overwrites `boards` and `fleets`, discarding the layout sent to the clients in the first `GAME_START`.

---

**3.3 — How do all five threads exit cleanly when the game ends?**

When `handleFire` calls `defenderFleet.allSunk()` and it returns true, the method sets `phase = PHASE_DONE`, calls `broadcast(gameOverJson(...))` to enqueue `GAME_OVER` in both outboxes, then calls `shutdown()`. `shutdown()` calls `writers[0].shutdown()` and `writers[1].shutdown()`, each of which calls `outbox.offer(POISON_PILL)`. Each `WriterThread` is blocked in `outbox.take()` (or currently writing). It dequeues and writes `GAME_OVER`, then dequeues `POISON_PILL` and breaks its loop. The `finally` block in `WriterThread.run()` calls `socket.close()`. Closing the socket causes the corresponding `ClientHandler`'s blocking `WebSocketUtil.readFrame()` call to return `null` (or throw `IOException`), so the read loop exits and the `finally` block calls `server.handleDisconnect(playerNum)`. Since `phase` is already `PHASE_DONE`, `handleDisconnect` returns immediately without doing anything. Both `ClientHandler` threads then exit, unblocking `ch1.join()` and `ch2.join()` in `BattleshipServer.main`, which prints a shutdown message and exits.

---

## Section 4 — Bugs

### 4.A — ASSIGN sent before WebSocket handshake completes

*Symptom:* The first browser tab to connect immediately disconnected. The server printed "Player 1 connecting…" but the tab's console showed `WebSocket connection to 'ws://localhost:8080' failed` and the network tab showed the upgrade request receiving a garbled, non-101 response.

*Cause:* I initially had `sendAssign` queued at the top of `BattleshipServer.main` right after `serverSocket.accept()`, before the `ClientHandler` was started. Because `WriterThread` was already running and the outbox was non-empty, it called `WebSocketUtil.writeFrame` on the socket's `OutputStream` before `ClientHandler.run()` had called `WebSocketUtil.handshake()`. The browser received a WebSocket frame header (`0x81`) where it expected the HTTP `101 Switching Protocols` response, failed the upgrade, and closed the connection.

*Fix:* Moved `sendAssign` to the first line inside `ClientHandler.run()` after `WebSocketUtil.handshake()` returns, so the HTTP upgrade is always fully written before any WebSocket frames are sent.

*Lesson:* The `OutputStream` for a socket is shared between the handshake writer and `WriterThread`; any write before the 101 response corrupts the upgrade and there is no browser-side error message that points directly to the cause.

---

### 4.B — React stale closure caused SHOT_RESULT to update the wrong board

*Symptom:* During a live game, all shot results — whether I fired them or the opponent did — appeared on *my* board instead of the opponent's. The opponent's board remained all blue.

*Cause:* Inside `connect()`, `ws.onmessage` was assigned as a closure. The SHOT_RESULT handler compared `shooter === playerNumber`, reading the `playerNumber` React state variable directly. Because `ws.onmessage` is set up once and never re-assigned, it captures the value of `playerNumber` at closure-creation time, which is `null` (the initial state). The comparison `1 === null` and `2 === null` both evaluate to `false`, so `iAmShooter` was always `false`, and every shot was applied to `myBoard`.

*Fix:* Replaced `playerNumber` with `playerNumberRef.current` in the SHOT_RESULT handler (and in the TURN_CHANGE and GAME_OVER handlers). The ref is updated synchronously in `useEffect` whenever `playerNumber` state changes, so `playerNumberRef.current` always holds the current value regardless of when the closure was created.

*Lesson:* React state is not safe to read inside long-lived callbacks like WebSocket event handlers; use a `useRef` as a stable container for any value the handler needs to observe after render.

---

## Section 5 — Testing

**5.1 — How did you verify the server before building the frontend?**

I used `wscat` (`npm install -g wscat`) in two separate terminals. In terminal A I ran `wscat -c ws://localhost:8080` and in terminal B the same. Terminal A immediately received `{"type":"ASSIGN","playerNumber":1}` and `{"type":"WAITING"}`. Terminal B received `{"type":"ASSIGN","playerNumber":2}`. I then typed `{"type":"READY","name":"Alice"}` in A and `{"type":"READY","name":"Bob"}` in B. Both terminals received `GAME_START` with a `myBoard` field containing a 10×10 array. I inspected the array manually to confirm exactly 17 cells were marked `1` (5+4+3+3+2 = 17 ship cells). I then fired several shots from A and verified that both terminals received `SHOT_RESULT` with matching `shooter`, `row`, `col`, and `hit` fields, followed by `TURN_CHANGE`.

---

**5.2 — How did you verify turn enforcement?**

After `GAME_START` with `turn: 1`, I sent `{"type":"FIRE","row":0,"col":0}` from terminal B (player 2) while it was player 1's turn. The server returned `{"type":"ERROR","message":"It is not your turn."}` to terminal B only — terminal A received nothing. I confirmed the turn did not advance by firing from terminal A and receiving a valid `SHOT_RESULT` broadcast to both. I also sent a duplicate fire to the same already-targeted cell and confirmed the server returned `{"type":"ERROR","message":"Cell already targeted."}` without advancing the turn.

---

**5.3 — How did you verify that CHAT is not blocked by concurrent FIRE processing?**

I temporarily inserted `Thread.sleep(300)` at the top of `handleFire` (inside the `synchronized` block) to simulate slow game-logic processing. I then had terminal A fire a shot and, within the 300 ms window, sent a chat message from terminal B: `{"type":"CHAT","text":"hello"}`. The chat appeared in both terminals immediately — well before the `SHOT_RESULT` arrived — confirming that CHAT bypasses the lock. I removed the sleep before final submission.

---

**5.4 — How did you test disconnect handling?**

I connected both players and let the game reach the PLAYING phase. I then closed terminal A's `wscat` connection (Ctrl+C) mid-game. Terminal B immediately received `{"type":"OPPONENT_DISCONNECTED"}`. I also tested closing *before* READY: I connected terminal A, waited for `ASSIGN`, then closed it. In that case there was no surviving player to notify, and the server shut down cleanly (the main thread's `ch1.join()` returned). Finally, I tested that after a normal `GAME_OVER`, neither terminal received a spurious `OPPONENT_DISCONNECTED` — the `phase == PHASE_DONE` guard in `handleDisconnect` prevented the double-send.

---

**5.5 — Browser and OS**

Google Chrome 124 on Windows 11. Two separate tabs at `http://localhost:5173`, each establishing its own WebSocket connection to the Java server.

---

## Section 6 — Reflection

The hardest ordering constraint to get right was the ASSIGN timing relative to the WebSocket handshake. The symptom (instant browser disconnect) gave no hint that the cause was a frame written before the 101 response; I only figured it out by adding a log line before and after `WebSocketUtil.handshake()` and noticing that the `WriterThread` was writing before the log line for "handshake complete" ever appeared.

This lab drove home the difference between correctness and performance in concurrent systems. The `synchronized` approach on `handleMessage` is correct but theoretically slower than a lock-free design — yet for a two-player game, the contention is so low that correctness is worth far more than the negligible throughput difference. The CHAT-outside-the-lock decision was the one case where correctness and latency pulled in the same direction: handling it outside the lock is both simpler and faster.

If starting from scratch, I would write `Message.parse` and its JSON builders with a small round-trip test (`main` that parses then re-serializes each type) before writing any server code. Having a verified message layer makes every subsequent debugging session much less ambiguous.

---

## Section 7 — Time Log

| Date    | Hours | What you worked on |
|---------|-------|--------------------|
| Apr 6   | 2     | Read spec; finalized message protocol on paper; drafted PROTOCOL.md |
| Apr 7   | 4     | Implemented `Message.java` (parse + all JSON builders); wrote standalone round-trip test |
| Apr 8   | 6     | `BattleshipServer`, `ClientHandler`, `WriterThread`; verified WebSocket handshake, ASSIGN with wscat |
| Apr 10  | 4     | `GameServer` READY/GAMESTART path; tested two-client connect and ship layout with wscat |
| Apr 11  | 6     | `GameServer` FIRE/SHOTRESULT/TURNCHANGE/GAMEOVER and disconnect handling; all wscat scenarios passing |
| Apr 12   | 4     | React frontend — `App.jsx` WebSocket setup and message dispatch; `Board.jsx` grid rendering |
| Apr 13   | 4     | `Chat.jsx`, `FleetStatus.jsx`, game-over screen; end-to-end browser test; fixed stale closure bug |

**Total hours:** 23

**Approximate split between backend and frontend:** 65% / 35%
