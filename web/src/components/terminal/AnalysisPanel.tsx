import { useDesk } from "@/lib/market/desk";
import { getTape } from "@/lib/market/cache";
import { instrumentOf } from "@/lib/market/instruments";
import { formatRMultiple } from "@/lib/market/format";
import { cn } from "@/lib/cn";

export function AnalysisPanel({ onClose }: { onClose?: () => void }) {
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const layers = useDesk((state) => state.layers);
  const toggleLayer = useDesk((state) => state.toggleLayer);
  const setDetail = useDesk((state) => state.setDetail);
  useDesk((state) => state.revision);
  const inst = instrumentOf(symbol);
  const tape = getTape(symbol, timeframe);
  const setup = tape.setup;
  const biasTone =
    tape.bias === "Bullish" ? "text-bull" : tape.bias === "Bearish" ? "text-bear" : "text-fg";

  return (
    <section className="flex h-full min-h-0 flex-col bg-canvas">
      <div className="flex h-11 items-center justify-between px-3">
        <h2 className="text-xs uppercase tracking-wider text-muted">Analysis</h2>
        {onClose ? (
          <button type="button" onClick={onClose} className="h-11 px-2 text-xs text-muted">
            Close
          </button>
        ) : null}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-3 pb-4">
        <p className="border-b border-line-soft py-3 text-sm text-fg">
          {inst.symbol}
          <span className="text-muted"> · {timeframe}</span>
        </p>
        <Stat label="Bias" value={tape.bias} detail={tape.biasDetail} valueClass={biasTone} />
        <Stat
          label="LIT"
          value={setup ? (setup.status === "active" ? "Active" : "Invalid") : "None"}
          detail={
            setup
              ? `${setup.direction === "long" ? "Long" : "Short"} · ${setup.status === "active" ? "in zone" : "stopped"}`
              : "No valid setup"
          }
          valueClass={setup?.status === "active" ? "text-accent" : "text-fg"}
        />
        <Stat label="Liquidity" value={tape.liquidityDetail} />
        <Stat label="Structure" value={tape.structureDetail} />
        <Stat
          label="Entry"
          value={tape.entryDetail}
          detail={setup ? "Unmitigated opposing close" : "Waiting for a sweep"}
          valueClass={tape.entryDetail === "Valid" ? "text-accent" : "text-fg"}
        />
        <Stat
          label="R : R"
          value={setup ? formatRMultiple(setup.rr2) : "—"}
          detail={setup ? "To the external pool" : "Not armed"}
        />
        {setup ? (
          <div className="flex flex-col pt-3">
            <button
              type="button"
              onClick={() => setDetail(true)}
              className="h-11 text-left text-sm text-accent"
            >
              Open thesis
            </button>
            {setup.status === "invalidated" ? (
              <button
                type="button"
                onClick={() => toggleLayer("invalidated")}
                className="h-11 text-left text-sm text-muted"
              >
                {layers.invalidated ? "Hide invalid on chart" : "Show invalid on chart"}
              </button>
            ) : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}

function Stat({
  label,
  value,
  detail,
  valueClass,
}: {
  label: string;
  value: string;
  detail?: string;
  valueClass?: string;
}) {
  return (
    <div className="flex flex-col gap-1 border-b border-line-soft py-3">
      <div className="text-xs uppercase tracking-wider text-muted">{label}</div>
      <div className={cn("text-sm text-fg", valueClass)}>{value}</div>
      {detail ? <div className="text-xs text-muted">{detail}</div> : null}
    </div>
  );
}
