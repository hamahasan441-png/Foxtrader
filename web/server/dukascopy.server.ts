import http from "node:http";
import https from "node:https";
import { promises as dns } from "node:dns";
import { LZMA_WORKER as lzma } from "./lzma-worker.js";
import type { Candle, TimeframeId } from "../src/lib/market/types.ts";

type Feed = { path: string; point: number; digits: number };

const FEEDS: Record<string, Feed> = {
  EURUSD: { path: "EURUSD", point: 100_000, digits: 5 },
  GBPUSD: { path: "GBPUSD", point: 100_000, digits: 5 },
  USDJPY: { path: "USDJPY", point: 1_000, digits: 3 },
  USDCHF: { path: "USDCHF", point: 100_000, digits: 5 },
  XAUUSD: { path: "XAUUSD", point: 1_000, digits: 2 },
  XAGUSD: { path: "XAGUSD", point: 1_000, digits: 3 },
  US30: { path: "USA30IDXUSD", point: 1_000, digits: 1 },
  NAS100: { path: "USATECHIDXUSD", point: 1_000, digits: 1 },
  SPX500: { path: "USA500IDXUSD", point: 1_000, digits: 2 },
  GER40: { path: "DEUIDXEUR", point: 1_000, digits: 1 },
};

const LIMIT: Record<TimeframeId, number> = {
  M1: 300,
  M5: 240,
  M15: 220,
  H1: 240,
  H4: 200,
  D1: 220,
};

type Bucket = { path: string; startMs: number; live: boolean };

const fileCache = new Map<string, { at: number; bytes: Buffer | null }>();
const resultCache = new Map<string, { at: number; candles: Candle[]; error: string | null }>();
let hosts: { at: number; ips: string[] } | null = null;

export async function loadDukascopy(
  symbol: string,
  timeframe: TimeframeId,
): Promise<{ candles: Candle[]; error: string | null }> {
  const feed = FEEDS[symbol];
  if (!feed) return { candles: [], error: `${symbol} is not on the Dukascopy archive` };

  const cacheKey = `${symbol}|${timeframe}`;
  const cached = resultCache.get(cacheKey);
  if (cached && Date.now() - cached.at < 20_000) {
    return { candles: cached.candles, error: cached.error };
  }

  const now = Date.now();
  const buckets = plan(feed.path, timeframe, now);
  const settled = await mapLimit(buckets, 4, async (bucket) => {
    try {
      const bytes = await readBi5(bucket.path, bucket.live);
      return { bucket, bytes, error: null as string | null };
    } catch (error) {
      return {
        bucket,
        bytes: null as Buffer | null,
        error: error instanceof Error ? error.message : "Dukascopy request failed",
      };
    }
  });

  const rows: Candle[] = [];
  let sawFile = false;
  let lastError: string | null = null;
  for (const item of settled) {
    if (item.error) lastError = item.error;
    if (!item.bytes || item.bytes.length === 0) continue;
    sawFile = true;
    rows.push(...decode(item.bytes, item.bucket.startMs, feed));
    await new Promise((resolve) => setImmediate(resolve));
  }

  const seconds = span(timeframe);
  const nowSec = Math.floor(now / 1000);
  const merged = aggregate(
    rows.filter((bar) => bar.time <= nowSec).sort((a, b) => a.time - b.time),
    seconds,
  );
  const candles = merged.slice(-LIMIT[timeframe]);
  if (candles.length === 0) {
    return {
      candles: [],
      error: sawFile ? "Dukascopy returned an empty session" : (lastError ?? "Dukascopy archive did not answer"),
    };
  }
  const value = { candles, error: null };
  resultCache.set(cacheKey, { at: Date.now(), candles, error: null });
  return value;
}

function plan(path: string, timeframe: TimeframeId, nowMs: number): Bucket[] {
  const now = new Date(nowMs);
  if (timeframe === "D1") {
    const buckets: Bucket[] = [shiftYear(now, -1), shiftYear(now, 0)].map((date) => ({
      path: `${path}/${date.getUTCFullYear()}/BID_candles_day_1.bi5`,
      startMs: Date.UTC(date.getUTCFullYear(), 0, 1),
      live: date.getUTCFullYear() === now.getUTCFullYear(),
    }));
    for (let month = 0; month <= now.getUTCMonth(); month += 1) {
      const date = new Date(Date.UTC(now.getUTCFullYear(), month, 1));
      buckets.push({
        path: `${path}/${date.getUTCFullYear()}/${pad(month)}/BID_candles_hour_1.bi5`,
        startMs: date.getTime(),
        live: month === now.getUTCMonth(),
      });
    }
    for (let back = 7; back >= 0; back -= 1) {
      const date = new Date(nowMs - back * 86_400_000);
      buckets.push({
        path: `${path}/${date.getUTCFullYear()}/${pad(date.getUTCMonth())}/${pad(date.getUTCDate())}/BID_candles_min_1.bi5`,
        startMs: Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()),
        live: back === 0,
      });
    }
    return buckets;
  }
  const buckets: Bucket[] = [];
  if (timeframe === "H1" || timeframe === "H4") {
    for (let back = 3; back >= 0; back -= 1) {
      const date = shiftMonth(now, -back);
      buckets.push({
        path: `${path}/${date.getUTCFullYear()}/${pad(date.getUTCMonth())}/BID_candles_hour_1.bi5`,
        startMs: Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1),
        live: date.getUTCFullYear() === now.getUTCFullYear() && date.getUTCMonth() === now.getUTCMonth(),
      });
    }
  }
  const days = timeframe === "M1" || timeframe === "M5" || timeframe === "M15" ? 3 : 8;
  for (let back = days - 1; back >= 0; back -= 1) {
    const date = new Date(nowMs - back * 86_400_000);
    buckets.push({
      path: `${path}/${date.getUTCFullYear()}/${pad(date.getUTCMonth())}/${pad(date.getUTCDate())}/BID_candles_min_1.bi5`,
      startMs: Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()),
      live: back === 0,
    });
  }
  return buckets;
}

