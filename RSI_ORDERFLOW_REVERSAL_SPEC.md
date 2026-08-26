# RSI Orderflow Structure Reversal Strategy — Master Implementation Plan

## Purpose
Build a deterministic, non-repainting, multi-timeframe reversal strategy based on:

**Price Structure + RSI Orderflow Candles + Divergence + RSI Structure Break + Final Price Extreme / Liquidity Sweep + Lower-Timeframe Confirmation + Fixed Risk/Reward**

The implementation must support BUY and SELL, historical signals, live signals, replay/backtest parity, and a clean on-chart signal UI.

> Important: the visual reference for `RSI Orderflow Candle` is provided in the bundle as `17998.jpg` and `17999.jpg`. Use those images as visual/behavioral references only. Implement the indicator with a clean-room design; do not copy protected/closed source code.

---

# 1. Core Strategy Principle

The strategy is built around one rule:

> **Price creates a new extreme. RSI Orderflow determines whether that extreme is confirmed continuation or momentum failure. If RSI confirms the new extreme, move the reference forward. If RSI fails to confirm, arm the reversal setup. Only after the setup is armed do we drop to the lower timeframe for the actual entry.**

---

# 2. High-Level Architecture

Create separate modules/engines:

1. Price Structure Engine
2. RSI Orderflow Candle Engine
3. RSI Structure Engine
4. HTF Pattern / State Machine
5. MTF Synchronizer
6. LTF Confirmation Engine
7. Entry / Risk Engine
8. Signal Rendering Engine
9. Historical / Replay Engine
10. Backtest / Statistics Engine
11. Test / Reliability Layer

Rendering must never affect signal calculations.

---

# 3. RSI Orderflow Candle Engine

## 3.1 Goal
Represent RSI as candlesticks rather than a single line.

Each market candle has:

- Open
- High
- Low
- Close

Create corresponding RSI values:

- RSI Open
- RSI High
- RSI Low
- RSI Close

## 3.2 Base RSI

Default settings:

- RSI Length = 14
- Range = 0–100
- Oversold = 30
- Midline = 50
- Overbought = 70

Use Wilder/RMA-style RSI.

For each bar:

```text
rOpen  = RSI(Open, length)
rHigh  = RSI(High, length)
rLow   = RSI(Low, length)
rClose = RSI(Close, length)
```

Canonical RSI candle:

```text
RSI_Open  = rOpen
RSI_High  = max(rOpen, rHigh, rLow, rClose)
RSI_Low   = min(rOpen, rHigh, rLow, rClose)
RSI_Close = rClose
```

Bullish RSI candle:

```text
RSI_Close >= RSI_Open
```

Bearish RSI candle:

```text
RSI_Close < RSI_Open
```

## 3.3 Calculation vs Visual Layer

Signal logic must always use raw RSI OHLC.

Optional visual modes may include:

- Raw
- Smoothed
- Heikin-Ashi visual mode

But smoothing/HA must never be used by divergence, structure, or entry logic.

---

# 4. RSI Orderflow Visual Requirements

Use the two reference images in this bundle.

The RSI panel should have:

- Separate lower pane
- Candlestick bodies
- Visible upper/lower wicks
- Bullish and bearish candle coloring
- Levels 30 / 50 / 70
- Dark, clean chart appearance
- No unnecessary line plot by default
- Proper candle spacing and zoom behavior
- Horizontal synchronization with the main chart

Debug mode may optionally show labels like P1, P2, P3, P4.

---

# 5. Price Structure Engine

Detect and store:

- Swing High
- Swing Low
- HH
- HL
- LH
- LL
- Equal High
- Equal Low

Price structure must use High/Low, not only Close.

## 5.1 Non-Repaint Swing Detection

Default configurable pivot strength:

```text
left = 2
right = 2
```

A swing is only confirmed once all required right-side bars exist.

After confirmation, the swing must never move or disappear.

