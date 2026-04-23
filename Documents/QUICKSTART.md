# QUICKSTART.md — Lab 5: Networked Battleship

Get from zero to a running two-player game in under ten minutes.
This guide assumes you have already implemented the required classes.
If you are starting fresh, follow the suggested implementation order in the README first.

---

## Step 1 — Install Node dependencies (REQUIRED — do this first)

> **If you skip this step, `npm run dev` will crash immediately with
> `Cannot find package 'vite'`.  This is the fix.**

```bash
cd frontend
npm install    # downloads Vite, React, and the React plugin into node_modules/
cd ..
```

`npm install` only needs to run once.  Run it again if you delete `node_modules/`.

---

## Step 2 — Compile the Java backend

```bash
cd src
javac *.java
```

Fix any compile errors before continuing.

---

## Step 3 — Start the server

In the `src/` directory:

```bash
java BattleshipServer
```

You should see:

```
[Server] Starting on port 8080 ...
[Server] Waiting for two players to connect ...
```

Leave this terminal open.

---

## Step 4 — Start the frontend

Open a second terminal:

```bash
cd frontend
npm run dev
```

You should see:

```
  ➜  Local:   http://localhost:5173/
```

---

## Step 5 — Open two browser tabs

Navigate to **`http://localhost:5173`** in two separate browser tabs (or two different browsers).

- **Tab 1** — enter a name, click Connect → player 1
- **Tab 2** — enter a name, click Connect → player 2

Both tabs should transition from "Waiting…" to the game board as soon as both players connect.

---

## Step 6 — Play

- Click any cell on the **Opponent's Fleet** board when it is your turn.
- The server validates the shot and updates both boards.
- Type in the chat box and press Enter at any time.

---

## If something does not work

**"Could not connect to server"**
→ Make sure `java BattleshipServer` is running in a terminal.

**Only one board shows up or both boards are blank**
→ Check the browser console (F12) for WebSocket errors.
→ Verify the server printed `[Player 1 connected]` for each tab.

**Clicking cells does nothing**
→ Confirm it is actually your turn (check the turn indicator).
→ Confirm you are clicking on the opponent's board, not your own.

**Server crashes immediately**
→ Check the terminal for a Java exception. Most likely `BattleshipServer` or `GameServer` has an unimplemented method still throwing `UnsupportedOperationException`.

**Port 8080 already in use**
```bash
# macOS / Linux
kill $(lsof -ti:8080)

# Windows PowerShell
Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess
```

---

## Verifying the server without a browser

If the frontend is not yet built, test with `wscat`:

```bash
# Install once
npm install -g wscat

# Terminal A
wscat -c ws://localhost:8080
> {"type":"READY","name":"Alice"}

# Terminal B
wscat -c ws://localhost:8080
> {"type":"READY","name":"Bob"}
```

Both terminals should receive a `GAME_START` message. Fire shots by typing:

```JSON
{"type":"FIRE","row":0,"col":0}
```
