// Real-time arrivals for the legacy endpoints. Same upstream call as the web
// app (func=0) but cached for a few seconds at the edge, because the E71 poll
// interval and the /m/ page can easily overlap.
//
// Llegadas en tiempo real para los endpoints antiguos, con caché corta.

import type { LegacyCatalog } from './catalog';

const UPSTREAM_BASE = 'https://itranvias.com/queryitr_v3.php';
const CACHE_TTL_SECONDS = 10;
const STALE_IF_ERROR_SECONDS = 120;
/** Tope de cordura para los minutos que publica el upstream. */
const MAX_MINUTES = 180;

export interface ArrivalLine {
  lineId: number;
  /** Commercial number from the catalog ("14"); falls back to the id. */
  code: string;
  /** Minutes until the next buses, ascending, at most 3. */
  minutes: number[];
}

export interface Arrivals {
  stopId: number;
  lines: ArrivalLine[];
  /** "live" when the upstream answered, "cached" when we had to reuse data. */
  source: 'live' | 'cached';
  updatedAt: string;
}

interface UpstreamArrivalLine {
  linea?: number | string;
  buses?: Array<{ tiempo?: number | string | null }>;
}

interface UpstreamArrivals {
  resultado?: string;
  buses?: { parada?: number | string; lineas?: UpstreamArrivalLine[] };
}

function cacheStorage(): Cache | null {
  try {
    return (globalThis as unknown as { caches?: { default: Cache } }).caches?.default ?? null;
  } catch {
    return null;
  }
}

function cacheKey(stopId: number): Request {
  return new Request(`https://catalog.buscoruna.internal/v1/arrivals/${stopId}`);
}

function parse(payload: UpstreamArrivals): ArrivalLine[] {
  const lines: ArrivalLine[] = [];
  for (const entry of payload.buses?.lineas ?? []) {
    const lineId = Number(entry?.linea);
    if (!lineId) continue;
    const minutes = (entry.buses ?? [])
      .map((bus) => Number(bus?.tiempo))
      // El upstream a veces manda valores absurdos ("3354"): un bus a más de
      // 3 horas no es información útil y solo ensucia la pantalla.
      .filter((value) => Number.isFinite(value) && value >= 0 && value <= MAX_MINUTES)
      .sort((a, b) => a - b)
      .slice(0, 3);
    lines.push({ lineId, code: String(lineId), minutes });
  }
  return lines;
}

export async function getArrivals(
  catalog: LegacyCatalog,
  stopId: number,
  options: { refresh?: boolean } = {},
): Promise<Arrivals> {
  const cache = cacheStorage();
  const key = cacheKey(stopId);

  if (cache && !options.refresh) {
    try {
      const hit = await cache.match(key);
      if (hit) {
        const cached = (await hit.json()) as Arrivals;
        return { ...cached, source: 'cached' };
      }
    } catch {
      // ignore cache misses/read failures
    }
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 9000);
  let payload: UpstreamArrivals;

  try {
    const response = await fetch(`${UPSTREAM_BASE}?func=0&dato=${encodeURIComponent(String(stopId))}`, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; BusCoruna/legacy)',
        Referer: 'https://itranvias.com/',
      },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`upstream ${response.status}`);
    payload = (await response.json()) as UpstreamArrivals;
    if (payload.resultado !== 'OK') throw new Error('upstream resultado distinto de OK');
  } catch (error) {
    clearTimeout(timer);
    if (cache) {
      try {
        const stale = await cache.match(key);
        if (stale) return { ...((await stale.json()) as Arrivals), source: 'cached' };
      } catch {
        // ignore
      }
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }

  const lines = parse(payload).map((line) => ({
    ...line,
    code: catalog.linesById[line.lineId]?.code ?? line.code,
  }));

  const result: Arrivals = {
    stopId,
    lines,
    source: 'live',
    updatedAt: new Date().toISOString(),
  };

  if (cache) {
    try {
      await cache.put(
        key,
        new Response(JSON.stringify(result), {
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': `max-age=${CACHE_TTL_SECONDS}, stale-if-error=${STALE_IF_ERROR_SECONDS}`,
          },
        }),
      );
    } catch {
      // ignore cache write failures
    }
  }

  return result;
}
