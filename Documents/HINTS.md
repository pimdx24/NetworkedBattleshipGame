# HINTS.md — Lab 5: Networked Battleship

Hints are organized by implementation phase.  Read only as far as you need — the earlier hints are more general; the later ones are more specific. None of these hints give away code directly.

---

## Phase 1 — Getting a single connection working

**H1.1 — The handshake and the ASSIGN message must not race on the OutputStream.**

`ClientHandler` and `WriterThread` share the same `OutputStream`. `ClientHandler` writes the HTTP 101 response via `handshake()`; `WriterThread` writes WebSocket frames.  If `WriterThread` writes a frame *before* `handshake()` has finished writing the 101 response, the bytes interleave and the browser sees a corrupt upgrade — it closes the connection immediately with no useful error.

The symptom: clicking Connect succeeds briefly, then the browser immediately reports the connection closed.

The fix: **do not enqueue ASSIGN before the handshake completes.**  The right place to enqueue ASSIGN (and WAITING) is inside `ClientHandler.run()`, immediately after `WebSocketUtil.handshake()` returns — at that point the 101 response is fully written and the stream is in WebSocket frame mode.

```
// WRONG — ASSIGN queued before handshake, races WriterThread
outbox.offer(Message.assignJson(playerNum));   // in BattleshipServer.main()
writer.start();
handler.start();   // handler calls handshake() — too late, race already exists

// RIGHT — ASSIGN sent after handshake inside ClientHandler.run()
WebSocketUtil.handshake(in, socket.getOutputStream());
server.sendAssign(playerNum);   // safe: stream is in frame mode now
```

**H1.2 — The handshake consumes from the raw `InputStream`.** `WebSocketUtil.handshake` reads the HTTP headers from the raw `InputStream`. After it returns, continue reading from that same `InputStream` with `WebSocketUtil.readFrame` — do not wrap it in a new `BufferedReader`.

**H1.3 — Start `WriterThread` before `ClientHandler`.** `WriterThread` must already be running (blocking on `outbox.take()`) before `ClientHandler` calls `sendAssign()` after the handshake.  Start both writer threads first, then both handler threads.

**H1.4 — Test with `wscat` before writing any React.** `wscat -c ws://localhost:8080` opens a WebSocket connection from the terminal. You can type JSON messages and read the server's responses directly.  This eliminates the frontend as a variable when debugging the backend.

---

## Phase 2 — Accepting exactly two clients

**H2.1 — `accept()` is called in the main thread.** The main thread calls `accept()` twice — once for player 1, once for player 2 — before starting any other threads.  There is no loop; two calls, two sockets.

**H2.2 — The `ServerSocket` itself can stay open.** You do not need to close the `ServerSocket` after accepting two clients.  Any additional `connect()` attempts from a third client will queue in the OS backlog and never get a `ClientHandler`.  Alternatively you can close it — either approach is correct for this lab.

---

## Phase 3 — `WriterThread` and the outbox

**H3.1 — Reference equality for the sentinel.** `POISON_PILL` is a `static final String` constant.  Use `msg == POISON_PILL` (reference equality), not `msg.equals(POISON_PILL)`.  This is intentional: it prevents a regular message that happens to contain the same text from being misidentified as a shutdown signal.

**H3.2 — `offer` vs `put`.** `offer` is non-blocking and always succeeds on an unbounded `LinkedBlockingQueue`. `put` also works but is unnecessary here.  Use `offer` in `send()` to keep it fast and lock-free.

**H3.3 — Only `WriterThread` ever calls `writeFrame`.** If any other method in your code calls `WebSocketUtil.writeFrame` directly on a client's `OutputStream`, you have a concurrency bug waiting to happen. All outbound writes must go through `WriterThread.send()`.

---

## Phase 4 — `Message` parsing

**H4.1 — You do not need a real JSON parser.** The protocol messages are flat objects with at most five fields, all scalar. A helper method that takes a key name and returns the associated value string from a flat JSON object (using `indexOf` and `substring`) is sufficient. Write it once and call it for each field.

