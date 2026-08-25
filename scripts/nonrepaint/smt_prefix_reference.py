"""
Faithful port of the pure decision core of SmtDivergenceDetector:
  - findSwings(candles, lookback, isHigh)
  - synchronizedPairs(primarySwings, peerSwings, maxSyncBars)
  - the low-side / high-side sweep-vs-held comparison
  - confirmationAligned = max(p1,q1) + swingLookback

Timestamps are made perfectly aligned so align() is the identity and the
alignment layer is factored out; this isolates the pair-matching logic.

The test: evaluate prefixes 1..N. Every divergence event is keyed by
(direction, type, p0, p1, confirmationIndex-in-absolute-bars). Once an event
is emitted at the prefix where it first becomes knowable, it must remain
emitted (with identical peer indices) in every longer prefix, until it ages
out of the retained window.
"""
import random

SWING_LOOKBACK = 3
MAX_SYNC_BARS = 4          # SmtConfig.maxSwingSyncBars default (INTRADAY)
MAX_SIGNAL_AGE = 24
PERIOD = 160


def find_swings(bars, lookback, is_high):
    """bars: list of (high, low). Mirrors Kotlin findSwings exactly."""
    out = []
    for i in range(lookback, len(bars) - lookback):
        if is_high:
            ok = all(bars[k][0] < bars[i][0] for k in range(i - lookback, i)) and \
                 all(bars[k][0] <= bars[i][0] for k in range(i + 1, i + lookback + 1))
        else:
            ok = all(bars[k][1] > bars[i][1] for k in range(i - lookback, i)) and \
                 all(bars[k][1] >= bars[i][1] for k in range(i + 1, i + lookback + 1))
        if ok:
            out.append(i)
    return out


def synchronized_pairs(primary_swings, peer_swings, max_sync):
    """Mirrors Kotlin synchronizedPairs exactly."""
    if len(primary_swings) < 2 or len(peer_swings) < 2:
        return []
    result = []
    for pi in range(1, len(primary_swings)):
        p0 = primary_swings[pi - 1]
        p1 = primary_swings[pi]
        best = None
        best_dist = 1 << 30
        for qi in range(1, len(peer_swings)):
            q0 = peer_swings[qi - 1]
            q1 = peer_swings[qi]
            fd = abs(p0 - q0)
            sd = abs(p1 - q1)
            if fd > max_sync or sd > max_sync:
                continue
            total = fd + sd
            if total < best_dist or (total == best_dist and (best is None or q1 < best[3])):
                best = (p0, p1, q0, q1)
                best_dist = total
        if best is not None:
            result.append(best)
    # distinctBy
    seen, dedup = set(), []
    for p in result:
        if p not in seen:
            seen.add(p)
            dedup.append(p)
    return dedup


def detect(primary, peer, lookback=SWING_LOOKBACK, max_sync=MAX_SYNC_BARS):
    """Returns list of events for the given prefix, in ALIGNED index space
    (== absolute index here, since align() is the identity)."""
    n = len(primary)
    last = n - 1
    events = []

    p_lows = find_swings(primary, lookback, False)
    q_lows = find_swings(peer, lookback, False)
    for (p0, p1, q0, q1) in synchronized_pairs(p_lows, q_lows, max_sync):
        conf = max(p1, q1) + lookback
        if conf > last:
            continue
        primary_swept = primary[p1][1] < primary[p0][1]
        peer_held = peer[q1][1] >= peer[q0][1]
        peer_swept = peer[q1][1] < peer[q0][1]
        primary_held = primary[p1][1] >= primary[p0][1]
        if primary_swept and peer_held:
            events.append(("BULLISH", "PRIMARY_SWEEP_PEER_FAIL", p0, p1, q0, q1, conf))
        if peer_swept and primary_held:
            events.append(("BULLISH", "PEER_SWEEP_PRIMARY_FAIL", p0, p1, q0, q1, conf))

    p_highs = find_swings(primary, lookback, True)
    q_highs = find_swings(peer, lookback, True)
    for (p0, p1, q0, q1) in synchronized_pairs(p_highs, q_highs, max_sync):
        conf = max(p1, q1) + lookback
        if conf > last:
            continue
        primary_swept = primary[p1][0] > primary[p0][0]
        peer_held = peer[q1][0] <= peer[q0][0]
        peer_swept = peer[q1][0] > peer[q0][0]
        primary_held = primary[p1][0] <= primary[p0][0]
        if primary_swept and peer_held:
            events.append(("BEARISH", "PRIMARY_SWEEP_PEER_FAIL", p0, p1, q0, q1, conf))
        if peer_swept and primary_held:
            events.append(("BEARISH", "PEER_SWEEP_PRIMARY_FAIL", p0, p1, q0, q1, conf))
    return events