---

# 6. RSI Structure Engine

RSI structure is independent from price structure.

Use:

- RSI_High
- RSI_Low

Detect:

- RSI Swing High
- RSI Swing Low
- RSI HH
- RSI HL
- RSI LH
- RSI LL

Do not reduce RSI structure to RSI_Close only.

---

# 7. BUY Master Pattern

## 7.1 P1 — First Confirmed Low

Store:

```text
P1.index
P1.time
P1.priceLow
P1.rsiLow
```

## 7.2 P2 — Price LL + RSI Failure

Price must make:

```text
P2.priceLow < P1.priceLow
```

But RSI must not confirm the lower low:

```text
P2.rsiLow >= P1.rsiLow
```

Preferred strong form:

```text
P2.rsiLow > P1.rsiLow
```

So:

```text
Price = LL
RSI = HL / non-LL
```

This creates an **Initial Bullish Divergence Candidate**.

No entry yet.

State:

```text
WAIT_RSI_BREAK
```

---

# 8. BUY P3 — RSI Structure Break

After P1/P2 divergence, identify the relevant RSI swing high / protected RSI high.

P3 occurs when RSI breaks that prior top upward.

Track two event types:

### Wick Break

```text
RSI_High[current] > ProtectedRsiHigh
```

### Close Break

```text
RSI_Close[current] > ProtectedRsiHigh
```

Default confirmation mode:

```text
CLOSE BREAK
```

Backtest wick and close modes independently.

After P3:

```text
state = WAIT_PRICE_P4
```

---

# 9. BUY P4 — Final Price LL / Liquidity Test

After P3, wait for price to return lower and break P2:

```text
Low(P4) < Low(P2)
```

Important:

- A wick break is enough.
- A body close below P2 is not mandatory.

This event may represent:

- Liquidity sweep
- Stop hunt
- New LL
- Wick penetration

---

# 10. BUY P4 Decision Branch

Compare:

```text
RSI_Low(P4)
```

against:

```text
RSI_Low(P2)
```

## 10.1 Pattern B1 — Preserved Bullish Divergence

If:

```text
PriceLow(P4) < PriceLow(P2)
AND
RSILow(P4) > RSILow(P2)
```

Then:

```text
Price = new LL
RSI = HL
```

Result:

```text
BUY_ARMED
```

Immediately begin lower-timeframe entry search.

No additional HTF confirmation is required.

## 10.2 Pattern B2 — True RSI LL

If:

```text
PriceLow(P4) < PriceLow(P2)
AND
RSILow(P4) < RSILow(P2)
```

Then RSI confirmed continuation.

Do not enter.

Move the active reference:

```text
ReferencePriceLow = P4.priceLow
ReferenceRsiLow   = P4.rsiLow
ReferenceIndex    = P4.index
```

State:

```text
WAIT_NEXT_EXTREME
```

---

# 11. Recursive BUY Re-Arm Logic

If P4 becomes the active reference, wait for P5:

```text
PriceLow(P5) < PriceLow(P4)
```

Again, wick break is valid.

Compare RSI:

### If RSI fails to make a new LL

```text
RSILow(P5) > RSILow(P4)
```

Then:

```text
BUY_ARMED
```

### If RSI also makes a new LL

```text
RSILow(P5) < RSILow(P4)
```

Then:

```text
Reference = P5
```

Wait for P6.

Repeat recursively:

```text
P4 -> P5 -> P6 -> P7 -> ...
```

until price creates a new LL that RSI fails to confirm.

This recursion must not be artificially limited to four points.

---

# 12. SELL Master Pattern — Exact Mirror

SELL logic must be the exact mirror of BUY.

## 12.1 P1/P2

Price:

```text
P2.high > P1.high
```

RSI:

```text
RSIHigh(P2) <= RSIHigh(P1)
```

Preferred strong form:

```text
RSIHigh(P2) < RSIHigh(P1)
```

