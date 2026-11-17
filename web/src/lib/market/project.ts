import type { Layers, LineSpec, Tape } from "./types";

export function projectLines(tape: Tape, layers: Layers): LineSpec[] {
  const hideIdea = tape.setup?.status === "invalidated" && !layers.invalidated;
  const visible = tape.lines.filter((line) => {
    if (!layers[line.layer]) return false;
    if (hideIdea && line.layer === "levels") return false;
    return true;
  });
  visible.sort((a, b) => a.price - b.price || b.priority - a.priority);

  const merged: LineSpec[] = [];
  const dist = tape.unit * 0.55;
  for (const line of visible) {
    const prev = merged[merged.length - 1];
    if (prev && Math.abs(prev.price - line.price) <= dist && !prev.label.includes("·")) {
      const winner = line.priority >= prev.priority ? line : prev;
      const loser = winner === line ? prev : line;
      merged[merged.length - 1] = {
        ...winner,
        label: `${winner.label} · ${loser.label}`,
        from: Math.min(winner.from, loser.from),
        dashed: Boolean(winner.dashed),
        clickable: Boolean(winner.clickable || loser.clickable),
      };
    } else if (prev && Math.abs(prev.price - line.price) <= dist) {
      if (line.priority > prev.priority) merged[merged.length - 1] = { ...line };
    } else {
      merged.push({ ...line });
    }
  }
  return merged;
}

export function projectZone(tape: Tape, layers: Layers) {
  if (!tape.zone || !layers.lit) return null;
  if (tape.setup?.status === "invalidated" && !layers.invalidated) return null;
  return tape.zone;
}
