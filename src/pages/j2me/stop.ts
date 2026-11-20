import type { APIRoute } from 'astro';
import { getCatalog } from '../../services/catalog';
import { getArrivals } from '../../services/arrivals';
import { errorResponse, stopText, textResponse } from '../../services/legacyText';

// GET /j2me/stop?id=523[&refresh=1]
// Arrivals panel for one stop, one line per record.
export const GET: APIRoute = async ({ url }) => {
  const stopId = Number(url.searchParams.get('id'));
  if (!Number.isInteger(stopId) || stopId <= 0) {
    return errorResponse('param', 'Falta el parametro id');
  }

  try {
    const catalog = await getCatalog();
    const stop = catalog.stopsById[stopId];
    if (!stop) return errorResponse('nostop', 'Parada desconocida', 404);

    let arrivals: Awaited<ReturnType<typeof getArrivals>>['lines'] = [];
    try {
      arrivals = (await getArrivals(catalog, stopId, { refresh: url.searchParams.get('refresh') === '1' })).lines;
    } catch (error) {
      // The stop still has value without live times: the screen shows the lines
      // that serve it and no minutes.
      console.error('j2me stop arrivals error', error);
    }

    return textResponse(stopText(catalog, stop, arrivals), { maxAge: 5 });
  } catch (error) {
    console.error('j2me stop error', error);
    return errorResponse('upstream', 'No se pudo consultar la parada', 502);
  }
};