So:

```text
Price = HH
RSI = LH / non-HH
```

This creates initial bearish divergence.

## 12.2 P3

RSI breaks its relevant protected swing low downward.

Wick event:

```text
RSI_Low < ProtectedRsiLow
```

Close event:

```text
RSI_Close < ProtectedRsiLow
```

Default: close break.

## 12.3 P4

Price returns upward and breaks P2:

```text
High(P4) > High(P2)
```

Wick is enough.

### Direct SELL pattern

```text
PriceHigh(P4) > PriceHigh(P2)
AND
RSIHigh(P4) < RSIHigh(P2)
```

Result:

```text
SELL_ARMED
```

### True RSI HH

```text
PriceHigh(P4) > PriceHigh(P2)
AND
RSIHigh(P4) > RSIHigh(P2)
```

Move reference to P4 and wait for next HH.

Repeat recursively until price makes a new HH that RSI fails to confirm.

---

# 13. Formal State Machine

Do not implement this as scattered conditionals only.

Use a formal state machine, for example:

```text
IDLE
FOUND_P1
WAIT_P2
DIVERGENCE_FOUND
WAIT_RSI_STRUCTURE_BREAK
RSI_BREAK_CONFIRMED
WAIT_FINAL_PRICE_EXTREME
CHECK_FINAL_RSI
WAIT_RECURSIVE_EXTREME
BUY_ARMED
SELL_ARMED
WAIT_LTF_CONFIRMATION
ENTRY_READY
IN_TRADE
TP_HIT
SL_HIT
EXPIRED
INVALIDATED
RESET
```

---

# 14. BUY State Flow

```text
IDLE
 ↓
P1
 ↓
P2 Price LL + RSI HL
 ↓
Initial Divergence
 ↓
RSI P3 upside structure break
 ↓
Wait P4
 ↓
Price breaks P2 low
 ↓
Check RSI at P4
 ├──────────────────┐
 │                  │
RSI HL            RSI LL
 │                  │
BUY_ARMED         Reference=P4
 │                  │
Drop to LTF       Wait next LL
                    │
                 Compare RSI
                 ↙         ↘
              RSI HL       RSI LL
                │            │
             BUY_ARMED    Move Ref
```

SELL is the exact inverse.

---

# 15. Lower-Timeframe Mapping

When BUY_ARMED or SELL_ARMED, drop one timeframe lower.

Default mapping:

```text
1D  -> 4H
4H  -> 1H
1H  -> 15m
30m -> 5m
15m -> 5m
5m  -> 1m
3m  -> 1m
```

Must be user-configurable.

---

# 16. LTF BUY Confirmation Patterns

Only search BUY confirmations while BUY_ARMED.

## E1 — Liquidity Sweep -> Bullish CHOCH

Sequence:

```text
Sweep local low
->
Break protected lower-high
```

Entry can be on:

- CHOCH close, or
- Retest

Configurable.

## E2 — Sweep -> Displacement -> BOS

Require:

```text
Liquidity sweep
+
Strong bullish displacement
+
Break LTF swing high
```

Displacement can use:

- body relative to recent average body, or
- ATR multiplier

## E3 — CHOCH -> BOS -> Retest

Sequence:

```text
CHOCH
->
Higher Low
->
BOS
->
Retest
->
BUY
```

---

# 17. LTF SELL Confirmation Patterns

Exact mirror:

```text
Sweep local high
->
Bearish CHOCH
->
Displacement down
->
BOS
->
Retest
->
SELL
```

---

# 18. Entry Modes

Provide three presets:

### Aggressive

```text
Sweep + CHOCH
```

### Balanced — Default

```text
Sweep + CHOCH + Displacement
```

### Strict

```text
Sweep + CHOCH + BOS + Retest
```

---

# 19. Stop Loss Rules

## BUY

Default:

```text
SL below final LTF swept low
```

