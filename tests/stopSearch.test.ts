import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { buildLegacyCatalog } from '../src/services/catalog';
import { normalizeQuery, searchStops } from '../src/services/stopSearch';

const payload = JSON.parse(readFileSync('research/catalog.json', 'utf8'));
const catalog = buildLegacyCatalog(payload.iTranvias.actualizacion);

describe('stop search', () => {
  it('drops accents, case and punctuation', () => {
    expect(normalizeQuery('  Av. de la MARINA, 27 ')).toBe('av de la marina 27');
  });

  it('finds a comma stop without typing the comma', () => {
    const results = searchStops(catalog, 'manuel azaña 27');
    expect(results[0]?.name).toContain('Manuel Azaña');
  });

  it('finds the same stop with the words in another order', () => {
    const results = searchStops(catalog, 'azaña manuel');
    expect(results[0]?.name).toContain('Manuel Azaña');
  });

  it('still resolves a plain stop id', () => {
    expect(searchStops(catalog, '523')[0]?.id).toBe(523);
  });

  it('does not invent results for unrelated text', () => {
    expect(searchStops(catalog, 'zzzzzz')).toHaveLength(0);
  });
});
