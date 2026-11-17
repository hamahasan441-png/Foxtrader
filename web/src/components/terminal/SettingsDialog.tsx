import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { useDesk } from "@/lib/market/desk";
import { cn } from "@/lib/cn";

export function SettingsDialog() {
  const open = useDesk((state) => state.settingsOpen);
  const setOpen = useDesk((state) => state.setSettings);
  const layers = useDesk((state) => state.layers);
  const toggleLayer = useDesk((state) => state.toggleLayer);
  const paused = useDesk((state) => state.paused);
  const setPaused = useDesk((state) => state.setPaused);
  const resetView = useDesk((state) => state.resetView);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-canvas/75" />
        <Dialog.Content className="fixed top-1/2 left-1/2 z-50 w-11/12 max-w-sm -translate-x-1/2 -translate-y-1/2 border border-line bg-surface p-4 focus:outline-none">
          <div className="flex items-start justify-between gap-3">
            <div>
              <Dialog.Title className="text-base font-medium">Settings</Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-muted">
                Desk model. Not a brokerage feed.
              </Dialog.Description>
            </div>
            <Dialog.Close className="flex size-11 items-center justify-center text-muted hover:text-fg" aria-label="Close">
              <X className="size-4" strokeWidth={1.5} />
            </Dialog.Close>
          </div>
          <div className="mt-3">
            <Row label="Tape" on={!paused} onClick={() => setPaused(!paused)} onText="Running" offText="Paused" />
            <Row label="Grid" on={layers.grid} onClick={() => toggleLayer("grid")} />
            <Row
              label="Invalid setups"
              on={layers.invalidated}
              onClick={() => toggleLayer("invalidated")}
              onText="Shown"
              offText="Hidden"
            />
            <button
              type="button"
              className="mt-2 h-11 w-full text-left text-sm text-fg"
              onClick={() => {
                resetView();
                setOpen(false);
              }}
            >
              Reset chart view
            </button>
          </div>
          <p className="mt-4 text-xs text-muted">
            Keys 1–6 change timeframe. Charting by{" "}
            <a className="text-accent" href="https://www.tradingview.com/" target="_blank" rel="noreferrer">
              TradingView
            </a>
            .
          </p>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Row({
  label,
  on,
  onClick,
  onText = "On",
  offText = "Off",
}: {
  label: string;
  on: boolean;
  onClick: () => void;
  onText?: string;
  offText?: string;
}) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className="flex h-11 w-full items-center justify-between border-b border-line-soft text-sm"
    >
      <span>{label}</span>
      <span className={cn(on ? "text-accent" : "text-faint")}>{on ? onText : offText}</span>
    </button>
  );
}
