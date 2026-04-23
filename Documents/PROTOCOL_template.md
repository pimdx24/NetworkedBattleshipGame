# PROTOCOL.md — Message Protocol

**Name(s):** Maddox Lin
**EID(s):** ml55892

---

For each message type below, fill in all four fields:
- **Direction** — who sends it and who receives it
- **Fields** — every JSON field, its Java type, and whether it can be null/absent
- **When sent** — the exact condition that triggers this message
- **Receiver action** — what the recipient does upon receiving it

Add rows or sections for any message types you defined beyond the ones listed.

---

## ASSIGN

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"ASSIGN"`) | No |
| `playerNumber` | int | No |

**When sent:** Immediately after the WebSocket handshake completes for that player's connection, before any other message. Sent from inside `ClientHandler.run()` by calling `server.sendAssign(playerNum)`.

**Receiver action:** Client stores the assigned player number in both React state (`setPlayerNumber`) and a ref (`playerNumberRef.current`) for use in the WebSocket message handler. Immediately sends a `READY` message in response (with optional `name` field).

---

## WAITING

**Direction:** Server → Client (player 1 only)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"WAITING"`) | No |

**When sent:** Sent to player 1 in the same `sendAssign` call as `ASSIGN`, while player 2 has not yet connected.

**Receiver action:** Client transitions the UI to a "Waiting for opponent…" screen and holds this state until `GAME_START` arrives.

---

## READY

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"READY"`) | No |
| `name` | String | Yes — omitted or `null` if player did not enter a name |

**When sent:** Client sends `READY` immediately upon receiving `ASSIGN`, including the player's display name if one was entered in the lobby.

**Receiver action:** Server stores the player's display name (falling back to `"Player 1"` / `"Player 2"` if absent or blank), increments `readyCount`. When `readyCount` reaches 2, generates random ship placements via `ShipPlacementGenerator` and sends `GAME_START` to both clients.

---

## GAME_START

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"GAME_START"`) | No |
| `myBoard` | int\[\]\[\] | No — 10×10 array; 1 = ship cell, 0 = water |
| `turn` | int | No — always 1 (player 1 goes first) |

**When sent:** Once both clients have sent `READY` (`readyCount == 2`). Each client receives a board reflecting only its own ship layout.

**Receiver action:** Client populates its own board display using `myBoard`, initializes the opponent's board as all-unknown (blue), resets fleet status panels, sets the turn indicator based on whether `turn` matches its player number, and transitions to the game screen.

---

## FIRE

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"FIRE"`) | No |
| `row` | int | No — 0-indexed |
| `col` | int | No — 0-indexed |

**When sent:** Client sends `FIRE` when the active player clicks an untargeted cell on the opponent's board. The UI prevents clicks when it is not the player's turn or when the cell has already been targeted.

**Receiver action:** Server validates the request (correct turn, valid coordinates, untargeted cell). On failure, sends `ERROR` to the sender only. On success, fires the shot against the defender's board, broadcasts `SHOT_RESULT` to both clients, then either broadcasts `GAME_OVER` (if all ships sunk) or `TURN_CHANGE` (on a miss only — a hit keeps the same player's turn).

---

## SHOT_RESULT

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"SHOT_RESULT"`) | No |
| `shooter` | int | No — 1 or 2 |
| `row` | int | No |
| `col` | int | No |
| `hit` | boolean | No |
| `sunkShip` | String | Yes — ship name if a ship was sunk; `null` otherwise |

**When sent:** Immediately after the server processes a valid `FIRE` message. Broadcast to both clients simultaneously before `TURN_CHANGE` or `GAME_OVER`.

**Receiver action:**
- If `shooter == myPlayerNumber`: update the opponent's board cell (red for hit, grey for miss). If `sunkShip` is non-null, mark that ship as sunk in the opponent's fleet status panel.
- If `shooter != myPlayerNumber`: update the client's own board cell. If `sunkShip` is non-null, mark that ship as sunk in the client's own fleet status panel.

---

## TURN_CHANGE

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"TURN_CHANGE"`) | No |
| `turn` | int | No — 1 or 2 |

**When sent:** After a miss is processed. **Not** sent after a hit — a hit keeps the current player's turn.

**Receiver action:** Client updates `myTurn` by comparing `turn` with its stored player number. Enables clicking on the opponent's board if `turn == myPlayerNumber`; disables clicking otherwise. Updates the turn indicator label accordingly.

---

## CHAT (client → server)

**Direction:** Client → Server

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"CHAT"`) | No |
| `text` | String | No |

**When sent:** Whenever the player submits a message via the chat input (Send button or Enter key), at any point during the game regardless of whose turn it is.

**Receiver action:** Server immediately broadcasts `CHAT` (server→client) to both players without acquiring the game-logic lock. The player's display name is looked up from the `names` array and included in the outgoing message.

---

## CHAT (server → client)

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"CHAT"`) | No |
| `from` | String | No — the sender's display name |
| `text` | String | No |

**When sent:** Immediately when the server receives a `CHAT` from either client, before acquiring any synchronization lock. Sent to both clients including the original sender.

**Receiver action:** Client appends a new entry `{ from, text, time }` to the chat log (where `time` is the local timestamp at receipt) and scrolls the chat panel to the bottom.

---

## GAME_OVER

**Direction:** Server → Client (both clients)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"GAME_OVER"`) | No |
| `winner` | int | No — player number of the winner |
| `finalBoard` | int\[\]\[\] | No — defender's board fully revealed; 0=water, 1=unhit ship, 2=hit, 3=miss |

**When sent:** When a `FIRE` results in the defender's entire fleet being sunk (`Fleet.allSunk()` returns `true`). The `finalBoard` is the defender's (losing) board with all ship positions revealed.

**Receiver action:**
- If `winner == myPlayerNumber`: display "You Won!", reveal `finalBoard` as the opponent's board.
- Otherwise: display "You Lost…", replace the client's own board display with `finalBoard` (revealing unhit ship cells the opponent never found).
- Both: transition to the game-over screen showing both boards and a "Play Again" button.

---

## OPPONENT_DISCONNECTED

**Direction:** Server → Client

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"OPPONENT_DISCONNECTED"`) | No |

**When sent:** When one client's socket closes or throws an `IOException` and the game phase is not already `DONE` (`handleDisconnect` is called and the phase guard passes).

**Receiver action:** Client displays an error message ("Your opponent disconnected.") and transitions to the game-over screen. No winner is declared.

---

## ERROR

**Direction:** Server → Client (sender only)

**Fields:**

| Field | Java type | Nullable? |
|---|---|---|
| `type` | String (`"ERROR"`) | No |
| `message` | String | No — human-readable description |

**When sent:** When the server rejects a client request:
- `FIRE` from the wrong player ("It is not your turn.")
- `FIRE` on an already-targeted cell ("Cell already targeted.")
- `FIRE` with out-of-bounds coordinates ("Invalid coordinates.")
- `FIRE` or other message received outside the `PLAYING` phase ("Game is not in progress.")
- Any unknown message type ("Unknown message type: …")
- Third client attempting to connect ("Game is full. Only two players allowed.")

**Receiver action:** Client logs the error to the browser console (`console.warn`). No visual change is made to the game state; the turn and board remain unchanged.

---

## Additional message types (if any)

No additional message types were defined. All field names and types match the protocol table in the README exactly (case-sensitive).