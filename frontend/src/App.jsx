// App.jsx — Root component.
//
// Manages the WebSocket connection, all top-level game state, and the
// high-level screen transitions (lobby → waiting → playing → game over).
//
// Key design note — stale closure problem:
//   ws.onmessage is assigned once inside connect() and never reassigned.
//   If it closed over React state variables directly (e.g. playerNumber),
//   it would always see their initial values, not the current ones.
//
//   The fix: store playerNumber in a ref (playerNumberRef) and update the
//   ref immediately whenever it changes.  ws.onmessage reads the ref, which
//   always holds the current value regardless of when onmessage was created.

import { useState, useEffect, useRef } from "react";
import Board from "./components/Board";
import Chat from "./components/Chat";
import FleetStatus from "./components/FleetStatus";

const SERVER_URL = "ws://localhost:8080";
const BOARD_SIZE = 10;

const emptyBoard = () => Array.from({ length: BOARD_SIZE }, () => Array(BOARD_SIZE).fill(0));

const initialFleet = () => ["Carrier", "Battleship", "Cruiser", "Submarine", "Destroyer"].map((name, i) => ({ name, size: [5, 4, 3, 3, 2][i], sunk: false }));

export default function App() 
{
  const [nameInput, setNameInput] = useState("");
  const [status, setStatus] = useState("idle");
  // status values: "idle" | "connecting" | "waiting" | "playing" | "over"
  const [playerNumber, setPlayerNumber] = useState(null);
  const [errorMsg, setErrorMsg] = useState("");
  const [myBoard, setMyBoard] = useState(emptyBoard());
  const [opponentBoard, setOpponentBoard] = useState(emptyBoard());
  const [myFleet, setMyFleet] = useState(initialFleet());
  const [opponentFleet, setOpponentFleet] = useState(initialFleet());
  const [myTurn, setMyTurn] = useState(false);
  const [winner, setWinner] = useState(null);
  const [chatLog, setChatLog] = useState([]);

  const wsRef = useRef(null);
  const playerNumberRef = useRef(null);  // always-current copy — read this in onmessage

  // Keep the ref in sync with the state value
  useEffect(() => { playerNumberRef.current = playerNumber; }, [playerNumber]);

  // connect() — open WebSocket, wire up all message handlers
  const connect = () =>
  {
    // Close any existing connection before opening a new one.
    if (wsRef.current)
    {
      wsRef.current.close();
      wsRef.current = null;
    }

    setErrorMsg("");
    setStatus("connecting");

    // Capture the name at connect-time to avoid stale closure inside onmessage
    const name = nameInput.trim() || "Anonymous";

    const ws = new WebSocket(SERVER_URL);
    wsRef.current = ws;

    ws.onmessage = (event) => 
    {
      let msg;
      try
      {
        msg = JSON.parse(event.data); 
      }
      catch 
      { 
        console.warn("Bad JSON from server:", event.data); return; 
      }

      switch (msg.type) 
      {
        case "ASSIGN":
          // Update the ref FIRST so subsequent handlers see the correct value.
          playerNumberRef.current = msg.playerNumber;
          setPlayerNumber(msg.playerNumber);
          setStatus("waiting");
          // Send READY immediately — the server is now ready to accept it.
          ws.send(JSON.stringify({ type: "READY", name }));
          break;
        case "WAITING":
          setStatus("waiting");
          break;
        case "GAME_START":
          setMyBoard(msg.myBoard);
          setOpponentBoard(emptyBoard());
          setMyFleet(initialFleet());
          setOpponentFleet(initialFleet());
          setWinner(null);
          setChatLog([]);
          setMyTurn(msg.turn === playerNumberRef.current);
          setStatus("playing");
          break;
        case "SHOT_RESULT":
        {
          const { shooter, row, col, hit, sunkShip } = msg;
          const iAmShooter = shooter === playerNumberRef.current;

          if (iAmShooter) 
          {
            // Update opponent board with shot result
            setOpponentBoard(prev => {
              const next = prev.map(r => [...r]);
              next[row][col] = hit ? 2 : 3;
              return next;
            });
            if (sunkShip) {
              setOpponentFleet(prev =>
                prev.map(s => s.name === sunkShip ? { ...s, sunk: true } : s)
              );
            }
          }
          else 
          {
            // Opponent fired at my board, update my board
            setMyBoard(prev => 
            {
              const next = prev.map(r => [...r]);
              next[row][col] = hit ? 2 : 3;
              return next;
            });
            if (sunkShip) 
            {
              setMyFleet(prev =>
                prev.map(s => s.name === sunkShip ? { ...s, sunk: true } : s)
              );
            }
          }
          break;
        }

        case "TURN_CHANGE":
          setMyTurn(msg.turn === playerNumberRef.current);
          break;
        case "CHAT":
          setChatLog(prev => [...prev, 
          {
            from: msg.from,
            text: msg.text,
            time: new Date().toLocaleTimeString(),
          }]);
          break;
        case "GAME_OVER":
          setWinner(msg.winner);
          setStatus("over");
          // The finalBoard is the defender's (loser's) board fully revealed
          // winner == my number → I won → finalBoard is opponent's board
          // winner != my number → I lost → finalBoard is my board
          if (msg.winner === playerNumberRef.current) 
          {
            setOpponentBoard(msg.finalBoard);
          }
          else
          {
            setMyBoard(msg.finalBoard);
          }
          break;

        case "OPPONENT_DISCONNECTED":
          setErrorMsg("Your opponent disconnected.");
          setStatus("over");
          break;
        case "ERROR":
          setErrorMsg(msg.message);
          break;
        default:
          console.log("[Server] Unknown message type:", msg.type);
      }
    };

    ws.onclose = () => 
    {
      wsRef.current = null;
      setStatus(prev => 
      {
        if (prev !== "over") 
        {
          setErrorMsg(prev => prev || "Connection to server lost.");
          return "idle";
        }
        return "over";
      });
    };

    ws.onerror = () => 
    {
      setErrorMsg("Could not connect. Is the Java server running on port 8080?");
      setStatus("idle");
    };
  };

  // Close WebSocket when the component unmounts (hot-module reload guard).
  useEffect(() => 
  {
    return () => { if (wsRef.current) wsRef.current.close(); };
  }, []);

  // -------------------------------------------------------------------------
  // fire(row, col) — send a FIRE message to the server
  // -------------------------------------------------------------------------
  const fire = (row, col) => 
  {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) 
    {
      wsRef.current.send(JSON.stringify({ type: "FIRE", row, col }));
    }
  };

  // -------------------------------------------------------------------------
  // sendChat(text) — send a CHAT message
  // -------------------------------------------------------------------------
  const sendChat = (text) =>
  {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) 
    {
      wsRef.current.send(JSON.stringify({ type: "CHAT", text }));
    }
  };

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  // Lobby/connection screen
  if (status === "idle" || status === "connecting") 
  {
    return (
      <div className="lobby">
        <h1>⚓ Battleship ⛴</h1>
        <p>Enter your name and connect to the server.</p>
        <input
          type="text"
          placeholder="Your name"
          value={nameInput}
          onChange={e => setNameInput(e.target.value)}
          onKeyDown={e => e.key === "Enter" && connect()}
          maxLength={20}
        />
        <button onClick={connect} disabled={status === "connecting"}>
          {status === "connecting" ? "Connecting…" : "Connect"}
        </button>
        {errorMsg && <p className="error">{errorMsg}</p>}
      </div>
    );
  }

  // Waiting for opponent to connect
  if (status === "waiting") 
  {
    return (
      <div className="lobby">
        <h1>⚓ Battleship</h1>
        <p>You are <strong>Player {playerNumber}</strong>.</p>
        <p>Waiting for your opponent to connect…</p>
      </div>
    );
  }

  // Game over
  if (status === "over") 
  {
    const iWon = winner === playerNumber;
    return (
      <div className="game-over">
        <h1 style={{ color: winner ? (iWon ? "#a5d6a7" : "#ef9a9a") : "#e0e0e0" }}>
          {winner ? (iWon ? "You Won! 🎉" : "You Lost…") : "Game Over"}
        </h1>
        {errorMsg && <p className="error">{errorMsg}</p>}

        <div className="boards-row">
          <div>
            <h3>My Board</h3>
            <Board cells={myBoard} interactive={false} />
          </div>
          <div>
            <h3>Opponent's Board</h3>
            <Board cells={opponentBoard} interactive={false} />
          </div>
        </div>

      </div>
    );
  }

  // Game operational state
  return (
    <div className="game">
      <header>
        <h1>⚓ Battleship</h1>
        <span style={{ color: "#90a4ae", fontSize: 14 }}>Player {playerNumber}</span>
        <div className={`turn-indicator ${myTurn ? "my-turn" : "their-turn"}`}>
          {myTurn ? "Your Turn" : "Opponent's Turn"}
        </div>
      </header>

      <div className="main-layout">
        {/* ── Boards ── */}
        <div className="boards-section">
          <div className="board-wrapper">
            <h3>My Board</h3>
            <Board cells={myBoard} interactive={false} />
            <FleetStatus fleet={myFleet} label="My Ships" />
          </div>

          <div className="board-wrapper">
            <h3>Opponent's Board</h3>
            <Board cells={opponentBoard} interactive={myTurn} onFire={fire} />
            <FleetStatus fleet={opponentFleet} label="Opponent's Ships" />
          </div>
        </div>

        {/* ── Chat ── */}
        <div className="chat-section">
          <Chat log={chatLog} onSend={sendChat} />
        </div>
      </div>
    </div>
  );
}
