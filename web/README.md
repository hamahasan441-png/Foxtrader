# Foxtrader Desk

Browser terminal for the same markets the Android app trades: FX, metals, and indices.

Candles come from the Dukascopy BID archive (`.bi5`), proxied by the dev server because the archive does not allow a browser to fetch it directly. The chart indicator is **Meridian**: structure, liquidity, LIT, and trade levels. The blotter is a sample, not live fills.

```bash
cd web
npm install
npm run dev
```

Open the desk and wait for **Dukascopy connected**. Today's bar appears only after Dukascopy publishes that day's file.

Production build: `npm run build`. Output is `web/dist`. A static host still needs something serving `GET /api/market?symbol=EURUSD&timeframe=H1` with the same loader in `server/dukascopy.server.ts`.
