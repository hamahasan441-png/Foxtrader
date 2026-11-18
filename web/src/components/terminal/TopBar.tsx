import { useEffect, useState } from "react";
import { Settings } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { instrumentOf, sessionState, TIMEFRAMES } from "@/lib/market/instruments";
import { formatClock } from "@/lib/market/format";
import type { TimeframeId } from "@/lib/market/types";
import { cn } from "@/lib/cn";

export function TopBar() {
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const paused = useDesk((state) => state.paused);
  const feed = useDesk((state) => state.feed);
  const setTimeframe = useDesk((state) => state.setTimeframe);
  const setSettings = useDesk((state) => state.setSettings);
  const setMobile = useDesk((state) => state.setMobile);
  const inst = instrumentOf(symbol);
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    const update = () => setNow(new Date());
    update();
    const id = window.setInterval(update, 1000);
    return () => window.clearInterval(id);
  }, []);

  const session = now ? sessionState(inst.cls, now) : null;

  const openMarkets = () => {
    if (window.matchMedia("(min-width: 1024px)").matches) return;
    setMobile("markets");
  };

  return (
    <header className="flex shrink-0 flex-col border-b border-line bg-canvas md:h-12 md:flex-row md:items-center">
      <div className="flex h-12 min-w-0 items-center gap-2 px-2 md:px-3">
        <div className="flex shrink-0 items-center gap-2 pr-1">
          <Mark />
          <span className="text-sm font-medium tracking-wide">FOXTRADER</span>
        </div>
        <span className="hidden h-4 w-px bg-line sm:block" />
        <button
          type="button"
          onClick={openMarkets}
          className="flex h-11 min-w-0 items-center gap-2 px-1 lg:pointer-events-none"
        >
          <span className="text-sm font-medium">{inst.symbol}</span>
          <span className="hidden truncate text-sm text-muted sm:inline">{inst.name}</span>
        </button>
        <div className="ml-auto flex items-center gap-2 md:ml-2">
          <Status
            dot={session?.open ? "accent" : "faint"}
            label={session ? session.label : "—"}
          />
          <Status
            dot={feed === "live" ? "bull" : feed === "down" ? "bear" : "faint"}
            label={paused ? "Paused" : feed === "live" ? "Dukascopy connected" : feed === "down" ? "Not connected" : "Connecting"}
          />
          <span className="hidden font-mono text-xs tabular-nums text-muted lg:inline">
            {now ? formatClock(now) : ""}
          </span>
          <button
            type="button"
            aria-label="Settings"
            onClick={() => setSettings(true)}
            className="flex size-11 items-center justify-center text-muted hover:text-fg"
          >
            <Settings className="size-4" strokeWidth={1.5} />
          </button>
        </div>
      </div>
      <div
        role="tablist"
        aria-label="Timeframe"
        className="flex h-11 items-center gap-1 overflow-x-auto px-2 md:ml-auto md:h-12 md:px-3"
      >
        {TIMEFRAMES.map((frame) => {
          const on = frame.id === timeframe;
          return (
            <button
              key={frame.id}
              type="button"
              role="tab"
              aria-selected={on}
              onClick={() => setTimeframe(frame.id as TimeframeId)}
              className={cn(
                "h-11 shrink-0 px-3 font-mono text-sm tabular-nums md:h-8",
                on ? "text-fg" : "text-faint hover:text-muted",
              )}
            >
              {frame.label}
            </button>
          );
        })}
      </div>
    </header>
  );
}

function Mark() {
  return (
    <svg viewBox="0 0 16 16" className="size-4 text-fg" aria-hidden="true">
      <path d="M8 1.5v13" stroke="currentColor" strokeWidth="1.25" />
      <path d="M8 8h6" stroke="var(--color-accent)" strokeWidth="1.25" />
    </svg>
  );
}

function Status({ dot, label }: { dot: "accent" | "bull" | "bear" | "faint"; label: string }) {
  return (
    <span className="flex h-11 items-center gap-1.5 text-xs text-muted">
      <span
        className={cn(
          "size-1.5 rounded-full",
          dot === "accent" && "bg-accent",
          dot === "bull" && "bg-bull",
          dot === "bear" && "bg-bear",
          dot === "faint" && "bg-faint",
        )}
      />
      {label}
    </span>
  );
}