function shiftMonth(date: Date, delta: number) {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + delta, 1));
}

function shiftYear(date: Date, delta: number) {
  return new Date(Date.UTC(date.getUTCFullYear() + delta, 0, 1));
}

function pad(value: number) {
  return String(value).padStart(2, "0");
}

async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor;
      cursor += 1;
      out[index] = await fn(items[index]);
    }
  });
  await Promise.all(workers);
  return out;
}

function span(timeframe: TimeframeId) {
  if (timeframe === "M1") return 60;
  if (timeframe === "M5") return 300;
  if (timeframe === "M15") return 900;
  if (timeframe === "H1") return 3600;
  if (timeframe === "H4") return 14_400;
  return 86_400;
}

async function readBi5(path: string, live: boolean): Promise<Buffer | null> {
  const hit = fileCache.get(path);
  const ttl = live ? 20_000 : 6 * 3_600_000;
  if (hit && Date.now() - hit.at < ttl) return hit.bytes;

  const ips = await resolveHosts();
  const origins = ["194.8.15.180", ...ips.filter((ip) => ip !== "194.8.15.180")];
  let last = "Dukascopy did not answer";
  for (const ip of origins) {
    try {
      const response = await httpGet(ip, path);
      if (response.status === 404) {
        fileCache.set(path, { at: Date.now(), bytes: null });
        return null;
      }
      if (response.status === 200) {
        const body = response.body.length === 0 ? null : response.body;
        fileCache.set(path, { at: Date.now(), bytes: body });
        return body;
      }
      last = `Dukascopy ${response.status}`;
    } catch (error) {
      last = error instanceof Error ? error.message : last;
    }
  }
  try {
    const response = await httpsGet(path);
    if (response.status === 404) {
      fileCache.set(path, { at: Date.now(), bytes: null });
      return null;
    }
    if (response.status === 200 && response.body.length > 0) {
      fileCache.set(path, { at: Date.now(), bytes: response.body });
      return response.body;
    }
    if (response.status !== 200) last = `Dukascopy ${response.status}`;
  } catch (error) {
    last = error instanceof Error ? error.message : last;
  }
  throw new Error(last);
}

function httpGet(ip: string, path: string): Promise<{ status: number; body: Buffer }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: ip,
        port: 80,
        method: "GET",
        path: `/datafeed/${path}`,
        headers: {
          Host: "datafeed.dukascopy.com",
          Accept: "*/*",
          "User-Agent": "Mozilla/5.0",
          Connection: "close",
        },
        timeout: 8_000,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks) }));
      },
    );
    req.on("timeout", () => req.destroy(new Error("Dukascopy timed out")));
    req.on("error", reject);
    req.end();
  });
}

function httpsGet(path: string): Promise<{ status: number; body: Buffer }> {
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        host: "datafeed.dukascopy.com",
        port: 443,
        method: "GET",
        path: `/datafeed/${path}`,
        headers: {
          Host: "datafeed.dukascopy.com",
          Accept: "*/*",
          "User-Agent": "Mozilla/5.0",
          Connection: "close",
        },
        timeout: 8_000,
        servername: "datafeed.dukascopy.com",
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk: Buffer) => chunks.push(chunk));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body: Buffer.concat(chunks) }));
      },
    );
    req.on("timeout", () => req.destroy(new Error("Dukascopy timed out")));
    req.on("error", reject);
    req.end();
  });
}

async function resolveHosts() {
  if (hosts && Date.now() - hosts.at < 5 * 60_000) return hosts.ips;
  const ips = await dns.resolve4("datafeed.dukascopy.com");
  if (ips.length === 0) throw new Error("Dukascopy host did not resolve");
  hosts = { at: Date.now(), ips };
  return ips;
}

function decode(bytes: Buffer, startMs: number, feed: Feed): Candle[] {
  const decoded = Buffer.from(lzma.decompress(Array.from(bytes)) as number[]);
  const candles: Candle[] = [];
  const count = Math.floor(decoded.length / 24);
  for (let index = 0; index < count; index += 1) {
    const offset = index * 24;
    const shift = decoded.readInt32BE(offset);
    const open = decoded.readInt32BE(offset + 4);
    const close = decoded.readInt32BE(offset + 8);
    const low = decoded.readInt32BE(offset + 12);
    const high = decoded.readInt32BE(offset + 16);
    if (shift < 0 || open <= 0 || close <= 0 || low <= 0 || high <= 0) continue;
    if (high < low || open < low || open > high || close < low || close > high) continue;
    candles.push({
      time: Math.floor(startMs / 1000) + shift,
      open: round(open / feed.point, feed.digits),
      high: round(high / feed.point, feed.digits),
      low: round(low / feed.point, feed.digits),
      close: round(close / feed.point, feed.digits),
    });
  }
  return candles;
}

function aggregate(candles: Candle[], seconds: number): Candle[] {
  const out: Candle[] = [];
  for (const bar of candles) {
    const time = Math.floor(bar.time / seconds) * seconds;
    const prev = out[out.length - 1];
    if (prev && prev.time === time) {
      prev.high = Math.max(prev.high, bar.high);
      prev.low = Math.min(prev.low, bar.low);
      prev.close = bar.close;
    } else if (!prev || time > prev.time) {
      out.push({ time, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
    }
  }
  return out;
}

function round(value: number, digits: number) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}
