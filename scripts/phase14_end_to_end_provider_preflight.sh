#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
require_file() { [[ -f "$1" ]] || fail "Missing $1"; }
require_grep() { local pattern="$1" file="$2" label="$3"; grep -Eq "$pattern" "$file" || fail "$label"; pass "$label"; }
reject_grep() { local pattern="$1" file="$2" label="$3"; if grep -Eq "$pattern" "$file"; then fail "$label"; else pass "$label"; fi; }

PROVIDER='app/src/main/java/com/foxtrader/app/domain/model/DataProvider.kt'
DERIV_DS='app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivMarketDataSource.kt'
DERIV_WS='app/src/main/java/com/foxtrader/app/data/remote/deriv/DerivWebSocketClient.kt'
DERIV_CHART_WS='app/src/main/java/com/foxtrader/app/data/remote/websocket/DerivMarketWebSocket.kt'
PROVIDER_WS='app/src/main/java/com/foxtrader/app/data/remote/websocket/ProviderMarketWebSocket.kt'
ROUTER='app/src/main/java/com/foxtrader/app/domain/usecase/marketdata/MarketProviderRouter.kt'
MARKET_REPO='app/src/main/java/com/foxtrader/app/data/repository/MarketRepositoryImpl.kt'
DAO='app/src/main/java/com/foxtrader/app/data/local/dao/CandleDao.kt'
MT4_REPO='app/src/main/java/com/foxtrader/app/domain/repository/Mt4Repository.kt'
META_DS='app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiDataSource.kt'
CHART_STATE='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartUiState.kt'
CHART_SCREEN='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartScreen.kt'
CHART_VM='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt'
CHART_DATA='app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartDataController.kt'
PREFS='app/src/main/java/com/foxtrader/app/domain/usecase/preferences/AppPreferences.kt'
SETTINGS_VM='app/src/main/java/com/foxtrader/app/feature/settings/presentation/SettingsViewModel.kt'

for f in "$PROVIDER" "$DERIV_DS" "$DERIV_WS" "$DERIV_CHART_WS" "$PROVIDER_WS" "$ROUTER" "$MARKET_REPO" "$DAO" "$MT4_REPO" "$META_DS" "$CHART_STATE" "$CHART_SCREEN" "$CHART_VM" "$CHART_DATA" "$PREFS" "$SETTINGS_VM"; do require_file "$f"; done

