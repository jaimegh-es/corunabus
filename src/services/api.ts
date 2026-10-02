import { Capacitor } from '@capacitor/core';

export const PROD_API_HOST = 'https://buscoruna.inled.es';
export const BASE_URL = '/api/proxy';

// Concurrency limit: max simultaneous requests to upstream
const CONCURRENCY_LIMIT = 4;
const MAX_RETRIES = 1;
const RETRY_BASE_DELAY = 800;
const REQUEST_TIMEOUT_MS = 10000;

// Global API cooldown after 429
const COOLDOWN_KEY = 'buscoruna_api_cooldown_until';
let cooldownUntil = parseInt(sessionStorage.getItem(COOLDOWN_KEY) || '0', 10) || 0;
let cooldownEndTimer: any = null;

// Minimal spacing between upstream calls (300ms) to avoid burst collisions
const MIN_SPACING_MS = 300;
let lastRequestAt = 0;

export function isApiCooldownActive() {
  return Date.now() < cooldownUntil;
}

export function getApiCooldownRemaining() {
  return Math.max(0, cooldownUntil - Date.now());
}

function startApiCooldown(ms: number = 30000) {
  if (isApiCooldownActive()) return;
  cooldownUntil = Date.now() + ms;
  try { sessionStorage.setItem(COOLDOWN_KEY, cooldownUntil.toString()); } catch {}
  window.dispatchEvent(new CustomEvent('api-cooldown-start', { detail: { remaining: ms } }));
  if (cooldownEndTimer) clearTimeout(cooldownEndTimer);
  cooldownEndTimer = setTimeout(() => {
    cooldownUntil = 0;
    try { sessionStorage.removeItem(COOLDOWN_KEY); } catch {}
    window.dispatchEvent(new CustomEvent('api-cooldown-end'));
  }, ms);
}

let totalPending = 0;
let activeRequests = 0;
const waiters: Array<() => void> = [];

export function isApiLoading() {
  return totalPending > 0;
}

function updateLoadingState(delta: number) {
  totalPending += delta;
  if (totalPending === 1 && delta === 1) {
    window.dispatchEvent(new CustomEvent('api-loading-start'));
  } else if (totalPending === 0) {
    window.dispatchEvent(new CustomEvent('api-loading-end'));
  }
}

async function acquire() {
  if (activeRequests < CONCURRENCY_LIMIT) {
    activeRequests++;
    return;
  }
  await new Promise<void>((resolve) => {
    waiters.push(resolve);
  });
  activeRequests++;
}

function release() {
  activeRequests--;
  if (waiters.length > 0) {
    const next = waiters.shift()!;
    activeRequests++;
    next();
  }
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function fetchWithTimeout(url: string, options: RequestInit = {}): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  return fetch(url, { ...options, signal: controller.signal }).finally(() => clearTimeout(timer));
}

async function fetchWithRetry(url: string, fallbackUrl?: string): Promise<any> {
  // Minimal spacing
  const wait = MIN_SPACING_MS - (Date.now() - lastRequestAt);
  if (wait > 0) await sleep(wait);
  lastRequestAt = Date.now();

  let target = url;
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    try {
      const response = await fetchWithTimeout(target);

      if (response.status === 429) {
        startApiCooldown(60000);
        throw Object.assign(new Error('API rate limited (429)'), { rateLimited: true });
      }

      if (!response.ok) {
        if (response.status >= 500 && attempt < MAX_RETRIES) {
          if (fallbackUrl) target = fallbackUrl;
          await sleep(RETRY_BASE_DELAY);
          continue;
        }
        throw new Error(`API error: ${response.status} ${response.statusText}`);
      }

      return await response.json();
    } catch (error: any) {
      if (error?.rateLimited) throw error;
      if (attempt < MAX_RETRIES) {
        if (fallbackUrl) target = fallbackUrl;
        await sleep(RETRY_BASE_DELAY);
        continue;
      }
      throw error;
    }
  }
}

