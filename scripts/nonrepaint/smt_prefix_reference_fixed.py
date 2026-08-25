"""Verify the proposed causal fix for synchronizedPairs."""
import smt_prefix_reference as T

def synchronized_pairs_causal(primary_swings, peer_swings, max_sync):
    """Earliest-confirmable match: among peer pairs satisfying the sync
    constraint, take the one with the SMALLEST q1. The peer swing list grows
    monotonically under prefix extension and no swing is ever inserted between
    two already-detected swings, so the minimum-q1 candidate can never be
    replaced by a later-arriving one. Selection is therefore stable."""
    if len(primary_swings) < 2 or len(peer_swings) < 2:
        return []
    result = []
    for pi in range(1, len(primary_swings)):
        p0, p1 = primary_swings[pi-1], primary_swings[pi]
        best = None
        for qi in range(1, len(peer_swings)):
            q0, q1 = peer_swings[qi-1], peer_swings[qi]
            if abs(p0-q0) > max_sync or abs(p1-q1) > max_sync:
                continue
            if best is None or q1 < best[3]:
                best = (p0, p1, q0, q1)
        if best is not None:
            result.append(best)
    seen, out = set(), []
    for p in result:
        if p not in seen:
            seen.add(p); out.append(p)
    return out

T.synchronized_pairs = synchronized_pairs_causal
T.run()
