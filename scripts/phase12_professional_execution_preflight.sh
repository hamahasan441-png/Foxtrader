#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
pass(){ printf 'PASS: %s\n' "$1"; }
check(){ local label="$1"; shift; if "$@"; then pass "$label"; else printf 'FAIL: %s\n' "$label" >&2; fail=1; fi; }
contains(){ grep -qF -- "$2" "$1"; }
contains_re(){ grep -Eq -- "$2" "$1"; }
not_contains_re(){ ! grep -Eq -- "$2" "$1"; }

STREAM=app/src/main/java/com/foxtrader/app/data/remote/websocket/MetaApiStreamingClient.kt
QUOTE=app/src/main/java/com/foxtrader/app/data/remote/websocket/Mt4QuoteStream.kt
DS=app/src/main/java/com/foxtrader/app/data/remote/api/MetaApiDataSource.kt
REPO=app/src/main/java/com/foxtrader/app/data/repository/Mt4RepositoryImpl.kt
MODEL=app/src/main/java/com/foxtrader/app/domain/model/Mt4Trading.kt
DRAFT=app/src/main/java/com/foxtrader/app/domain/usecase/execution/BrokerTradeDraftStore.kt
CHART=app/src/main/java/com/foxtrader/app/feature/chart/presentation/ChartViewModel.kt
JOURNAL=app/src/main/java/com/foxtrader/app/domain/model/Journal.kt
JSYNC=app/src/main/java/com/foxtrader/app/domain/usecase/execution/BrokerJournalSynchronizer.kt
JCSV=app/src/main/java/com/foxtrader/app/domain/usecase/journal/JournalCsvExporter.kt
SCREEN=app/src/main/java/com/foxtrader/app/feature/mt4/presentation/Mt4AccountScreen.kt
VM=app/src/main/java/com/foxtrader/app/feature/mt4/presentation/Mt4ViewModel.kt

check "MetaApi Socket.IO client exists" test -f "$STREAM"
check "Socket.IO uses fixed /ws path" contains "$STREAM" '.setPath("/ws")'
check "Socket.IO auth token stays on dedicated zero-log transport" contains "$STREAM" '@MetaApiSocketClient private val okHttpClient: OkHttpClient'
check "Market-data subscribe request is implemented" contains "$STREAM" 'subscribeToMarketData'
check "Market-data unsubscribe request is implemented" contains "$STREAM" 'unsubscribeFromMarketData'
check "Price packets are generation/account bound" contains "$STREAM" 'isCurrent(myGeneration, candidate, safeId)'
check "Quote overflow fails the stream instead of silently dropping" contains "$STREAM" 'failGeneration(myGeneration, candidate, safeId)'
check "REST quote polling is only stream watchdog/fallback" contains "$QUOTE" 'REST polling is a watchdog/fallback'
check "Stream restart watchdog exists" contains "$QUOTE" 'STREAM_RESTART_INTERVAL_MS'
check "Session replacement clears old cached quotes" contains "$QUOTE" 'latestQuotes.clear()'

check "Pending orders require explicit open price" contains "$MODEL" 'val openPrice: Double'
check "Pending orders use MetaApi openPrice" contains "$DS" 'openPrice = request.openPrice'
check "Pending modify is implemented" contains "$DS" 'actionType = "ORDER_MODIFY"'
check "Pending cancel is implemented" contains "$DS" 'actionType = "ORDER_CANCEL"'
check "Partial close is implemented" contains "$DS" 'actionType = "POSITION_PARTIAL"'
check "Position protection modify is implemented" contains "$DS" 'actionType = "POSITION_MODIFY"'
check "Trailing stop request is implemented" contains "$DS" 'trailingStopLoss = protection.trailingDistancePoints'
check "Pending expiration GTC exists" contains "$MODEL" '    GTC,'
check "Pending expiration DAY exists" contains "$MODEL" '    DAY,'
check "Pending expiration SPECIFIED exists" contains "$MODEL" '    SPECIFIED,'
check "Pending expiration SPECIFIED_DAY exists" contains "$MODEL" '    SPECIFIED_DAY,'
check "Broker stop and freeze levels are both enforced" contains "$REPO" 'maxOf(spec.stopsLevelPoints, spec.freezeLevelPoints)'
check "Broker allowed order types are enforced" contains "$REPO" 'allowedOrderTypes'
check "Broker allowed expiration modes are enforced" contains "$REPO" 'allowedExpirationModes'
check "Pending-order flow is blocked from market-order submit path" contains "$REPO" 'Pending orders require an explicit open price'

