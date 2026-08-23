from pathlib import Path

path = Path("app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt")
text = path.read_text()
old = '''        _uiState.value = latest.withReplayComputation(
            replayCandles = candles,
            computation = computation,
            toggles = ind,
            signals = chartSignals,
            litXAnalysis = litXAnalysis,
'''
new = '''        _uiState.value = latest.withReplayComputation(
            replayCandles = candles,
            computation = computation,
            toggles = ind,
            signals = chartSignals,
            barMode = current.barMode,
            litXAnalysis = litXAnalysis,
'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"replay mapper call: expected 1 match, got {count}")
path.write_text(text.replace(old, new, 1))
print("Replay chart mode mapping patched")
