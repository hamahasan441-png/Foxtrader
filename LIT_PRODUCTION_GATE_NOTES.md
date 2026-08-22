# LiT production-gate hardening

This branch closes two concrete release risks without inventing proprietary LIT definitions.

## Closed

- **AI-agent canonical bypass:** `LitAgent` is now fail-closed. Generic ICT/SMC sweep, BOS/CHOCH, direct equal-high/equal-low heuristics, and SMT cannot be re-labelled as execution-grade LiT after the canonical `LitEngine` rejects a setup.
- **Live/backtest prefix parity regression:** tests compare the live confirmed-bar prefix with the historical/backtest prefix at every cutoff and verify identical LiT stage/context/signal decisions.
- **Forming-candle isolation:** mutating the active candle cannot change the canonical LiT decision because the active bar is excluded from signal evidence.
- **Provider timestamp safety:** duplicated/out-of-order timestamps fail closed before LiT signal emission.

## Still specification/data dependent

The repository still must not invent missing proprietary definitions for LET, MMM1, MMM2, EDM, Major/Medium/Minor inducement hierarchy, proprietary Vector semantics, AH/FO/LO/NY LIT semantics, or formal cycle-completion/projection rules. Those remain `SPECIFICATION REQUIRED` until authoritative definitions are supplied.

No universal accuracy or win-rate claim is made. Production statistical acceptance remains symbol/timeframe/regime dependent and requires genuine out-of-sample/walk-forward evaluation.