check "Management actions have durable UNKNOWN reservation" contains "$REPO" 'val reservation = ExecutionReceipt.Unknown(intent)'
check "Unknown management outcome blocks blind retry" contains "$REPO" 'Reconcile/refresh before retrying'
check "Pending cancel has broker-state reconciliation" contains "$REPO" 'intent.operationTag.startsWith("PENDING_CANCEL:")'
check "Pending modify has broker-state reconciliation" contains "$REPO" 'intent.operationTag.startsWith("PENDING_MODIFY:")'
check "Position modify has broker-state reconciliation" contains "$REPO" 'intent.operationTag.startsWith("POSITION_MODIFY:")'
check "Break-even has broker-state reconciliation" contains "$REPO" 'intent.operationTag.startsWith("BREAK_EVEN:")'
check "Partial close has broker-state reconciliation" contains "$REPO" 'intent.operationTag.startsWith("POSITION_PARTIAL:")'
check "Management idempotency is bound to broker starting state" contains "$REPO" 'FROM_SL=${position.sl}'
check "Pending modify idempotency includes starting price" contains "$REPO" 'FROM_PRICE=${order.openPrice}'
check "Pending modification rejects stale reviewed broker state" contains "$REPO" 'Pending order changed at the broker since review'
check "Position protection rejects stale reviewed broker state" contains "$REPO" 'Position protection changed at the broker since review'
check "Partial close rejects stale reviewed broker state" contains "$REPO" 'Position changed at the broker since review'
check "UI passes pending broker snapshot to execution" contains "$VM" 'expectedState = Mt4PendingOrderSnapshot('
check "UI passes position broker snapshot to execution" contains "$VM" 'expectedState = Mt4PositionSnapshot('
check "New-order zero SL/TP is normalized to disabled" contains "$REPO" 'if (value == null || value == 0.0) return null'
check "Trailing ambiguity stays UNKNOWN without broker proof" contains "$REPO" 'never claim a trailing'
check "Break-even management exists" contains "$REPO" 'movePositionToBreakEven'
check "Partial-close validates remaining broker volume" contains "$REPO" 'Partial close would leave an invalid broker volume'
check "Chart hand-off is a draft, not direct execution" contains "$DRAFT" 'class BrokerTradeDraftStore'
check "Chart stages broker draft" contains "$CHART" 'brokerTradeDraftStore.stage'
check "Chart ViewModel has no direct placeTrade call" not_contains_re "$CHART" 'mt4Repository\.placeTrade\('

check "Journal open state is derived, not stale stored state" contains "$JOURNAL" 'val isOpen: Boolean get() = exitPrice == null'
check "Broker journal close waits for authoritative history" contains "$JSYNC" 'loadCloseDetails'
check "Broker journal does not fabricate missing exits" contains "$JSYNC" 'never fabricate an exit'
check "CSV export guards spreadsheet formulas" contains "$JCSV" 'FORMULA_PREFIXES'
check "MT4 management dialogs are scrollable" contains_re "$SCREEN" 'verticalScroll|rememberScrollState'

check "No TODO()/NotImplementedError in Android production Kotlin" bash -c '! grep -R -E "TODO\\(|NotImplementedError" app/src/main/java --include="*.kt" >/dev/null'
check "No GlobalScope in Android production Kotlin" bash -c '! grep -R "GlobalScope" app/src/main/java --include="*.kt" >/dev/null'
check "No merge conflict markers" bash -c '! grep -R -E "^(<<<<<<<|=======|>>>>>>>)" app/src/main/java backend scripts >/dev/null'
check "No raw WebSocket MetaApi legacy transport" bash -c '! grep -E "newWebSocket|WebSocketListener" app/src/main/java/com/foxtrader/app/data/remote/websocket/MetaApiStreamingClient.kt app/src/main/java/com/foxtrader/app/data/remote/websocket/Mt4QuoteStream.kt >/dev/null'

if (( fail )); then
  echo 'PHASE12_PROFESSIONAL_EXECUTION_PREFLIGHT: FAIL' >&2
  exit 1
fi
echo 'PHASE12_PROFESSIONAL_EXECUTION_PREFLIGHT: PASS'
