from pathlib import Path

viewport_path = Path("app/src/main/java/com/foxtrader/app/feature/chart/presentation/components/ChartViewport.kt")
test_path = Path("app/src/test/java/com/foxtrader/app/feature/chart/presentation/components/ChartViewportTest.kt")

viewport = viewport_path.read_text()
original = viewport

old_bound = "        val maxStart = max(0f, total - visibleBars)"
count = viewport.count(old_bound)
if count != 2:
    raise SystemExit(f"Expected 2 viewport max-bound sites, found {count}")
viewport = viewport.replace(old_bound, "        val maxStart = maxFutureStartIndex(total)")

old_reset = "        startIndex = max(0f, total - visibleBars)\n        stopFling()"
new_reset = "        startIndex = latestPinnedStartIndex(total)\n        stopFling()"
if old_reset not in viewport:
    raise SystemExit("resetToLatest anchor not found")
viewport = viewport.replace(old_reset, new_reset, 1)

old_edge = '''    /** Whether the right edge of the viewport is pinned to the newest bar. */
    fun isAtRightEdge(total: Int, toleranceBars: Float = 1f): Boolean =
        startIndex + visibleBars >= total - toleranceBars
'''
new_edge = '''    /**
     * Whether the camera is in the normal live-follow position.
     *
     * Panning into the deliberate future-space area must turn live-follow off;
     * otherwise a new tick would snap the latest candle back to the right edge
     * while the trader is inspecting it near the middle of the screen.
     */
    fun isAtRightEdge(total: Int, toleranceBars: Float = 1f): Boolean =
        abs(startIndex - latestPinnedStartIndex(total)) <= toleranceBars
'''
if old_edge not in viewport:
    raise SystemExit("isAtRightEdge block not found")
viewport = viewport.replace(old_edge, new_edge, 1)

anchor = '''    fun shiftForPrependedBars(prependedCount: Int) {
        if (prependedCount <= 0) return
        startIndex += prependedCount
    }

    // ========================================================================
    // AUTO-SCALE
'''
replacement = '''    fun shiftForPrependedBars(prependedCount: Int) {
        if (prependedCount <= 0) return
        startIndex += prependedCount
    }

    /** Normal live-follow position: newest data sits at the right edge. */
    private fun latestPinnedStartIndex(total: Int): Float =
        max(0f, total.toFloat() - visibleBars)

    /**
     * Maximum pan into empty future space. At this bound the newest candle is
     * exactly at the horizontal centre of the chart, giving the trader room to
     * inspect price action without permitting unbounded empty scrolling.
     */
    private fun maxFutureStartIndex(total: Int): Float {
        if (total <= 0) return 0f
        val latestIndex = (total - 1).toFloat()
        return max(0f, latestIndex - visibleBars * LAST_CANDLE_CENTER_FRACTION)
    }

    // ========================================================================
    // AUTO-SCALE
'''
if anchor not in viewport:
    raise SystemExit("prepend/AUTO-SCALE anchor not found")
viewport = viewport.replace(anchor, replacement, 1)

const_anchor = '''        /** Default window on first layout / after "go to now". */
        const val DEFAULT_VISIBLE_BARS = 80f
'''
const_replacement = '''        /** Default window on first layout / after "go to now". */
        const val DEFAULT_VISIBLE_BARS = 80f

        /** Furthest allowed future-space pan places the newest candle at 50% width. */
        const val LAST_CANDLE_CENTER_FRACTION = 0.5f
'''
if const_anchor not in viewport:
    raise SystemExit("viewport constant anchor not found")
viewport = viewport.replace(const_anchor, const_replacement, 1)

if viewport == original:
    raise SystemExit("ChartViewport was not modified")
viewport_path.write_text(viewport)

# Existing hard-bound tests must reflect the new, intentional future-space limit.
tests = test_path.read_text()
for old, new in (
    ("fun `clamp prevents scrolling past the newest bar`()", "fun `clamp stops when newest candle reaches chart centre`()"),
    ("assertEquals(400f, vp.startIndex, 0.001f)", "assertEquals(449f, vp.startIndex, 0.001f)"),
    ("assertEquals(900f, vp.startIndex, 0.001f)", "assertEquals(949f, vp.startIndex, 0.001f)"),
):
    if old not in tests:
        raise SystemExit(f"Expected test anchor not found: {old}")
    tests = tests.replace(old, new, 1)
test_path.write_text(tests)

print("Applied bounded future-space chart camera patch")
