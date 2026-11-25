# Foxtrader Desk

Browser terminal for the same markets the Android app trades: FX, metals, and indices.

Candles come from the Dukascopy BID archive (`.bi5`) plus the live minute feed on `jetta.dukascopy.com`, so the chart includes the bar that is still forming. The hostname `datafeed.dukascopy.com` often answers 503; history is read from the archive address that still serves the files. **Asia Sweep Reclaim** reads closed candles only: the 00:00–07:00 UTC Asia range, then a London/New York wick through that range that closes back inside. A confirmed marker does not move. Meridian structure stays on the chart beside it. The blotter is a sample, not live fills.

```bash
cd web
npm install
npm run dev
```

Open the desk and wait for **Dukascopy connected**. Today's bar appears only after Dukascopy publishes that day's file.

Production build: `npm run build`. Output is `web/dist`. A static host still needs something serving `GET /api/market?symbol=EURUSD&timeframe=H1` with the same loader in `server/dukascopy.server.ts`.
