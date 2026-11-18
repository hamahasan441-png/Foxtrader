import { ChevronDown, ChevronUp } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { FILLS, netR } from "@/lib/market/blotter";
import { instrumentOf } from "@/lib/market/instruments";
import { formatPx, formatSignedR, formatUtc } from "@/lib/market/format";
import { cn } from "@/lib/cn";

export function BlotterBar() {
  const open = useDesk((state) => state.blotterOpen);
  const setOpen = useDesk((state) => state.setBlotterOpen);
  const net = netR();
  return (
    <div className="shrink-0 border-t border-line bg-canvas">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex h-11 w-full items-center gap-3 px-3 text-left"
        aria-expanded={open}
      >
        {open ? (
          <ChevronDown className="size-4 text-muted" strokeWidth={1.5} />
        ) : (
          <ChevronUp className="size-4 text-muted" strokeWidth={1.5} />
        )}
        <span className="text-xs uppercase tracking-wider text-muted">Sample blotter</span>
        <span className="text-sm text-fg">{FILLS.length} fills</span>
        <span className={cn("font-mono text-sm tabular-nums", net >= 0 ? "text-bull" : "text-bear")}>
          {formatSignedR(net)}
        </span>
      </button>
      {open ? (
        <div className="max-h-40 overflow-auto border-t border-line-soft">
          <FillTable />
        </div>
      ) : null}
    </div>
  );
}

export function FillTable({ onPick }: { onPick?: () => void }) {
  const setSymbol = useDesk((state) => state.setSymbol);
  return (
    <table className="w-full border-collapse text-left whitespace-nowrap">
      <thead className="text-xs uppercase tracking-wider text-muted">
        <tr className="border-b border-line-soft">
          <th className="px-3 py-2 font-medium">Time</th>
          <th className="px-3 py-2 font-medium">Market</th>
          <th className="px-3 py-2 font-medium">Side</th>
          <th className="px-3 py-2 text-right font-medium">Entry</th>
          <th className="px-3 py-2 text-right font-medium">Exit</th>
          <th className="px-3 py-2 text-right font-medium">R</th>
        </tr>
      </thead>
      <tbody>
        {FILLS.map((fill) => {
          const digits = instrumentOf(fill.symbol).digits;
          return (
            <tr key={fill.id} className="border-b border-line-soft last:border-0">
              <td className="px-3 py-2 font-mono text-xs tabular-nums text-muted">
                {formatUtc(fill.time)}
              </td>
              <td className="px-3 py-2">
                <button
                  type="button"
                  className="h-8 text-sm text-fg"
                  onClick={() => {
                    setSymbol(fill.symbol);
                    onPick?.();
                  }}
                >
                  {fill.symbol}
                </button>
              </td>
              <td className={cn("px-3 py-2 text-sm", fill.side === "Buy" ? "text-bull" : "text-bear")}>
                {fill.side}
              </td>
              <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">
                {formatPx(fill.entry, digits)}
              </td>
              <td className="px-3 py-2 text-right font-mono text-xs tabular-nums">
                {formatPx(fill.exit, digits)}
              </td>
              <td
                className={cn(
                  "px-3 py-2 text-right font-mono text-xs tabular-nums",
                  fill.r >= 0 ? "text-bull" : "text-bear",
                )}
              >
                {formatSignedR(fill.r)}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