def gen_series(seed, n=400):
    rng = random.Random(seed)
    primary, peer = [], []
    p, q = 100.0, 100.0
    for _ in range(n):
        shock = rng.gauss(0, 0.6)
        p += shock + rng.gauss(0, 0.25)
        q += shock * 0.85 + rng.gauss(0, 0.30)   # correlated but not identical
        for series, mid in ((primary, p), (peer, q)):
            hi = mid + abs(rng.gauss(0, 0.35))
            lo = mid - abs(rng.gauss(0, 0.35))
            series.append((hi, lo))
    return primary, peer


def run(seeds=200, n=400, warmup=60):
    """Prefix non-repaint check.

    Identity of an event = (direction, type, p0, p1, conf) -- i.e. the primary
    structure and the bar on which it was confirmed. The PEER pair (q0,q1) is
    payload: it drives peerIndex, peerPrice, correlation and confidence.
    """
    disappeared = 0        # event vanished from a later prefix
    peer_remapped = 0      # same event, different peer pair -> confidence/peerIndex repaint
    flipped = 0            # same (p0,p1,conf) emitted with a different type/direction later
    total_events = 0
    examples = []

    for seed in range(seeds):
        primary, peer = gen_series(seed, n)
        first_seen = {}   # key -> (prefix_len, q0, q1)
        for m in range(warmup, n + 1):
            evs = detect(primary[:m], peer[:m])
            present = {}
            for (d, t, p0, p1, q0, q1, conf) in evs:
                present[(d, t, p0, p1, conf)] = (q0, q1)
            for key, qpair in present.items():
                if key not in first_seen:
                    first_seen[key] = (m, qpair[0], qpair[1])
                    total_events += 1
            # check every previously-seen, still-in-window event
            for key, (born, q0b, q1b) in first_seen.items():
                conf = key[4]
                if m - conf > MAX_SIGNAL_AGE:
                    continue          # legitimately aged out of the public window
                if key not in present:
                    disappeared += 1
                    if len(examples) < 6:
                        examples.append(("DISAPPEARED", seed, key, born, m))
                elif present[key] != (q0b, q1b):
                    peer_remapped += 1
                    if len(examples) < 6:
                        examples.append(("PEER_REMAPPED", seed, key, (q0b, q1b), present[key], m))
            # direction/type flip on the same primary structure + confirmation bar
            by_struct = {}
            for (d, t, p0, p1, q0, q1, conf) in evs:
                by_struct.setdefault((p0, p1, conf), set()).add((d, t))
            for key, (born, _, _) in first_seen.items():
                d, t, p0, p1, conf = key
                if m - conf > MAX_SIGNAL_AGE:
                    continue
                got = by_struct.get((p0, p1, conf))
                if got and (d, t) not in got:
                    flipped += 1

    print(f"seeds={seeds} bars={n}  distinct confirmed events observed: {total_events}")
    print(f"  events that DISAPPEARED while still inside maxSignalAgeBars : {disappeared}")
    print(f"  events whose PEER PAIR was re-matched after confirmation    : {peer_remapped}")
    print(f"  events whose direction/type flipped after confirmation      : {flipped}")
    print()
    for e in examples:
        print("  example:", e)


if __name__ == "__main__":
    run()
