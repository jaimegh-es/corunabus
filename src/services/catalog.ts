// Server-side catalog access for the legacy (Nokia E71 / Java ME) clients and
// the lightweight /m/ pages.
//
// The iTranvías catalog (func=7) is ~74 KB of JSON and changes roughly once a
// month. We fetch it once per isolate (and once per edge PoP through the Cache
// API) and keep it in a compact shape, so a legacy client asking for the text
// listing costs one cached upstream call and ~16 KB on the wire.
//
// Acceso al catálogo en el servidor para los clientes antiguos (Nokia E71 /
// Java ME) y las páginas ligeras /m/. El catálogo se pide una vez por isolate
// y se guarda en la caché del edge; los clientes solo reciben texto compacto.

const UPSTREAM_BASE = 'https://itranvias.com/queryitr_v3.php';
const CATALOG_URL = `${UPSTREAM_BASE}?func=7&dato=20160101T000000_es_0_20160101T000000`;

const CACHE_TTL_SECONDS = 6 * 60 * 60;
// Upstream is rate limited (1 req/s, 4/min). A manual refresh from the phone
// must not be able to burn that budget, so refreshes are throttled.
const MIN_REFRESH_INTERVAL_MS = 60 * 1000;

export interface LegacyLine {
  id: number;
  /** Commercial number shown on the bus ("1", "14", "311"). */
  code: string;
  from: string;
  to: string;
  /** Ordered stop ids per direction. */
  routes: number[][];
}

export interface LegacyStop {
  id: number;
  name: string;
  lat: number;
  lng: number;
}

export interface LegacyCatalog {
  /** Upstream catalog date, e.g. "20260430T000000". */
  version: string;
  lines: LegacyLine[];
  stops: LegacyStop[];
  stopsById: Record<number, LegacyStop>;
  linesById: Record<number, LegacyLine>;
  /** Line ids per stop, taken from `enlaces` and cross-checked with routes. */
  linesAtStop: Record<number, number[]>;
}

interface UpstreamRoute {
  ruta?: number;
  paradas?: Array<number | string>;
}

interface UpstreamLine {
  id?: number | string;
  lin_comer?: string;
  nombre_orig?: string;
  nombre_dest?: string;
  rutas?: UpstreamRoute[];
}

interface UpstreamStop {
  id?: number | string;
  nombre?: string;
  posx?: number | string;
  posy?: number | string;
}

interface UpstreamCatalog {
  fecha?: string;
  lineas?: UpstreamLine[];
  paradas?: Record<string, UpstreamStop>;
}

interface UpstreamResponse {
  resultado?: string;
  iTranvias?: { actualizacion?: UpstreamCatalog };
  actualizacion?: UpstreamCatalog;
}

let memoryCache: { at: number; catalog: LegacyCatalog } | null = null;
let inflight: Promise<LegacyCatalog> | null = null;

export function normalizeText(value: unknown): string {
  return String(value ?? '')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\|/g, '/')
    .replace(/\s+/g, ' ')
    .trim();
}

