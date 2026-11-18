import { useEffect, type ReactNode } from "react";
import { BookOpen, CandlestickChart, List } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { INSTRUMENTS, TIMEFRAMES } from "@/lib/market/instruments";
import type { TimeframeId } from "@/lib/market/types";
import { cn } from "@/lib/cn";
import { TopBar } from "./TopBar";
import { Watchlist } from "./Watchlist";
import { ChartPane } from "./ChartPane";
import { AnalysisPanel } from "./AnalysisPanel";
import { BlotterBar, FillTable } from "./Blotter";
import { SetupDetail } from "./SetupDetail";
import { SettingsDialog } from "./SettingsDialog";

const TF_KEYS: Record<string, TimeframeId> = {
  "1": "M1",
  "2": "M5",
  "3": "M15",
  "4": "H1",
  "5": "H4",
  "6": "D1",
};

export function Terminal() {
  const mobile = useDesk((state) => state.mobile);
  const setMobile = useDesk((state) => state.setMobile);
  const refresh = useDesk((state) => state.refresh);
  const symbol = useDesk((state) => state.symbol);
  const timeframe = useDesk((state) => state.timeframe);
  const setTimeframe = useDesk((state) => state.setTimeframe);
  const setDetail = useDesk((state) => state.setDetail);
  const hydrate = useDesk((state) => state.hydrate);

  useEffect(() => {
    try {
      const raw = localStorage.getItem("meridian-desk");
      if (!raw) return;
      const saved = JSON.parse(raw) as {
        symbol?: string;
        timeframe?: TimeframeId;
        layers?: Record<string, boolean>;
        blotterOpen?: boolean;
      };
      if (saved.symbol && !INSTRUMENTS.some((item) => item.symbol === saved.symbol)) return;
      if (saved.timeframe && !TIMEFRAMES.some((frame) => frame.id === saved.timeframe)) return;
      hydrate({
        symbol: saved.symbol,
        timeframe: saved.timeframe,
        layers: saved.layers as never,
        blotterOpen: saved.blotterOpen,
      });
    } catch {
      /* ignore broken local state */
    }
  }, [hydrate]);

  useEffect(() => {
    return useDesk.subscribe((state) => {
      localStorage.setItem(
        "meridian-desk",
        JSON.stringify({
          symbol: state.symbol,
          timeframe: state.timeframe,
          layers: state.layers,
          blotterOpen: state.blotterOpen,
        }),
      );
    });
  }, []);

  useEffect(() => {
    void refresh(symbol, timeframe);
    const id = window.setInterval(() => {
      if (!useDesk.getState().paused) void refresh(symbol, timeframe);
    }, 20_000);
    return () => window.clearInterval(id);
  }, [refresh, symbol, timeframe]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target;
      if (target instanceof HTMLElement && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) {
        return;
      }
      const next = TF_KEYS[event.key];
      if (next) setTimeframe(next);
      if (event.key === "Escape") setDetail(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [setDetail, setTimeframe]);

  return (
    <div className="flex h-dvh flex-col overflow-hidden bg-canvas text-fg">
      <h1 className="sr-only">Foxtrader</h1>
      <TopBar />
      <div className="flex min-h-0 flex-1">
        <aside className="hidden w-60 shrink-0 border-r border-line lg:flex">
          <Watchlist />
        </aside>
        <ChartPane />
        <aside className="hidden w-72 shrink-0 border-l border-line md:flex">
          <AnalysisPanel />
        </aside>
      </div>
      <div className="hidden md:block">
        <BlotterBar />
      </div>
      <nav className="flex h-12 shrink-0 border-t border-line md:hidden">
        <NavButton label="Markets" onClick={() => setMobile("markets")} icon={<List className="size-4" strokeWidth={1.5} />} />
        <NavButton
          label="Indicators"
          onClick={() => setMobile("analysis")}
          icon={<BookOpen className="size-4" strokeWidth={1.5} />}
        />
        <NavButton
          label="Blotter"
          onClick={() => setMobile("blotter")}
          icon={<CandlestickChart className="size-4" strokeWidth={1.5} />}
        />
      </nav>

      <Drawer open={mobile === "markets"} title="Markets" side="left" onClose={() => setMobile(null)}>
        <Watchlist onClose={() => setMobile(null)} />
      </Drawer>
      <Drawer open={mobile === "analysis"} title="Indicators" side="right" onClose={() => setMobile(null)}>
        <AnalysisPanel onClose={() => setMobile(null)} />
      </Drawer>
      <Drawer open={mobile === "blotter"} title="Blotter" side="bottom" onClose={() => setMobile(null)}>
        <div className="flex h-11 shrink-0 items-center justify-between border-b border-line-soft px-3">
          <h2 className="text-xs uppercase tracking-wider text-muted">Blotter</h2>
          <button type="button" onClick={() => setMobile(null)} className="h-11 px-2 text-xs text-muted">
            Close
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-auto">
          <FillTable onPick={() => setMobile(null)} />
        </div>
      </Drawer>
      <SetupDetail />
      <SettingsDialog />
    </div>
  );
}

function NavButton({
  label,
  onClick,
  icon,
}: {
  label: string;
  onClick: () => void;
  icon: ReactNode;
}) {
  return (
    <button type="button" onClick={onClick} className="flex h-12 flex-1 flex-col items-center justify-center gap-0.5 text-xs text-muted">
      {icon}
      {label}
    </button>
  );
}

function Drawer({
  open,
  title,
  side,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  side: "left" | "right" | "bottom";
  onClose: () => void;
  children: ReactNode;
}) {
  if (!open) return null;
  const hidden = side === "bottom" || side === "right" ? "md:hidden" : "lg:hidden";
  return (
    <div className={cn("fixed inset-0 z-40", hidden)}>
      <button type="button" className="absolute inset-0 bg-canvas/70" aria-label="Close panel" onClick={onClose} />
      <div
        role="dialog"
        aria-label={title}
        className={cn(
          "absolute flex flex-col bg-surface",
          side === "left" && "inset-y-0 left-0 w-[min(22rem,100%)] border-r border-line",
          side === "right" && "inset-y-0 right-0 w-[min(22rem,100%)] border-l border-line",
          side === "bottom" && "inset-x-0 bottom-0 h-[70%] border-t border-line",
        )}
      >
        {children}
      </div>
    </div>
  );
}
