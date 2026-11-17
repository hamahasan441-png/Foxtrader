import { useState } from "react";
import { Search } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { getTape } from "@/lib/market/cache";
import { changeOf, lastOf } from "@/lib/market/engine";
import { INSTRUMENTS } from "@/lib/market/instruments";
import { formatPct, formatPx } from "@/lib/market/format";
import { cn } from "@/lib/cn";

export function Watchlist({ onClose }: { onClose?: () => void }) {
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const setSymbol = useDesk((state) => state.setSymbol);
  useDesk((state) => state.revision);
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const rows = INSTRUMENTS.filter(
    (inst) => !q || inst.symbol.toLowerCase().includes(q) || inst.name.toLowerCase().includes(q),
  );

  return (
    <section className="flex h-full min-h-0 flex-col bg-canvas">
      <div className="flex h-11 items-center justify-between px-3">
        <h2 className="text-xs uppercase tracking-wider text-muted">Markets</h2>
        {onClose ? (
          <button type="button" onClick={onClose} className="h-11 px-2 text-xs text-muted">
            Close
          </button>
        ) : null}
      </div>
      <label className="flex h-11 items-center gap-2 border-y border-line-soft px-3">
        <Search className="size-4 shrink-0 text-faint" strokeWidth={1.5} />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search"
          aria-label="Search markets"
          className="h-11 w-full bg-transparent text-sm text-fg outline-none placeholder:text-faint"
        />
      </label>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {rows.length === 0 ? (
          <p className="px-3 py-6 text-sm text-muted">No market matches.</p>
        ) : (
          rows.map((inst) => {
            const tape = getTape(inst.symbol, timeframe);
            const last = lastOf(tape);
            const pct = changeOf(tape);
            const active = inst.symbol === symbol;
            return (
              <button
                key={inst.symbol}
                type="button"
                onClick={() => setSymbol(inst.symbol)}
                className={cn(
                  "relative flex h-11 w-full items-center gap-2 px-3 text-left transition-colors duration-150",
                  active ? "bg-raised" : "hover:bg-raised",
                )}
              >
                {active ? <span className="absolute inset-y-0 left-0 w-0.5 bg-accent" /> : null}
                <span className="w-16 shrink-0 text-sm font-medium">{inst.symbol}</span>
                <span className="ml-auto font-mono text-xs tabular-nums text-fg">
                  {formatPx(last, inst.digits)}
                </span>
                <span
                  className={cn(
                    "w-14 shrink-0 text-right font-mono text-xs tabular-nums",
                    pct >= 0 ? "text-bull" : "text-bear",
                  )}
                >
                  {formatPct(pct)}
                </span>
              </button>
            );
          })
        )}
      </div>
    </section>
  );
}