Optional modes:

- Below CHOCH origin swing
- Below HTF final extreme

Optional buffer:

```text
spread + small ATR fraction
```

## SELL

Exact mirror:

```text
SL above final LTF swept high
```

---

# 20. Take Profit

Default:

```text
RR = 1:4
```

BUY:

```text
Risk = Entry - Stop
TP = Entry + 4 * Risk
```

SELL:

```text
Risk = Stop - Entry
TP = Entry - 4 * Risk
```

Optional opposing-liquidity awareness may be added, but it must not silently alter the core 4R mode.

---

# 21. Signal Object

Every signal should store full context:

```text
Signal {
    id
    symbol
    direction
    contextTimeframe
    entryTimeframe

    p1Time
    p1Price
    p1Rsi

    p2Time
    p2Price
    p2Rsi

    p3Time
    p3RsiBreak

    finalExtremeTime
    finalExtremePrice
    finalExtremeRsi

    recursiveCount
    confirmationType

    entry
    stop
    target
    riskReward

    state
    createdAt
    confirmedAt

    isHistorical
    isLive
}
```

---

# 22. Main Chart Signal Rendering

Default chart should remain clean.

Only final entry signals are shown.

BUY:

```text
↑
```

Below the entry candle.

SELL:

```text
↓
```

Above the entry candle.

Requirements:

- Clear size
- Zoom aware
- No flicker
- No duplicate arrows
- Historical arrows persist
- No repaint

Optional compact label:

```text
BUY
15m -> 5m
RR 1:4
```

---

# 23. Debug Visualization

Optional developer/debug mode may show:

- P1
- P2
- P3
- P4
- Recursive P5/P6/etc.
- RSI reference swings
- CHOCH
- BOS
- Sweep
- Entry
- SL
- TP

Default user mode: OFF.

---

# 24. Intrabar Event Types

Track event classes separately:

```text
TOUCH
WICK_BREAK
CLOSE_BREAK
```

Default policy:

- Final price extreme P4/P5/etc.: WICK_BREAK valid
- RSI P3 structure break: CLOSE_BREAK
- LTF CHOCH/BOS: CLOSE_BREAK

All should be configurable for research/backtesting.

---

# 25. Equality / Tolerance Rules

Never rely on exact floating-point equality.

RSI epsilon:

```text
0.05–0.10 RSI points default range
```

Example:

```text
abs(rsiA - rsiB) <= epsilon
```

For price, use tick-size-aware comparisons.

---

# 26. Optional Noise Filters

Provide optional, disabled-by-default filters:

- Minimum price LL/HH distance
- Minimum RSI divergence distance
- Minimum bars between pivots
- Maximum setup age
- Session filter
- Regime filter
- Minimum displacement strength

Do not hardwire them into the core strategy before testing.

---

# 27. Setup Expiry

A setup must not remain active forever.

Configurable limits:

- Max bars P1->P2
- Max bars P2->P3
- Max bars P3->P4/final extreme
- Max recursive extremes
- LTF confirmation window

If expired:

```text
EXPIRED
```

---

# 28. MTF Synchronization

Map HTF setup activation time to the LTF precisely.

Requirements:

- No lookahead
- No use of future HTF close
- Correct timestamp alignment
- No duplicated LTF processing
- Respect incomplete candles

---

# 29. LTF Entry Window

BUY_ARMED / SELL_ARMED should remain active only for a configurable number of LTF bars.

Example default research range:

```text
3–12 LTF bars
```

If no valid confirmation appears:

```text
EXPIRED
```

---

# 30. Duplicate Protection

Each HTF setup must have a unique key, e.g.:

```text
symbol + HTF + P1 index + P2 index + final reference index
```

Do not allow several arrows for the same setup unless explicitly configured.

---

# 31. Historical / Live Requirements

The engine must support:

- Historical scan
- Live calculation
- Replay
- App restart
- Symbol change
- Timeframe change

