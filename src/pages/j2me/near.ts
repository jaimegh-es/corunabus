import type { APIRoute } from 'astro';
import { getCatalog } from '../../services/catalog';
import { nearbyStops } from '../../services/planner';
import { errorResponse, nearbyText, textResponse } from '../../services/legacyText';

// GET /j2me/near?lat=43.37&lon=-8.40&r=800&n=5
// Used when the phone has GPS but the client prefers not to keep the whole
// stop list in memory; the MIDlet can also do this locally with its cached
// catalog, so this endpoint is optional.
export const GET: APIRoute = async ({ url }) => {
  const lat = Number(url.searchParams.get('lat'));
  const lon = Number(url.searchParams.get('lon'));
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return errorResponse('param', 'Faltan lat/lon');
  }

  const radius = Number(url.searchParams.get('r') ?? 800);
  const max = Number(url.searchParams.get('n') ?? 5);

  try {
    const catalog = await getCatalog();
    const entries = nearbyStops(
      catalog,
      lat,
      lon,
      Number.isFinite(radius) ? Math.min(Math.max(radius, 50), 5000) : 800,
      Number.isFinite(max) ? Math.min(Math.max(max, 1), 20) : 5,
    );
    return textResponse(nearbyText(entries), { maxAge: 30 });
  } catch (error) {
    console.error('j2me near error', error);
    return errorResponse('upstream', 'No se pudieron buscar paradas cercanas', 502);
  }
};
