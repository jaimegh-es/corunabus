import type { APIRoute } from 'astro';
import { getCatalog } from '../../services/catalog';
import { catalogText, errorResponse, textResponse } from '../../services/legacyText';

// GET /j2me/catalog[?refresh=1]
// Full lines + stops listing for the MIDlet, which stores it in RMS and only
// asks again when the user presses "Actualizar datos".
export const GET: APIRoute = async ({ url }) => {
  try {
    const catalog = await getCatalog({ refresh: url.searchParams.get('refresh') === '1' });
    return textResponse(catalogText(catalog), { maxAge: 3600 });
  } catch (error) {
    console.error('j2me catalog error', error);
    return errorResponse('upstream', 'No se pudo leer el catalogo de itranvias', 502);
  }
};
