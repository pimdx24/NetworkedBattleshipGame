// vite.config.js
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The React dev server runs on http://localhost:5173.
// The Java server listens on ws://localhost:8080.
//
// The browser connects directly to ws://localhost:8080 — no Vite proxy is
// needed.  Both processes must be running simultaneously (see QUICKSTART.md).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
});