Signals must reappear in exactly the same historical locations after recalculation.

---

# 32. Strict Non-Repaint Requirements

1. Never use future bars for a current signal.
2. Confirmed swings never move after confirmation.
3. Confirmed signals never disappear later.
4. Reload must reproduce the same signals.
5. Historical batch calculation must equal step-by-step replay.
6. Rendering must not change calculations.
7. Timezone changes must not change structure logic.
8. Candidate intrabar events and confirmed events must be separate.

---

# 33. Backtest Engine

For every setup record:

- Date/time
- Symbol
- HTF
- LTF
- Direction
- P1/P2/P3/final extreme
- Recursive depth
- Entry type
- Entry
- SL
- TP
- Risk
- Reward
- Result

Result types:

```text
WIN
LOSS
BE
EXPIRED
INVALID
```

---

# 34. Backtest Statistics

Report:

- Total setups
- Total trades
- Win rate
- Loss rate
- Average R
- Total R
- Expectancy
- Profit factor
- Max drawdown
- Max losing streak
- Max winning streak
- Average bars to confirmation
- Average recursive depth

---

# 35. Pattern-Specific Statistics

Compare separately:

### Direct Pattern

```text
P4 price new extreme
RSI fails new extreme
```

### Recursive Depth 1

```text
P4 RSI confirms
P5 RSI fails
```

### Recursive Depth 2+

```text
P4 confirms
P5 confirms
P6 fails
```

Also compare:

- RSI wick break vs RSI close break
- Aggressive vs Balanced vs Strict entries
- BUY vs SELL
- Different HTF->LTF mappings

---

# 36. Replay Validation

Replay must feed candles one by one.

For each candle log:

```text
state before candle
input event
detected events
state after candle
```

Then compare replay result against full historical calculation.

Expected:

```text
Replay signals == Historical signals
```

Exact match required.

---

# 37. Unit Tests — RSI

Test:

- RSI range stays 0–100
- RSI candle OHLC is valid
- RSI High >= Open/Close
- RSI Low <= Open/Close
- No NaN after warmup
- Incremental RSI == full recalculation

---

# 38. Unit Tests — Divergence

BUY positive test:

```text
Price P2 < P1
RSI P2 > P1
=> TRUE
```

BUY negative test:

```text
Price LL
RSI LL
=> FALSE
```

Mirror for SELL.

---

# 39. Unit Tests — Recursion

Example synthetic sequence:

```text
Price: LL1 -> LL2 -> LL3 -> LL4
RSI:   HL2 -> LL3 -> HL4
```

Expected:

- No arm at LL3 if RSI made LL3
- Arm at LL4 when RSI fails to confirm LL4

---

# 40. Unit Tests — P3

Test:

- Wick-only break
- Close break
- Equal level
- False breakout
- Repeated breakout

---

# 41. Unit Tests — LTF

Examples:

```text
Sweep without CHOCH -> NO ENTRY
CHOCH without HTF armed -> NO ENTRY
HTF armed + valid confirmation -> ENTRY
Expired window -> NO ENTRY
```

---

# 42. Reliability / Crash Tests

Test:

- Empty data
- One candle only
- Insufficient RSI history
- Data gaps
- Duplicate candles
- Out-of-order candles
- Symbol changes
- Timeframe changes
- App background/resume
- Reconnect
- Large history (100k+ bars)

No crash, no deadlock, no duplicate state.

---

# 43. Performance Requirements

Use incremental updates.

Cache:

- RSI OHLC
- Confirmed price swings
- Confirmed RSI swings
- Active HTF setup state
- Recursive reference
- MTF mapping state
- LTF structure state

Do not recalculate all historical bars on every tick.

---

# 44. Settings UI

Suggested tabs:

- Strategy
- RSI Orderflow
- Structure
- Entry
- Risk
- Visual
- Backtest
- Debug

