// Stop search shared by the lightweight /m/ pages.
// Búsqueda de paradas por nombre o número, sin acentos, sin mayúsculas y
// tolerante a puntuación y orden: "manuel azaña 27" encuentra "Manuel Azaña, 27".

import type { LegacyCatalog, LegacyStop } from './catalog';

export function normalizeQuery(value: string): string {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function searchStops(catalog: LegacyCatalog, query: string, limit = 20): LegacyStop[] {
  const q = normalizeQuery(query);
  if (!q) return [];

  const exactId = /^\d+$/.test(q) ? Number(q) : 0;
  const tokens = q.split(' ').filter(Boolean);

  const ranked: Array<{ stop: LegacyStop; score: number }> = [];

  for (const stop of catalog.stops) {
    if (exactId && stop.id === exactId) {
      ranked.push({ stop, score: -1 });
      continue;
    }

    const name = normalizeQuery(stop.name);
    let score: number;
    if (name === q) score = 0;
    else if (name.startsWith(q)) score = 1;
    else if (name.includes(q)) score = 2;
    else if (tokens.every((token) => name.includes(token))) score = 3;
    else continue;

    ranked.push({ stop, score });
  }

  ranked.sort((a, b) => a.score - b.score || a.stop.id - b.stop.id);
  return ranked.slice(0, limit).map((item) => item.stop);
}