**H4.2 — Distinguish quoted strings from unquoted values.** In JSON, string values are surrounded by double quotes; numbers and booleans are not.  Your extractor should check whether the character immediately after the colon (ignoring whitespace) is a `"` and branch accordingly.

**H4.3 — The `null` JSON literal is unquoted.** `"sunkShip":null` has no quotes around `null`.  If you extract it as a raw string you will get the four-character string `"null"`, not Java `null`.  Either treat the string `"null"` as Java null in your caller, or return Java null when the extracted value equals the literal string `"null"`.

**H4.4 — Build JSON strings by hand.** Your builder methods just need to concatenate string literals and variable values with a `StringBuilder` or string concatenation.  The only tricky part is encoding a Java `String` as a JSON string value: you must escape `\` as `\\` and `"` as `\"`. Write a one-line private helper `escapeJson(String s)` and call it anywhere you embed a user-supplied string in JSON.

---

## Phase 5 — `GameServer` and thread safety

**H5.1 — Only one thing needs to be `synchronized`.** Declare `handleMessage` as `synchronized`.  That single keyword protects all the shared mutable fields (`phase`, `turn`, `readyCount`, `boards`, `fleets`) from concurrent modification, because all mutation goes through that method.

**H5.2 — Release the lock before doing I/O.** `broadcast()` enqueues strings into outboxes — it does not do I/O directly. This means calling `broadcast()` while holding the lock is safe.  The actual `writeFrame` calls happen later in `WriterThread`, outside the lock.

**H5.3 — Chat must bypass the lock.**
If you declare `handleMessage` as `synchronized` and handle `CHAT` inside it, a `CHAT` from player 2 will block until player 1's `FIRE` finishes processing. The fix: check for `CHAT` at the top of `handleMessage` *before* acquiring any lock.  Since `broadcast()` only does queue enqueues (no shared state reads), this is safe.

**H5.4 — `handleDisconnect` can be called twice.** If both sockets close at nearly the same time, both `ClientHandler` threads call `handleDisconnect`.  Guard with a check-and-set on `phase` inside a synchronized block: if `phase == PHASE_DONE` when you enter, return immediately.

**H5.5 — `readyCount` needs the lock too.** Even though `readyCount` is only ever incremented (never decremented), two threads could read it as 1 simultaneously, both think they are the second player ready, and both try to start the game.  The `synchronized` on `handleMessage` already protects this — just make sure `handleReady` is only called from within that lock.

---

## Phase 6 — `Board` JSON serialization

**H6.1 — `shipLayoutToJson` reports the initial layout.** A cell is a "ship cell" if it was ever placed as a ship, even after it has been hit.  In `hiddenBoard`, hit cells are stored as `GameConfiguration.HIT` (not `GameConfiguration.SHIP`), so your condition should be `cell == SHIP || cell == HIT` → emit `1`.

**H6.2 — `fullStateToJson` needs both arrays.** `playerView` tells you whether a cell was targeted (HIT or MISS), but not whether an untargeted cell has a ship.  `hiddenBoard` tells you where ships are.  Consult both to decide the code for each cell.

---

## Phase 7 — React frontend

**H7.1 — `ws.onmessage` is a permanent closure — it never sees updated state.**

This is the single most common React bug in this lab, and the symptom is subtle: the game appears to start, the turn indicator may even say "Your Turn", but clicking cells does nothing.

Here is why.  `ws.onmessage` is assigned once, inside `connect()`:

```js
const connect = () => {
  const ws = new WebSocket(...);
  ws.onmessage = (event) => {
    // playerNumber is captured here, at the moment connect() ran.
    // Its value at that moment is null.
    // It will be null forever inside this function, no matter what
    // React does later with setPlayerNumber().
    setMyTurn(msg.turn === playerNumber);  // always: msg.turn === null → false
  };
};
```

