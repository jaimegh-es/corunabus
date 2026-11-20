import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { buildLegacyCatalog, getCatalog, normalizeText } from '../src/services/catalog';
import { haversineMeters, nearbyStops, planDirect, planRoute, planTransfer } from '../src/services/planner';
import { catalogText, nearbyText, planText, sanitize, stopText } from '../src/services/legacyText';

const payload = JSON.parse(readFileSync('research/catalog.json', 'utf8'));
const catalog = buildLegacyCatalog(payload.iTranvias.actualizacion);

describe('legacy catalog', () => {
  it('keeps every line and stop from the upstream payload', () => {
    expect(catalog.lines.length).toBe(26);
    expect(catalog.stops.length).toBeGreaterThan(500);
    expect(catalog.linesById[1400].code).toBe('14');
    expect(catalog.stopsById[1].name).toBe('Porta Real');
  });

  it('knows which lines serve a stop', () => {
    expect(catalog.linesAtStop[523]).toContain(100);
  });

  it('does not let separators leak into the wire format', () => {
    expect(sanitize('Porta | Real\r\nnorte')).toBe('Porta / Real norte');
    expect(normalizeText('  Av.  de   la Marina ')).toBe('Av. de la Marina');
  });
});

describe('planner', () => {
  it('finds a direct line between two stops on the same route', () => {
    const direct = planDirect(catalog, 523, 68);
    expect(direct.length).toBeGreaterThan(0);
    expect(direct[0].legs[0].line).toBe('1');
    expect(direct[0].legs[0].stops).toBe(19);
    expect(direct[0].totalMinutes).toBe(38);
  });

  it('finds a one-transfer alternative when no direct line exists', () => {
    const stops = Object.keys(catalog.stopsById).map(Number);
    const withTransfer = stops
      .filter((originId) => catalog.linesAtStop[originId]?.length)
      .map((originId) => ({ originId, options: planTransfer(catalog, originId, 68) }))
      .find((entry) => entry.options.length > 0);

    expect(withTransfer).toBeTruthy();
    const option = withTransfer!.options[0];
    expect(option.type).toBe('transfer');
    expect(option.legs).toHaveLength(2);
    expect(option.legs[1].line).not.toBe(option.legs[0].line);
    expect(option.walkMeters).toBeLessThanOrEqual(200);
  });

  it('returns options ordered by estimated time', () => {
    const planned = planRoute(catalog, 523, 68);
    expect(planned).not.toBeNull();
    const times = planned!.options.map((option) => option.totalMinutes);
    expect(times).toEqual([...times].sort((a, b) => a - b));
  });

  it('measures distance and nearby stops', () => {
    expect(haversineMeters(43.3623, -8.4115, 43.3623, -8.4115)).toBe(0);
    const near = nearbyStops(catalog, 43.3701, -8.3958, 800, 5);
    expect(near.length).toBeGreaterThan(0);
    expect(near[0].meters).toBeLessThanOrEqual(near[near.length - 1].meters);
  });
});

describe('text wire format', () => {
  it('starts the catalog with the version and every line/stop', () => {
    const text = catalogText(catalog);
    const records = text.trimEnd().split('\r\n');
    expect(records[0]).toMatch(/^VER \S+ 26 \d+$/);
    expect(records.filter((record) => record.startsWith('L '))).toHaveLength(26);
    expect(records.filter((record) => record.startsWith('P '))).toHaveLength(catalog.stops.length);
    expect(records[records.length - 1]).toBe('END');
  });

  it('lists every serving line at a stop, with minutes when there are buses', () => {
    const text = stopText(
      catalog,
      catalog.stopsById[523],
      [
        { lineId: 100, code: '1', minutes: [7, 21] },
        { lineId: 1400, code: '14', minutes: [] },
      ],
    );
    expect(text).toMatch(/^P 523\|.+\|43\./m);
    expect(text).toContain('L 1|7,21');
    expect(text).toContain('L 14|');
    expect(text.trimEnd().split('\r\n').at(-1)).toBe('END');
  });

  it('keeps a transfer record to 16 fields (13 + espera, llegada y tarde)', () => {
    const planned = planRoute(catalog, 523, 68)!;
    const transfer = planned.options.find((option) => option.type === 'transfer');
    expect(transfer).toBeTruthy();

    const record = planText(catalog, 523, 68, [transfer!], [
      { waitMinutes: 7, departAt: '14:20', arriveAt: '14:45', late: false },
    ])
      .split('\r\n')
      .find((line) => line.startsWith('R '))!;

    const fields = record.split('|');
    expect(fields).toHaveLength(16);
    expect(fields.slice(-3)).toEqual(['7', '14:45', '0']);
  });

  it('serialises nearby stops in metres', () => {
    const text = nearbyText([{ stop: catalog.stopsById[1], meters: 42 }]);
    expect(text).toBe('S 1|Porta Real|42\r\nEND\r\n');
  });
});

describe('catalog cache', () => {
  it('serves the second call from memory instead of the network', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = (async () => {
      calls += 1;
      return new Response(JSON.stringify(payload), { status: 200 });
    }) as typeof fetch;

    try {
      const first = await getCatalog({ refresh: true });
      const second = await getCatalog();
      expect(first.stops.length).toBe(second.stops.length);
      expect(calls).toBe(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
