import type { APIRoute } from 'astro';
import { getCatalog } from '../../services/catalog';
import { getArrivals } from '../../services/arrivals';
import { planRoute } from '../../services/planner';
import { busAlternatives, parseClock, timeOptions } from '../../services/schedule';
import { errorResponse, planText, textResponse } from '../../services/legacyText';

// GET /j2me/plan?o=523&d=180[&t=14:30]
// t = llegar antes de esa hora (HH:MM). Sin t, salir ahora.
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
    const now = new Date();
    const target = parseClock(url.searchParams.get('t'), now);

    if (!planned) return errorResponse('nostop', 'Parada de origen o destino desconocida', 404);

    if (!planned.options.length) {
      return textResponse(
        `O ${originId}|${planned.origin.name}\r\nD ${destId}|${planned.destination.name}\r\nEND\r\n`,
        { maxAge: 60 },
      );
    }

    // Llegadas en vivo de la parada de origen: dan la espera real del primer bus.
    const arrivals = new Map<number, number[]>();
    try {
      const live = await getArrivals(catalog, originId);
      for (const line of live.lines) arrivals.set(line.lineId, line.minutes);
    } catch {
      // sin datos en vivo se responde solo con la duración estimada
    }

    const timings = timeOptions(planned.options, arrivals, now, target);
    const alternatives = planned.options.map((option) =>
      busAlternatives(option, arrivals.get(option.legs[0].lineId) ?? [], now, target),
    );
    return textResponse(
      planText(catalog, originId, destId, planned.options, timings, alternatives),
      {
        maxAge: 30,
      },
    );
  } catch (error) {
    console.error('j2me plan error', error);
    return errorResponse('upstream', 'No se pudo calcular la ruta', 502);
  }
};