function toNumber(value: unknown): number {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

export function buildLegacyCatalog(raw: UpstreamCatalog): LegacyCatalog {
  const stops: LegacyStop[] = [];
  const stopsById: Record<number, LegacyStop> = {};

  for (const key of Object.keys(raw.paradas ?? {})) {
    const entry = raw.paradas![key];
    const id = toNumber(entry?.id ?? key);
    if (!id) continue;
    const stop: LegacyStop = {
      id,
      name: normalizeText(entry?.nombre ?? 'Parada ' + id),
      lat: toNumber(entry?.posy),
      lng: toNumber(entry?.posx),
    };
    stops.push(stop);
    stopsById[id] = stop;
  }
  stops.sort((a, b) => a.id - b.id);

  const lines: LegacyLine[] = [];
  const linesById: Record<number, LegacyLine> = {};

  for (const entry of raw.lineas ?? []) {
    const id = toNumber(entry?.id);
    if (!id) continue;
    const line: LegacyLine = {
      id,
      code: normalizeText(entry?.lin_comer ?? id),
      from: normalizeText(entry?.nombre_orig ?? ''),
      to: normalizeText(entry?.nombre_dest ?? ''),
      routes: (entry?.rutas ?? [])
        .map((route) => (route?.paradas ?? []).map((stopId) => toNumber(stopId)).filter((stopId) => stopId > 0))
        .filter((route) => route.length > 1),
    };
    lines.push(line);
    linesById[id] = line;
  }
  lines.sort((a, b) => a.code.localeCompare(b.code, 'es', { numeric: true }));

  // Which lines stop at each stop. `enlaces` from the upstream is authoritative
  // (it includes the transfer hints) but it also contains ids of lines that are
  // not in the catalog anymore, so we drop those.
  const linesAtStop: Record<number, number[]> = {};

  const add = (stopId: number, lineId: number) => {
    if (!linesById[lineId] || !stopsById[stopId]) return;
    const list = linesAtStop[stopId] ?? (linesAtStop[stopId] = []);
    if (list.indexOf(lineId) === -1) list.push(lineId);
  };

  for (const stop of stops) {
    const rawStop = raw.paradas?.[String(stop.id)];
    for (const linked of (rawStop as unknown as { enlaces?: Array<number | string> })?.enlaces ?? []) {
      add(stop.id, toNumber(linked));
    }
  }

  // Fill gaps from the route sequences: some stops have no `enlaces` entry but
  // are clearly served by a line.
  for (const line of lines) {
    for (const route of line.routes) {
      for (const stopId of route) add(stopId, line.id);
    }
  }

  for (const key of Object.keys(linesAtStop)) {
    const lineIds = linesAtStop[Number(key)];
    lineIds.sort((a, b) => (linesById[a]?.code ?? '').localeCompare(linesById[b]?.code ?? '', 'es', { numeric: true }));
  }

  return {
    version: normalizeText(raw.fecha ?? 'desconocida') || 'desconocida',
    lines,
    stops,
    stopsById,
    linesById,
    linesAtStop,
  };
}

async function fetchUpstreamCatalog(): Promise<LegacyCatalog> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(CATALOG_URL, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (compatible; BusCoruna/legacy)',
        Referer: 'https://itranvias.com/',
      },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`upstream ${response.status}`);
    const payload = (await response.json()) as UpstreamResponse;
    const raw = payload.iTranvias?.actualizacion ?? payload.actualizacion;
    if (!raw || !raw.lineas || !raw.paradas) throw new Error('catálogo upstream vacío');
    return buildLegacyCatalog(raw);
  } finally {
    clearTimeout(timer);
  }
}

function cacheStorage(): Cache | null {
  try {
    return (globalThis as unknown as { caches?: { default: Cache } }).caches?.default ?? null;
  } catch {
    return null;
  }
}

export async function getCatalog(options: { refresh?: boolean } = {}): Promise<LegacyCatalog> {
  const now = Date.now();
  const fresh = options.refresh && (!memoryCache || now - memoryCache.at > MIN_REFRESH_INTERVAL_MS);

  if (memoryCache && !fresh) return memoryCache.catalog;

  const cache = cacheStorage();
  const cacheKey = new Request('https://catalog.buscoruna.internal/v1/legacy.json');

  if (!fresh && cache) {
    try {
      const hit = await cache.match(cacheKey);
      if (hit) {
        const catalog = (await hit.json()) as LegacyCatalog;
        memoryCache = { at: now, catalog };
        return catalog;
      }
    } catch {
      // Cache API unavailable (local dev) - fall through to upstream
    }
  }

  if (inflight) return inflight;

  inflight = (async () => {
    const catalog = await fetchUpstreamCatalog();
    memoryCache = { at: Date.now(), catalog };
    if (cache) {
      try {
        await cache.put(
          cacheKey,
          new Response(JSON.stringify(catalog), {
            headers: {
              'Content-Type': 'application/json',
              'Cache-Control': `max-age=${CACHE_TTL_SECONDS}`,
            },
          }),
        );
      } catch {
        // Cache write failures are not fatal
      }
    }
    return catalog;
  })().finally(() => {
    inflight = null;
  });

  return inflight;
}

/** Compact one-line label for a line, e.g. "14 Porta Real - Castrillón". */
export function lineLabel(line: LegacyLine): string {
  const ends = [line.from, line.to].filter(Boolean).join(' - ');
  return ends ? `${line.code} ${ends}` : line.code;
}

export function stopName(catalog: LegacyCatalog, stopId: number): string {
  return catalog.stopsById[stopId]?.name ?? `Parada ${stopId}`;
}

/** Stop ids served by a line, in route order, deduplicated across directions. */
export function lineStopIds(catalog: LegacyCatalog, line: LegacyLine): number[] {
  const seen: number[] = [];
  for (const route of line.routes) {
    for (const stopId of route) {
      if (catalog.stopsById[stopId] && seen.indexOf(stopId) === -1) seen.push(stopId);
    }
  }
  return seen;
}