Default preset:

```text
RSI length = 14
Price pivot left/right = 2/2
RSI pivot left/right = 2/2
P3 RSI break = Close
P4/final price break = Wick
LTF = one timeframe lower
Confirmation = Balanced
SL = behind final LTF sweep
TP = 4R
```

---

# 45. Signal Status UI

Optional compact state text:

```text
Scanning
Bullish divergence detected
Waiting RSI break
RSI confirmed
Waiting final LL
BUY Armed
Waiting LTF confirmation
BUY Confirmed
```

Mirror for SELL.

---

# 46. Implementation Order

Implement in phases:

1. RSI Orderflow Candle Engine
2. Price Structure Engine
3. RSI Structure Engine
4. P1/P2 divergence
5. P3 RSI structure break
6. P4 + recursive final-extreme logic
7. BUY/SELL state machine
8. MTF synchronization
9. LTF Sweep/CHOCH/BOS
10. Entry / SL / TP
11. Signal rendering
12. Historical signal reconstruction
13. Backtester
14. Unit/integration/replay tests
15. Performance optimization
16. Crash/reliability hardening

Do not skip ahead while a previous phase is unstable.

---

# 47. Acceptance Criteria

The task is not complete until all of the following are true:

- RSI Orderflow candles render correctly
- Price swings are non-repainting
- RSI swings are non-repainting
- P1/P2 detection is correct
- P3 detection is correct
- P4 detection is correct
- Recursive reference movement is correct
- BUY and SELL are exact mirrors
- LTF confirmation works
- Historical signals persist
- Live/replay/historical results match
- No duplicate arrows
- RR is calculated correctly
- 100k+ bars remain stable
- No crashes
- All tests pass

---

# 48. Development Constraints for Work

1. First audit the existing project architecture.
2. Integrate this strategy without unnecessarily rewriting unrelated stable systems.
3. Do not silently change strategy rules.
4. If a rule is ambiguous, isolate it behind a clearly named configurable parameter and write tests for both interpretations.
5. No TODOs, placeholders, fake signals, hard-coded test outputs, or unfinished branches may remain in the production path.
6. Preserve deterministic behavior.
7. Document every material change.

---

# 49. Required Final Deliverables

Work should return:

1. Full implementation
2. RSI Orderflow Candle module
3. Price + RSI structure modules
4. BUY/SELL state machines
5. Recursive re-arm logic
6. MTF synchronizer
7. LTF confirmation engine
8. Entry / SL / TP engine
9. Chart rendering
10. Historical signal reconstruction
11. Backtest engine
12. Unit tests
13. Integration tests
14. Replay/non-repaint verification
15. Performance verification
16. Crash/reliability verification
17. Changelog
18. Architecture note
19. Strategy rule documentation
20. Evidence that live/replay/historical calculations agree

---

# 50. Compact BUY Rule

```text
Price LL
-> RSI fails LL
-> RSI breaks upside structure
-> Price makes/sweeps another LL
-> Compare RSI
-> If RSI HL: BUY_ARMED
-> If RSI LL: move reference and wait next LL
-> LTF sweep / CHOCH / BOS confirmation
-> BUY
-> SL below final LTF sweep
-> TP = 4R
```

# 51. Compact SELL Rule

```text
Price HH
-> RSI fails HH
-> RSI breaks downside structure
-> Price makes/sweeps another HH
-> Compare RSI
-> If RSI LH: SELL_ARMED
-> If RSI HH: move reference and wait next HH
-> LTF sweep / CHOCH / BOS confirmation
-> SELL
-> SL above final LTF sweep
-> TP = 4R
```

---

# 52. Reference Images

The bundle contains:

- `17998.jpg` — primary reference for price/RSI structure and numbered points
- `17999.jpg` — lower-timeframe confirmation / structure reference

Use these images to understand the intended visual behavior and the relationship between price structure and RSI Orderflow candles.

