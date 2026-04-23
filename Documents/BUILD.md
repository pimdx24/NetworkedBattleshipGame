# BUILD.md — Lab 5: Networked Battleship

Complete reference for compiling, running, and testing every part of the project.

---

## Prerequisites

| Tool | Minimum version | Check with |
|---|---|---|
| Java JDK | 17 | `java -version` |
| Node.js | 20 | `node --version` |
| npm | 9 | `npm --version` |

No Maven, Gradle, or other build tools are required.

---

## Directory layout

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

## 1. Backend

### Compile

From the project root:

```bash
cd src
javac *.java
```

All `.java` files are in a flat directory with no packages, so a single `javac *.java` is sufficient.

If you see errors about class files being out of date, do a clean compile:

```bash
rm -f *.class && javac *.java
```

### Run (default port 8080)

```bash
java BattleshipServer
```

### Run on a custom port

```bash
java BattleshipServer 9090
```

### Expected startup output

```
[Server] Starting on port 8080 ...
[Server] Waiting for two players to connect ...
```

The server blocks at the second `accept()` call until both clients have connected.

### Generate JavaDoc

From the `src/` directory:

```bash
javadoc -d ../docs *.java
```

This writes HTML documentation to `docs/`. Zip it for Canvas submission:

```bash
cd .. && zip -r javadoc.zip docs/
```

---

## 2. Frontend

### Install dependencies (first time only)

```bash
cd frontend
npm install
```

This installs React, React-DOM, Vite, and the React plugin. No other packages are needed.

### Run the development server

```bash
npm run dev
```

Output:

```
  VITE v5.x.x  ready in Nms

  ➜  Local:   http://localhost:5173/
```

Open **two separate browser tabs** at `http://localhost:5173` to simulate two players.

### Build for production (optional)

```bash
npm run build
```

Output goes to `frontend/dist/`. You can serve it with `npm run preview`.

---

## 3. Running both together

Open three terminals:

```bash
# Terminal 1 — compile and start the server
cd src && javac *.java && java BattleshipServer

# Terminal 2 — start the frontend
cd frontend && npm run dev

# Terminal 3 — open wscat for debugging (optional)
wscat -c ws://localhost:8080
```

Open two tabs at `http://localhost:5173`.

---

## 4. Testing the server without a browser

Install `wscat` once:

```bash
npm install -g wscat
```

**Two-terminal game simulation:**

```bash
# Terminal A — player 1
wscat -c ws://localhost:8080
# After connecting, send:
{"type":"READY","name":"Alice"}

# Terminal B — player 2
wscat -c ws://localhost:8080
# After connecting, send:
{"type":"READY","name":"Bob"}
```

After both send `READY` you should see `GAME_START` in both terminals.

**Fire a shot (from player 1's terminal):**
```
{"type":"FIRE","row":3,"col":5}
```

**Send a chat message:**
```
{"type":"CHAT","text":"Good luck!"}
```

**Test out-of-turn rejection (send FIRE from player 2 when it is player 1's turn):**
```
{"type":"FIRE","row":0,"col":0}
```
Expected: `{"type":"ERROR","message":"It is not your turn."}`

---

## 5. Cleaning up

Remove compiled class files:

```bash
cd src && rm -f *.class
```

Remove Node modules (if you need a clean install):

```bash
cd frontend && rm -rf node_modules && npm install
```

---

## 6. GradeScope submission

The autograder compiles your Java files with:

```bash
javac *.java
```

and runs the server with:

```bash
java BattleshipServer 8080
```

**Do not** submit `WebSocketUtil.java`, `GameConfiguration.java`, or `ShipPlacementGenerator.java` — the autograder provides its own copies. Submit all other `.java` files and your entire `frontend/src/` directory.

---

## 7. Common build errors

| Error | Likely cause | Fix |
|---|---|---|
| `Cannot find package 'vite'` or `ERR_MODULE_NOT_FOUND` | `npm install` was never run | `cd frontend && npm install` |
| `cannot find symbol: WebSocketUtil` | Missing provided file | Make sure `WebSocketUtil.java` is in `src/` |
| `error: release version 17 not supported` | Old JDK | Upgrade to JDK 17+ |
| `ENOENT: package.json not found` | Wrong directory | `cd frontend` before running npm |
| `EADDRINUSE: port 8080` | Previous server still running | `kill $(lsof -ti:8080)` (macOS/Linux) |
| `Connection refused` in browser | Server not running or wrong port | Start the Java server first |
| Vite shows blank page with console WS errors | Server not started | Start `java BattleshipServer` first |
