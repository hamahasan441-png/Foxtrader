import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { getTape } from "@/lib/market/cache";
import { instrumentOf } from "@/lib/market/instruments";
import { distanceLabel, formatCompact, formatPx, formatRMultiple } from "@/lib/market/format";
import { cn } from "@/lib/cn";

export function SetupDetail() {
  const open = useDesk((state) => state.detailOpen);
  const setOpen = useDesk((state) => state.setDetail);
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const inst = instrumentOf(symbol);
  const setup = getTape(symbol, timeframe).setup;
  const [equity, setEquity] = useState("250000");
  const [risk, setRisk] = useState("0.25");

  const equityN = Number(equity);
  const riskN = Number(risk);
  const riskAmount =
    Number.isFinite(equityN) && Number.isFinite(riskN) ? equityN * (riskN / 100) : Number.NaN;
  const dist = setup ? Math.abs(setup.entry - setup.stop) : 0;
  const units = setup && dist > 0 && Number.isFinite(riskAmount) ? riskAmount / dist : Number.NaN;
  const notional = Number.isFinite(units) && setup ? units * setup.entry : Number.NaN;

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-canvas/75" />
        <Dialog.Content className="fixed inset-3 z-50 mx-auto max-w-xl overflow-y-auto border border-line bg-surface p-4 focus:outline-none sm:inset-y-12">
          <div className="flex items-start justify-between gap-3">
            <div>
              <Dialog.Title className="text-base font-medium text-fg">
                {inst.symbol} {setup ? (setup.direction === "long" ? "long" : "short") : "thesis"}
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-muted">
                {setup
                  ? setup.status === "active"
                    ? "Thesis, checklist, and size. Kept off the candles."
                    : "Invalidated. Hidden on the chart unless you show it."
                  : "This market has no armed setup."}
              </Dialog.Description>
            </div>
            <Dialog.Close className="flex size-11 items-center justify-center text-muted hover:text-fg" aria-label="Close">
              <X className="size-4" strokeWidth={1.5} />
            </Dialog.Close>
          </div>

          {setup ? (
            <div className="mt-4 flex flex-col gap-4">
              <p className="text-sm text-pretty text-fg">{setup.thesis}</p>
              <LevelTable
                digits={inst.digits}
                rows={[
                  ["Entry", setup.entry],
                  ["Zone low", setup.entryLow],
                  ["Zone high", setup.entryHigh],
                  ["Stop", setup.stop],
                  ["Sweep", setup.sweep],
                  ["TP1", setup.tp1],
                  ["TP2", setup.tp2],
                  ["TP3", setup.tp3],
                ]}
              />
              <div>
                <h3 className="text-xs uppercase tracking-wider text-muted">Confirmation</h3>
                <ul className="mt-2 flex flex-col">
                  {setup.checklist.map((item) => (
                    <li key={item.label} className="flex items-start gap-2 border-b border-line-soft py-2">
                      <span className={cn("mt-1.5 size-1.5 shrink-0", item.ok ? "bg-bull" : "bg-bear")} />
                      <span className="text-sm text-fg">{item.label}</span>
                    </li>
                  ))}
                </ul>
              </div>
              <p className="text-sm text-fg">
                <span className="text-muted">Invalidation. </span>
                {setup.invalidation}
              </p>
              <div>
                <h3 className="text-xs uppercase tracking-wider text-muted">Risk</h3>
                <div className="mt-2 grid grid-cols-2 gap-3">
                  <label className="text-xs text-muted">
                    Equity
                    <input
                      value={equity}
                      inputMode="decimal"
                      onChange={(event) => setEquity(event.target.value)}
                      className="mt-1 h-11 w-full border border-line bg-canvas px-2 font-mono text-sm text-fg outline-none"
                    />
                  </label>
                  <label className="text-xs text-muted">
                    Risk %
                    <input
                      value={risk}
                      inputMode="decimal"
                      onChange={(event) => setRisk(event.target.value)}
                      className="mt-1 h-11 w-full border border-line bg-canvas px-2 font-mono text-sm text-fg outline-none"
                    />
                  </label>
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-y-2 text-sm">
                  <dt className="text-muted">Amount</dt>
                  <dd className="text-right font-mono tabular-nums">
                    {Number.isFinite(riskAmount) ? formatCompact(riskAmount) : "—"}
                  </dd>
                  <dt className="text-muted">Stop</dt>
                  <dd className="text-right font-mono tabular-nums">{distanceLabel(inst, setup.entry, setup.stop)}</dd>
                  <dt className="text-muted">Units</dt>
                  <dd className="text-right font-mono tabular-nums">
                    {Number.isFinite(units) ? formatCompact(units) : "—"}
                  </dd>
                  <dt className="text-muted">Notional</dt>
                  <dd className="text-right font-mono tabular-nums">
                    {Number.isFinite(notional) ? formatCompact(notional) : "—"}
                  </dd>
                  <dt className="text-muted">TP1 / TP2 / TP3</dt>
                  <dd className="text-right font-mono tabular-nums">
                    {formatRMultiple(setup.rr1).replace("1 : ", "")} / {formatRMultiple(setup.rr2).replace("1 : ", "")} /{" "}
                    {formatRMultiple(setup.rr3).replace("1 : ", "")}
                  </dd>
                </dl>
                <p className="mt-3 text-xs text-muted">
                  Size is indicative. The stop is structural, not a promise of fill.
                </p>
              </div>
            </div>
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function LevelTable({ digits, rows }: { digits: number; rows: [string, number][] }) {
  return (
    <table className="w-full text-sm">
      <tbody>
        {rows.map(([label, price]) => (
          <tr key={label} className="border-b border-line-soft">
            <th className="py-1.5 text-left font-normal text-muted">{label}</th>
            <td className="py-1.5 text-right font-mono tabular-nums text-fg">{formatPx(price, digits)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
