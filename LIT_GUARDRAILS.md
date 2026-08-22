# LiT guardrails

1. Fail closed on malformed OHLCV or timestamp ordering.
2. Evaluate confirmed bars only.
3. Preserve chronological IDM -> BOS -> CHOCH validation.
4. Never allow generic fallback evidence to override canonical rejection.
5. Keep correlated evidence families from inflating confidence.
6. Require live/backtest prefix parity.
7. Treat accuracy as an empirical out-of-sample property, not a source-code constant.
