import type { APIRoute } from 'astro';

// Shared edge cache for per-line live map data (func=99). Bus positions are
// used for *detection* only, so a few minutes of staleness is fine. Caching
// here means every user's scans warm the cache for everyone: the upstream
// rate limit (1 req/s, 4/min) is spent once per line per 5 min globally,
// instead of once per user per scan.
//
// Caché compartida en el edge para los mapas de línea en vivo (func=99).
// Las posiciones de bus se usan para *detectar*, así que unos minutos de
// antigüedad son aceptables. Cachear aquí hace que el escaneo de cada usuario
// caliente la caché para todos: el límite de peticiones upstream se gasta una
// vez por línea cada 5 min globalmente, en vez de una por usuario y escaneo.
const LINE_MAP_CACHE_TTL = 300; // seconds
const lineMapInFlight = new Map<string, Promise<{ body: string; status: number }>>();

const UPSTREAM_BASE = 'https://itranvias.com/queryitr_v3.php';

// The client sends `dato` percent-encoded because some calls carry several
// "&clave=valor" pairs inside it (func=99: "100&mostrar=B"). Rebuild the
// upstream URL by splitting those pairs back out, exactly like the client does
// when it talks to itranvias.com directly from the native app.
// El cliente envía `dato` codificado porque algunas llamadas llevan varios pares
// "&clave=valor" dentro (func=99: "100&mostrar=B"). Reconstruimos la URL del
// upstream separando esos pares, igual que hace el cliente al llamar directo
// desde la app nativa.
function buildUpstreamUrl(func: string, dato: string): string {
  const params = new URLSearchParams();
  params.set('func', func);
  String(dato ?? '').split('&').forEach((part) => {
    if (!part) return;
    const eq = part.indexOf('=');
    if (eq === -1) params.set('dato', part);
    else params.set(part.slice(0, eq), part.slice(eq + 1));
  });
  return `${UPSTREAM_BASE}?${params.toString()}`;
}

async function getUpstreamWithSharedCache(targetUrl: string, cacheable: boolean, buildResponse: (body: string, status: number) => Response): Promise<Response> {
  if (!cacheable) {
    const res = await fetchUpstream(targetUrl);
    return buildResponse(res.body, res.status);
  }

  const cacheKey = new Request(`https://shared-cache.buscoruna.internal/${targetUrl}`);

  try {
    const cache = (globalThis as any).caches?.default;
    if (cache) {
      const hit = await cache.match(cacheKey);
      if (hit) {
        const body = await hit.text();
        return buildResponse(body, 200);
      }
    }
  } catch {
    // Cache API unavailable (local dev) - fall through to direct fetch
    // Cache API no disponible (dev local) - continuar con fetch directo
  }

  // Single-flight: concurrent requests for the same line share one upstream fetch
  // Single-flight: peticiones concurrentes de la misma línea comparten un fetch
  let inFlight = lineMapInFlight.get(targetUrl);
  if (!inFlight) {
    const promise: Promise<{ body: string; status: number }> = (async () => {
      const res = await fetchUpstream(targetUrl);
      if (res.status >= 200 && res.status < 400 && res.body.trimStart().startsWith('{')) {
        try {
          const cache = (globalThis as any).caches?.default;
          if (cache) {
            await cache.put(cacheKey, new Response(res.body, {
              headers: { 'Content-Type': 'application/json', 'Cache-Control': `max-age=${LINE_MAP_CACHE_TTL}` },
            }));
          }
        } catch {
          // ignore cache write failures
        }
      }
      lineMapInFlight.delete(targetUrl);
      return res;
    })();
    inFlight = promise;
    lineMapInFlight.set(targetUrl, promise);
  }

  const res = await inFlight;
  return buildResponse(res.body, res.status);
}

async function fetchUpstream(targetUrl: string): Promise<{ body: string; status: number }> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 10000); // 10s timeout

  const response = await fetch(targetUrl, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
      'Referer': 'https://itranvias.com/',
    },
    signal: controller.signal,
  });

  clearTimeout(timeoutId);
  const status = response.status;
  const body = await response.text();
  return { body, status };
}

export const GET: APIRoute = async ({ request }) => {
  const origin = request.headers.get('origin');
  const corsHeaders = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, User-Agent, X-Requested-With',
  };

  const url = new URL(request.url);
  const type = url.searchParams.get('type') || 'itranvias';
  
  let targetUrl = '';

  if (type === 'itranvias') {
    const func = url.searchParams.get('func');
    const dato = url.searchParams.get('dato');
    if (!func || !dato) {
      return new Response(JSON.stringify({ error: 'Missing parameters' }), { status: 400 });
    }
    targetUrl = buildUpstreamUrl(func, dato);
  } else if (type === 'photon') {
    const q = url.searchParams.get('q');
    const lat = url.searchParams.get('lat') || '43.3623';
    const lon = url.searchParams.get('lon') || '-8.4115';
    const bbox = url.searchParams.get('bbox');
    const limit = url.searchParams.get('limit') || '5';
    
    if (!q) {
      return new Response(JSON.stringify({ error: 'Missing query' }), { status: 400 });
    }
    targetUrl = `https://photon.komoot.io/api/?q=${encodeURIComponent(q)}&lat=${lat}&lon=${lon}${bbox ? `&bbox=${bbox}` : ''}&limit=${limit}`;
  } else {
    return new Response(JSON.stringify({ error: 'Invalid proxy type' }), { status: 400 });
  }
  
  try {
    const func = url.searchParams.get('func');
    const cacheable = type === 'itranvias' && func === '99';
    const result = await getUpstreamWithSharedCache(targetUrl, cacheable, (raw, upstreamStatus) => {
    // The upstream is aggressively rate-limited (1 req/s, 4/min; it answers 429
    // with an HTML body). Always read the raw body first - JSON.parse on an HTML
    // error page would crash the proxy - and mirror the upstream status so the
    // client can honor the cooldown with a proper Retry-After.
    let data: any;
    try {
      data = JSON.parse(raw);
    } catch {
      const isRateLimit = upstreamStatus === 429;
      const retryAfter = isRateLimit ? '60' : '';
      return new Response(
        JSON.stringify({
          error: isRateLimit ? 'rate_limited' : 'upstream_unavailable',
          upstream_status: upstreamStatus,
        }),
        {
          status: isRateLimit ? 429 : (upstreamStatus >= 400 ? upstreamStatus : 502),
          headers: {
            'Content-Type': 'application/json',
            ...(retryAfter ? { 'Retry-After': retryAfter } : {}),
            'Access-Control-Allow-Origin': origin || (referer ? new URL(referer).origin : '*'),
          },
        }
      );
    }

    return new Response(JSON.stringify(data), {
      status: upstreamStatus >= 400 ? upstreamStatus : 200,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': '*',
      }
    });
    });
    return result;
  } catch (error) {
    console.error('Proxy error:', error);
    return new Response(JSON.stringify({ error: 'Failed to fetch from target' }), { 
      status: 500,
      headers: {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
      }
    });
  }
};

export const OPTIONS: APIRoute = async () => {
  return new Response(null, {
    status: 204,
    headers: {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': '*',
      'Access-Control-Max-Age': '86400',
    }
  });
};