React state updates are asynchronous and do not mutate the captured variable. `playerNumber` inside `onmessage` is permanently `null`.  So `setMyTurn(msg.turn === playerNumber)` always evaluates to `setMyTurn(false)`, and `interactive={myTurn}` is always `false`, so no clicks ever fire.

**The fix: store `playerNumber` in a ref and read the ref inside `onmessage`.**

A ref (`useRef`) is a plain mutable object — not part of React's render cycle. You can write to it and read from it at any time and always get the current value:

```js
const playerNumberRef = useRef(null);

// Keep the ref in sync whenever the state changes:
useEffect(() => { playerNumberRef.current = playerNumber; }, [playerNumber]);

// Inside onmessage, read from the ref — always current:
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === "ASSIGN") {
    playerNumberRef.current = msg.playerNumber;  // update ref immediately
    setPlayerNumber(msg.playerNumber);            // also update state for rendering
  }
  if (msg.type === "GAME_START") {
    setMyTurn(msg.turn === playerNumberRef.current);  // correct
  }
};
```

Update the ref *immediately* on `ASSIGN` (before relying on the `useEffect` to sync it), because `GAME_START` can arrive in the same burst of messages and the `useEffect` may not have run yet.

**H7.2 — Use the functional form of `setState` when updating board state.**
```js
setOpponentBoard(prev => {
  const next = prev.map(r => [...r]);   // copy each row
  next[row][col] = hit ? 2 : 3;
  return next;
});
```
If you mutate `opponentBoard` in place and then call `setOpponentBoard(opponentBoard)`, React sees the same array reference and skips the re-render.  Always produce a new array.

**H7.3 — Send `READY` only after you know your `playerNumber`.** Send `READY` from inside the `"ASSIGN"` case of `ws.onmessage` — not from `ws.onopen`.  By the time `ASSIGN` arrives you have the player number; in `ws.onopen` you do not.

**H7.4 — Board click handler guard.**

Three conditions must all be true for a click to call `onFire`:
  1. `interactive` prop is `true` (parent sets this from `myTurn`)
  2. `cells[r][c] === 0` (cell has not been targeted yet)
  3. The click is on the opponent's board

If clicks do nothing at all, add a `console.log` inside the click handler to confirm it is being called.  If it is called but nothing happens on the server, check the browser's Network tab (WS) to verify the FIRE message is actually sent.

**H7.5 — `useEffect` cleanup prevents duplicate WebSockets on hot reload.**
Return a cleanup function from your `useEffect`:
```js
useEffect(() => {
  return () => { if (wsRef.current) wsRef.current.close(); };
}, []);
```
Without it, Vite's hot-module reload opens a new socket on every save without closing the old one, and the server (which only accepts two connections total) gets confused.

**H7.6 — Check `ws.readyState` before sending.** Use `ws.readyState === WebSocket.OPEN` before calling `ws.send()`.  If the connection is closing or already closed, `send()` throws.

---

## General debugging tips

**Log everything at first.** Add `System.out.println` in `ClientHandler` for every frame received and every message forwarded.  Add `console.log` in `ws.onmessage` for every message received. Remove these before submitting, but they are invaluable while developing.

**Check field name casing.** `"sunkShip"`, not `"sunkship"` or `"SunkShip"`.  The autograder checks exact field names.  Compare your `Message` builder output against the protocol table in the README character by character.

**A `NullPointerException` in `GameServer` usually means boards are null.** This happens when `handleFire` is called before `handleReady` has finished setting up the boards — typically because one player sent `READY` and then immediately `FIRE` without waiting for `GAME_START`.  The autograder doesn't do this, but it is worth guarding against.

**If `wscat` connects but the server hangs, the handshake is failing.** `WebSocketUtil.handshake` is blocking — it reads until it sees a blank line. If the client is not sending a proper HTTP upgrade request, the call never returns. `wscat` sends a correct upgrade request; a raw `telnet` connection does not.
