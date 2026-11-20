import type { APIRoute } from 'astro';
import { getCatalog } from '../../services/catalog';
import { planRoute } from '../../services/planner';
import { errorResponse, planText, textResponse } from '../../services/legacyText';

// GET /j2me/plan?o=523&d=180
// The MIDlet sends two stop ids and gets back ready-to-render options. The
// route graph never travels to the phone.
export const GET: APIRoute = async ({ url }) => {
  const originId = Number(url.searchParams.get('o') ?? url.searchParams.get('from'));
  const destId = Number(url.searchParams.get('d') ?? url.searchParams.get('to'));

  if (!Number.isInteger(originId) || !Number.isInteger(destId) || originId <= 0 || destId <= 0) {
    return errorResponse('param', 'Faltan las paradas o y d');
  }
  if (originId === destId) {
    return errorResponse('same', 'Origen y destino son la misma parada');
  }

  try {
    const catalog = await getCatalog();
    const planned = planRoute(catalog, originId, destId);

    if (!planned) return errorResponse('nostop', 'Parada de origen o destino desconocida', 404);

    if (!planned.options.length) {
      return textResponse(
        `O ${originId}|${planned.origin.name}\r\nD ${destId}|${planned.destination.name}\r\nEND\r\n`,
        { maxAge: 60 },
      );
    }

    return textResponse(planText(catalog, originId, destId, planned.options), { maxAge: 60 });
  } catch (error) {
    console.error('j2me plan error', error);
    return errorResponse('upstream', 'No se pudo calcular la ruta', 502);
  }
};
