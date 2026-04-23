// Board.jsx — Renders a 10×10 Battleship grid.
//
// Props:
//   cells        — 2D array (10×10) of cell codes:
//                    0 = unknown / water
//                    1 = ship cell (my board only)
//                    2 = hit
//                    3 = miss
//   interactive  — boolean; if true, code-0 cells are clickable (opponent board)
//   onFire(r, c) — called when the user clicks an unknown cell

const COLS = ["1","2","3","4","5","6","7","8","9","10"];
const ROWS  = ["A","B","C","D","E","F","G","H","I","J"];

function cellStyle(code, clickable) {
  const base = {
    width: 30,
    height: 30,
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: 13,
    border: "1px solid #1a3a5a",
    cursor: clickable ? "pointer" : "default",
    userSelect: "none",
    transition: "background 0.1s",
  };
  if (code === 2) return { ...base, background: "#c62828", color: "#fff" };   // hit — red
  if (code === 3) return { ...base, background: "#37474f", color: "#90a4ae" }; // miss — grey
  if (code === 1) return { ...base, background: "#1b5e20" };                   // ship — dark green
  if (clickable)  return { ...base, background: "#1565c0" };                   // unknown + interactive — blue
  return { ...base, background: "#0d1b2a" };                                   // water — dark
}

export default function Board({ cells, interactive, onFire }) {
  if (!cells || cells.length === 0) return null;

  return (
    <div style={{ display: "inline-block", fontFamily: "monospace" }}>
      {/* Column headers */}
      <div style={{ display: "flex", marginLeft: 26 }}>
        {COLS.map(c => (
          <div
            key={c}
            style={{ width: 30, textAlign: "center", fontSize: 11, color: "#546e7a", paddingBottom: 2 }}
          >
            {c}
          </div>
        ))}
      </div>

      {/* Rows */}
      {cells.map((row, r) => (
        <div key={r} style={{ display: "flex", alignItems: "center" }}>
          {/* Row label */}
          <div style={{ width: 22, fontSize: 11, color: "#546e7a", textAlign: "right", paddingRight: 4 }}>
            {ROWS[r]}
          </div>

          {/* Cells */}
          {row.map((code, c) => {
            const clickable = interactive && code === 0;
            return (
              <div
                key={c}
                style={cellStyle(code, clickable)}
                onClick={() => clickable && onFire && onFire(r, c)}
                title={`${ROWS[r]}${COLS[c]}`}
              >
                {code === 2 ? "✕" : code === 3 ? "·" : ""}
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}