// The upstream reads `dato` as a set of "&clave=valor" pairs (func=99 uses
// "100&mostrar=B"). getQuery() percent-encodes the whole value so the proxy
// receives it as one parameter, but sending that encoded value straight to
// itranvias.com makes it parse "100%26mostrar%3DB" as the line id and answer
// `resultado: ERROR` (empty bus map) - which is what the native app used to get,
// since it talks to the upstream directly. Split the pairs back out so both
// transports send the same, valid request.
// El upstream lee `dato` como pares "&clave=valor" (func=99 usa "100&mostrar=B").
// getQuery() codifica el valor entero para que el proxy lo reciba como un solo
// parámetro, pero enviarlo codificado a itranvias.com hace que interprete
// "100%26mostrar%3DB" como id de línea y devuelva `resultado: ERROR` (mapa de
// buses vacío). Separamos los pares para que ambas rutas envíen la misma petición.
export function buildUpstreamQueryUrl(func: number, dato: string): string {
  const params = new URLSearchParams();
  params.set('func', String(func));
  String(dato ?? '').split('&').forEach((part) => {
    if (!part) return;
    const eq = part.indexOf('=');
    if (eq === -1) params.set('dato', part);
    else params.set(part.slice(0, eq), part.slice(eq + 1));
  });
  return `https://itranvias.com/queryitr_v3.php?${params.toString()}`;
}

export async function getQuery(func: number, dato: string) {
  if (isApiCooldownActive()) {
    throw new Error('API cooldown active');
  }

  // Construct URLs
  const proxyUrl = `${BASE_URL}?func=${func}&dato=${encodeURIComponent(dato)}`;
  const directUrl = buildUpstreamQueryUrl(func, dato);
  const prodProxyUrl = `${PROD_API_HOST}/api/proxy?func=${func}&dato=${encodeURIComponent(dato)}`;

  let primaryUrl = proxyUrl;
  let fallbackUrl = directUrl;

  if (Capacitor.isNativePlatform()) {
    // The upstream sends no CORS headers, so a WebView fetch to it is always
    // blocked and every "direct first" attempt just wastes a round trip and a
    // retry. The relative proxy is same-origin both in production (the app is
    // served from the API host) and when testing against a local dev server,
    // so it always answers. Keep the cross-origin proxy as the rescue path.
    primaryUrl = proxyUrl;
    fallbackUrl = prodProxyUrl;
  }

  updateLoadingState(1);

  try {
    await acquire();
    try {
      return await fetchWithRetry(primaryUrl, fallbackUrl);
    } finally {
      release();
    }
  } finally {
    updateLoadingState(-1);
  }
}

// --- Stop arrivals cache (TTL: 15s) ------------------------------------------
const ARRIVALS_CACHE_TTL_MS = 15000;
const arrivalsCache = new Map<string, { at: number; data: any }>();
const arrivalsInflight = new Map<string, Promise<any>>();

function getStopArrivalsCached(stopId: number, force: boolean = false): Promise<any> {
  const key = stopId.toString();

  if (!force) {
    const cached = arrivalsCache.get(key);
    if (cached && Date.now() - cached.at < ARRIVALS_CACHE_TTL_MS) {
      return Promise.resolve(cached.data);
    }
  }

  const flightKey = `${key}:${force ? 'f' : 'c'}`;
  const existing = arrivalsInflight.get(flightKey);
  if (existing) return existing;

  const p = getQuery(0, key)
    .then((data) => {
      if (data && data.resultado) {
        arrivalsCache.set(key, { at: Date.now(), data });
      }
      return data;
    })
    .finally(() => {
      arrivalsInflight.delete(flightKey);
    });

  arrivalsInflight.set(flightKey, p);
  return p;
}

export const API = {
  // Real-time arrivals for a stop (cached ~15s; pass `true` to force a refresh)
  getStopArrivals: (stopId: number, force: boolean = false) =>
    getStopArrivalsCached(stopId, force),

  // List of lines (basic info)
  getLines: () => getQuery(1, '1'),

  // Detailed info for a line (stops, current buses)
  getLineInfo: (lineId: number) => getQuery(2, lineId.toString()),

  // Find nearby stops
  getNearbyStops: (lat: number, lng: number, radius: number = 5000, max: number = 5) =>
    getQuery(3, `${lat}_${lng}_${radius}_${max}`),

  // Full catalog
  getCatalog: (date = '20160101T000000', lang = 'es') =>
    getQuery(7, `${date}_${lang}_0_20160101T000000`),

  // Schedules for a line
  getSchedules: (lineId: number, date: string) =>
    getQuery(8, `${lineId}&fecha=${date}`),

  // Map data
  getMapData: (lineId: number, show: 'B' | 'PRB') =>
    getQuery(99, `${lineId}&mostrar=${show}`),
};