require_grep 'DERIV\("Deriv", supportsLive = true, requiresApiKey = false, implemented = true\)' "$PROVIDER" 'Deriv is an implemented live chart provider'
require_grep 'private val ws = DerivWebSocketClient\(' "$DERIV_DS" 'Deriv chart traffic owns a dedicated public WebSocket client'
require_grep 'private val connectMutex = Mutex\(\)' "$DERIV_DS" 'Deriv public connection establishment is serialized'
require_grep 'frx\$\{MarketSymbolClassifier\.canonicalSymbol\(trimmed\)\}' "$DERIV_DS" 'Deriv FX aliases map to native frx symbols'
require_grep 'const val PUBLIC_WS = "wss://api\.derivws\.com/trading/v1/options/ws/public"' "$DERIV_WS" 'Deriv public WebSocket endpoint is current and exact'
require_grep 'class DerivMarketWebSocket' "$DERIV_CHART_WS" 'Deriv live chart adapter exists'
require_grep 'private val derivMarketWebSocket: DerivMarketWebSocket' "$PROVIDER_WS" 'Deriv live adapter is wired into provider router'
require_grep 'RouteBinding\(' "$PROVIDER_WS" 'Live routing keeps route identity separate from provider-native symbol'
require_grep 'symbol = binding\.requestedSymbol' "$PROVIDER_WS" 'Chart receives the exact requested provider-native symbol'
require_grep 'provider = binding\.provider' "$PROVIDER_WS" 'Chart tick provenance keeps the selected provider identity'
require_grep 'return preferred' "$ROUTER" 'Historical routing is strict to the selected provider'
require_grep 'return if \(providerSupportsAsset\(preferred, asset\)\) preferred else null' "$ROUTER" 'Live routing never silently substitutes another provider'
require_grep 'DataProvider\.DERIV,' "$ROUTER" 'Deriv native symbols bypass generic asset rejection'
require_grep 'DataProvider\.MT4 -> true' "$ROUTER" 'MetaTrader native broker symbols bypass generic asset rejection'
reject_grep 'fetchDefaultCandles' "$MARKET_REPO" 'Legacy cross-provider default fetch helpers are removed'
require_grep 'selectedProvider == DataProvider\.DERIV' "$MARKET_REPO" 'Repository has explicit Deriv history path'
require_grep 'selectedProvider == DataProvider\.MT4' "$MARKET_REPO" 'Repository has explicit MetaTrader history path'
require_grep 'ensureProviderUnchanged\(requestedProvider\)' "$MARKET_REPO" 'In-flight provider changes discard stale responses'
require_grep 'dao\.replaceSeries\(' "$MARKET_REPO" 'Provider refresh replaces the whole cached series instead of merging venues'
require_grep 'override suspend fun clearMarketDataCache\(' "$MARKET_REPO" 'Provider-switch cache purge is implemented'
require_grep 'suspend fun replaceSeries\(' "$DAO" 'Room candle DAO supports atomic series replacement'
require_grep 'DELETE FROM candles' "$DAO" 'Room candle DAO supports complete provider-switch purge'
require_grep 'getHistoricalCandlesBefore\(' "$MT4_REPO" 'MetaTrader older-history contract exists'
require_grep 'startTime = Instant\.ofEpochMilli' "$META_DS" 'MetaApi older-history paging uses an explicit startTime boundary'
require_grep 'val dataProvider: DataProvider' "$CHART_STATE" 'Chart state owns selected provider identity'
require_grep '"DERIV" to implemented\.filter' "$CHART_SCREEN" 'Chart provider menu separates Deriv'
require_grep '"METATRADER" to implemented\.filter' "$CHART_SCREEN" 'Chart provider menu separates MetaTrader'
require_grep '"OTHER DATA PROVIDERS" to implemented\.filter' "$CHART_SCREEN" 'Chart provider menu separates all other feeds'
require_grep 'fun onDataProviderChange\(' "$CHART_VM" 'Chart supports explicit provider switching'
# Two purge calls around preference mutation are intentionally required by design.
[[ "$(grep -c 'repository\.clearMarketDataCache()' "$CHART_VM")" -ge 2 ]] || fail 'Chart provider switch must purge candle cache before and after source mutation'
pass 'Chart provider switch purges stale provider cache'
require_grep 'historyContextGeneration' "$CHART_DATA" 'Chart history requests are generation-bound'
require_grep 'historyContextMatches\(' "$CHART_DATA" 'Stale symbol/timeframe/provider history completions are ignored'
require_grep 'if \(provider == DataProvider\.MT4\) setMetaApiToken\(normalizedKey\)' "$PREFS" 'Generic MT4 provider key stays synchronized with dedicated MetaApi token'
require_grep 'marketRepository\.clearMarketDataCache\(\)' "$SETTINGS_VM" 'Settings provider switch purges stale candle cache'

if rg -n --glob '*.kt' 'TODO\(\)|NotImplementedError|GlobalScope' app/src/main/java >/dev/null; then
  fail 'Production Kotlin contains TODO()/NotImplementedError/GlobalScope'
else
  pass 'No TODO()/NotImplementedError/GlobalScope in Android production Kotlin'
fi
if rg -n '^(<<<<<<<|=======|>>>>>>>)' . --glob '!*.md' --glob '!*.txt' >/dev/null; then
  fail 'Merge conflict markers found'
else
  pass 'No merge conflict markers'
fi

printf 'PHASE14_END_TO_END_PROVIDER_PREFLIGHT: PASS\n'
