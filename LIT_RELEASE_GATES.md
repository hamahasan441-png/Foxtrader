# LiT release gates

- Canonical LiT sequence only for LiT execution evidence.
- No generic ICT/SMC or SMT fallback may override canonical rejection.
- Closed-bar only decision input.
- Live/backtest prefix parity regression required.
- Forming-candle mutation isolation required.
- Duplicate/out-of-order/non-finite provider bars fail closed.
- Equivalent normalized provider candles must produce identical LiT decisions.
- One objectively confirmed LiT/LiTX event must map to one semantic `eventKey` across direct-engine and StrategyLibrary chart paths.
- Strategy mirrors must be deduplicated before confluence scoring so they cannot create duplicate arrows or false confidence boosts.
- Legacy `ChartSignal.id` values remain compatibility identifiers; semantic deduplication must not require changing them.
- No universal win-rate claim.
- Missing proprietary LiT definitions remain SPECIFICATION REQUIRED until authoritative rules are supplied.
