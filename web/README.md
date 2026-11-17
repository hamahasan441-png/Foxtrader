# Foxtrader Desk

Browser terminal for the same markets the Android app trades: FX, metals, and indices.

The chart, watchlist, structure / liquidity / setup layers, and blotter are this desk. Candles are a structured session tape so the layout stays readable without a broker login. Live history on the phone still comes from the Dukascopy `.bi5` feed in the Android app.

```bash
cd web
npm install
npm run dev
```

Production build: `npm run build`. Output is `web/dist`.
