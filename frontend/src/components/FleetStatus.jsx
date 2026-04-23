// FleetStatus.jsx — Displays the status of each ship in a fleet.
//
// Props:
//   fleet  — array of { name, size, sunk } objects
//   label  — string heading (e.g. "My Ships" or "Opponent's Ships")

export default function FleetStatus({ fleet, label }) {
  if (!fleet) return null;

  return (
    <div style={{ marginTop: 10 }}>
      <h4 style={{
        color: "#90a4ae",
        fontSize: 11,
        textTransform: "uppercase",
        letterSpacing: "0.07em",
        marginBottom: 8,
      }}>
        {label}
      </h4>

      {fleet.map(ship => (
        <div key={ship.name} style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          marginBottom: 5,
          opacity: ship.sunk ? 0.55 : 1,
        }}>
          {/* Pip squares — one per ship cell */}
          <div style={{ display: "flex", gap: 2 }}>
            {Array.from({ length: ship.size }).map((_, i) => (
              <div key={i} style={{
                width: 11,
                height: 11,
                borderRadius: 2,
                background: ship.sunk ? "#546e7a" : "#2e7d32",
                border: "1px solid " + (ship.sunk ? "#37474f" : "#388e3c"),
              }} />
            ))}
          </div>

          {/* Ship name */}
          <span style={{
            fontSize: 12,
            color: ship.sunk ? "#546e7a" : "#cfd8dc",
            textDecoration: ship.sunk ? "line-through" : "none",
          }}>
            {ship.name} ({ship.size})
          </span>

          {/* SUNK badge */}
          {ship.sunk && (
            <span style={{
              fontSize: 10,
              color: "#ef5350",
              fontWeight: "bold",
              letterSpacing: "0.05em",
            }}>
              SUNK
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
