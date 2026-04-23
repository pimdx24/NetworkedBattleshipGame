// Chat.jsx — In-game chat panel.
//
// Props:
//   log            — array of { from, text, time } objects (newest last)
//   onSend(text)   — called when the user submits a message

import { useState, useRef, useEffect } from "react";

export default function Chat({ log, onSend }) {
  const [input, setInput] = useState("");
  const bottomRef = useRef(null);

  // Auto-scroll to the bottom whenever a new message arrives.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [log]);

  const submit = () => {
    const trimmed = input.trim();
    if (!trimmed) return;
    onSend(trimmed);
    setInput("");
  };

  return (
    <div style={{
      display: "flex",
      flexDirection: "column",
      height: "100%",
      minHeight: 400,
      background: "#0d1b2a",
      border: "1px solid #1a3a5a",
      borderRadius: 8,
      padding: 12,
    }}>
      <h3 style={{ color: "#90caf9", marginBottom: 10, fontSize: 15 }}>Chat</h3>

      {/* Message log */}
      <div style={{
        flex: 1,
        overflowY: "auto",
        marginBottom: 10,
        paddingRight: 4,
      }}>
        {log.length === 0 && (
          <p style={{ color: "#37474f", fontSize: 12, fontStyle: "italic" }}>No messages yet.</p>
        )}
        {log.map((msg, i) => (
          <div key={i} style={{ marginBottom: 8 }}>
            <div style={{ display: "flex", gap: 6, alignItems: "baseline" }}>
              <span style={{ color: "#90caf9", fontSize: 12, fontWeight: "bold" }}>{msg.from}</span>
              <span style={{ color: "#546e7a", fontSize: 11 }}>{msg.time}</span>
            </div>
            <div style={{ color: "#e0e0e0", fontSize: 13, wordBreak: "break-word" }}>{msg.text}</div>
          </div>
        ))}
        <div ref={bottomRef} />
      </div>

      {/* Input area */}
      <div style={{ display: "flex", gap: 6 }}>
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => e.key === "Enter" && submit()}
          placeholder="Type a message…"
          maxLength={200}
          style={{
            flex: 1,
            padding: "7px 10px",
            background: "#1a2c45",
            border: "1px solid #2a4a6a",
            borderRadius: 6,
            color: "#e0e0e0",
            fontSize: 13,
            outline: "none",
          }}
        />
        <button onClick={submit} style={{ padding: "7px 14px", fontSize: 13 }}>
          Send
        </button>
      </div>
    </div>
  );
}
