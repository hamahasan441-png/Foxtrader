import path from "node:path";
import { defineConfig, type ViteDevServer } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const FRAMES = new Set(["M1", "M5", "M15", "H1", "H4", "D1"]);

function marketApi(): { name: string; configureServer: (server: ViteDevServer) => void } {
  return {
    name: "dukascopy-market-api",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const raw = req.url ?? "";
        if (!raw.startsWith("/api/market")) {
          next();
          return;
        }
        const url = new URL(raw, "http://127.0.0.1");
        const symbol = (url.searchParams.get("symbol") ?? "").trim().toUpperCase();
        const timeframe = (url.searchParams.get("timeframe") ?? "").trim().toUpperCase();
        try {
          if (!FRAMES.has(timeframe)) throw new Error("Unknown timeframe");
          const mod = (await server.ssrLoadModule("/server/dukascopy.server.ts")) as {
            loadDukascopy: (
              symbol: string,
              timeframe: "M1" | "M5" | "M15" | "H1" | "H4" | "D1",
            ) => Promise<{ candles: unknown[]; error: string | null }>;
          };
          const result = await mod.loadDukascopy(
            symbol,
            timeframe as "M1" | "M5" | "M15" | "H1" | "H4" | "D1",
          );
          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          res.end(JSON.stringify(result));
        } catch (error) {
          res.statusCode = 200;
          res.setHeader("content-type", "application/json");
          res.end(
            JSON.stringify({
              candles: [],
              error: error instanceof Error ? error.message : "Dukascopy request failed",
            }),
          );
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), marketApi()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "src"),
    },
  },
  server: {
    host: "0.0.0.0",
    port: 5173,
  },
});
