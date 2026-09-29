import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { transformSync } from 'esbuild';
import { buildUpstreamQueryUrl } from '../src/services/api';

const componentPath = 'src/components/BusTracker.astro';

// Small, hand-written catalog: line 1 has an out-and-back pair of routes that
// share both termini (so a route and its reverse serve the same streets), plus
// an outbound variant that detours through a stop far away from the main one.
// Two buses at the same coordinates in opposite senses can therefore only be
// told apart by the direction the live feed reports for each of them.
const CATALOG = {
  actualizacion: {
    lineas: [
      {
        id: 100,
        lin_comer: '1',
        nombre_orig: 'Abente y Lago',
        nombre_dest: 'Pza. de Pablo Iglesias',
        color: '982135',
        rutas: [
          { ruta: 10000, paradas: [1, 2, 3, 4, 5] },        // out
          { ruta: 10001, paradas: [5, 4, 3, 2, 1] },        // back
          { ruta: 10002, paradas: [1, 2, 6, 3, 4, 5] },     // out, via the detour
        ],
      },
    ],
  },
  paradas: {
    1: { id: 1, nombre: 'Abente y Lago', posy: 43.37, posx: -8.4, enlaces: [100] },
    2: { id: 2, nombre: 'Plaza de Galicia', posy: 43.36, posx: -8.4, enlaces: [100] },
    3: { id: 3, nombre: 'Cantón Grande', posy: 43.35, posx: -8.4, enlaces: [100] },
    4: { id: 4, nombre: 'Marineda City', posy: 43.34, posx: -8.4, enlaces: [100] },
    5: { id: 5, nombre: 'Pza. de Pablo Iglesias', posy: 43.33, posx: -8.4, enlaces: [100] },
    6: { id: 6, nombre: 'Ronda de Outeiro', posy: 43.355, posx: -8.5, enlaces: [100] },
  },
};

const STRINGS: Record<string, string> = {
  trackerFoundBus: 'Detectado: bus {num} de la línea {line} hacia {dest}',
  trackerNearStop: 'Estás cerca de',
  trackerSelectDest: 'Elige tu parada de bajada:',
  trackerDestRemaining: '{m} paradas · ~{min} min más',
  trackerDestTrack: 'Seguir este bus hasta aquí',
  trackerNoStopsAhead: 'El bus está llegando a su final de línea y no le quedan paradas por delante.',
  trackerFullLine: 'Paradas de la línea en ambos sentidos. Las destacadas son las que te quedan por delante en este viaje.',
  trackerPositionUnknown: 'No se pudo ubicar el bus con precisión, así que se muestran todas las paradas de la línea.',
  trackerCancel: 'Cambiar de bus',
  trackerNotFound: 'No se encontró ningún bus',
  trackerStarted: 'Seguimiento iniciado',
  trackerScanning: 'Rastreando...',
};

type Tracker = {
  showDetected(found: { bus: any; line: any }): void;
  connectedCallback(): void;
  querySelectorAll(sel: string): any[];
  querySelector(sel: string): any;
};

const MARKUP = (() => {
  const astro = readFileSync(componentPath, 'utf8');
  return astro.slice(
    astro.indexOf('<bus-tracker>'),
    astro.indexOf('</bus-tracker>') + '</bus-tracker>'.length,
  );
})();

/**
 * Evaluates the component's real script once, with the imports it pulls in
 * (storage, API, tracking, geolocation, i18n) stubbed. The script registers
 * itself as <bus-tracker>, so a mount just clones the markup onto that tag.
 */
(() => {
  const astro = readFileSync(componentPath, 'utf8');
  const script = astro
    .slice(astro.indexOf('<script>') + '<script>'.length, astro.indexOf('</script>'))
    .replace(/^\s*import .*$/gm, '');

  new Function(
    'storage', 'API', 'tracking', 'getPosition', 'getTranslations',
    transformSync(script, { loader: 'ts' }).code,
  )(
    { getCatalog: () => CATALOG },
    { getMapData: async () => ({ resultado: 'OK', mapas: [{ buses: [] }] }) },
    { set: () => {} },
    async () => null,
    () => STRINGS,
  );
})();

/** Mounts a fresh tracker element with the component's real markup. */
function mountTracker(): { el: Tracker; doc: any; line: any } {
  const doc = globalThis.document;
  doc.documentElement.setAttribute('data-lang', 'es');

  // Parse the markup detached: the element upgrades on insertion and
  // connectedCallback() wires up listeners to its children, so they must be in
  // place before it runs.
  const host = doc.createElement('div');
  host.innerHTML = MARKUP;
  const el = host.querySelector('bus-tracker') as unknown as Tracker;
  doc.body.innerHTML = '';
  doc.body.appendChild(host);

  return { el, doc, line: CATALOG.actualizacion.lineas[0] };
}

/** Runs showDetected() and returns the whole list it rendered. */
function detect(el: Tracker, doc: any, line: any, bus: { num: number; posy: any; posx: any; sentido: string }) {
  el.showDetected({ bus: { bus: bus.num, posy: bus.posy, posx: bus.posx, sentido: bus.sentido }, line });
  return [...doc.querySelectorAll('.tracker-dest-item')].map((node: any) => node.getAttribute('data-stop-id'));
}

/**
 * The stops the list marks as still ahead of the bus, in the order the bus will
 * serve them. The list itself follows the order of the line, so the ahead ones
 * are ordered by the stops remaining each of them is given.
 */
function aheadOf(doc: any) {
  return [...doc.querySelectorAll('.tracker-dest-item-wrap.is-ahead .tracker-dest-item')]
    .map((node: any) => ({
      id: node.getAttribute('data-stop-id'),
      remaining: parseInt(node.querySelector('small').textContent, 10),
    }))
    .sort((a: any, b: any) => a.remaining - b.remaining)
    .map((x: any) => x.id);
}

describe('BusTracker destination list', () => {
  it('lists every stop of the line, in both directions', () => {
    const { el, doc, line } = mountTracker();
    const stops = detect(el, doc, line, { num: 1, posy: 43.35, posx: -8.4, sentido: '0' });

    // 1..5 are the stops of the out-and-back pair, 6 the one the detour variant
    // adds: the whole line, without repeats.
    expect(stops).toEqual(['1', '2', '3', '4', '5', '6']);
  });

  it('marks the stops ahead in the direction the bus is travelling', () => {
    const { el, doc, line } = mountTracker();
    // Both buses are at Cantón Grande (stop 3): one heading out, one heading back.
    detect(el, doc, line, { num: 1, posy: 43.35, posx: -8.4, sentido: '0' });
    expect(aheadOf(doc)).toEqual(['4', '5']);

    detect(el, doc, line, { num: 2, posy: 43.35, posx: -8.4, sentido: '1' });
    expect(aheadOf(doc)).toEqual(['2', '1']);
  });

  it('ends the stops ahead at the terminus of that direction', () => {
    const { el, doc, line } = mountTracker();
    detect(el, doc, line, { num: 3, posy: 43.35, posx: -8.4, sentido: '0' });
    const ahead = aheadOf(doc);
    const last = CATALOG.paradas[ahead[ahead.length - 1] as keyof typeof CATALOG.paradas];

    expect(last.nombre).toBe(CATALOG.actualizacion.lineas[0].nombre_dest);
  });

  it('uses the route variant that matches the bus position', () => {
    const { el, doc, line } = mountTracker();
    // On the detour, by "Ronda de Outeiro" (stop 6), which the main outbound
    // route does not serve: the stops ahead would be 4 and 5 only.
    detect(el, doc, line, { num: 4, posy: 43.355, posx: -8.5, sentido: '0' });

    expect(aheadOf(doc)).toEqual(['3', '4', '5']);
  });

  it('only offers tracking to the stops ahead of the bus', () => {
    const { el, doc, line } = mountTracker();
    detect(el, doc, line, { num: 5, posy: 43.35, posx: -8.4, sentido: '0' });

    const trackable = [...doc.querySelectorAll('.tracker-dest-track')]
      .map((node: any) => node.getAttribute('data-stop-id'));
    expect(trackable).toEqual(['4', '5']);
    // The whole line is still listed and can be opened.
    expect(doc.querySelectorAll('.tracker-dest-item').length).toBe(6);
  });

  it('marks nothing as ahead when the bus has no usable position', () => {
    const { el, doc, line } = mountTracker();
    const stops = detect(el, doc, line, { num: 6, posy: null, posx: null, sentido: '0' });

    expect(stops).toEqual(['1', '2', '3', '4', '5', '6']);
    expect(aheadOf(doc)).toEqual([]);
    expect(doc.querySelector('.tracker-dest-note')?.textContent).toBe(STRINGS.trackerPositionUnknown);
  });

  it('explains that there are no stops left when the bus is at the terminus', () => {
    const { el, doc, line } = mountTracker();
    // At the end of the outbound direction ("Pza. de Pablo Iglesias").
    detect(el, doc, line, { num: 7, posy: 43.33, posx: -8.4, sentido: '0' });

    expect(aheadOf(doc)).toEqual([]);
    expect(doc.querySelector('.tracker-dest-note')?.textContent).toBe(STRINGS.trackerNoStopsAhead);
    // The line is still listed, so the stop can be looked up.
    expect(doc.querySelectorAll('.tracker-dest-item').length).toBe(6);
  });

  it('keeps the action row hidden until its stop is opened', () => {
    const { el, doc, line } = mountTracker();
    detect(el, doc, line, { num: 8, posy: 43.35, posx: -8.4, sentido: '0' });

    const first = doc.querySelector('.tracker-dest-item');
    const actions = first.closest('.tracker-dest-item-wrap').querySelector('.tracker-dest-actions');
    expect(actions.hasAttribute('hidden')).toBe(true);

    first.click();
    expect(actions.hasAttribute('hidden')).toBe(false);

    doc.querySelectorAll('.tracker-dest-item')[1].click();
    expect(actions.hasAttribute('hidden')).toBe(true);
  });

  it('keeps every row at full height inside the scrolling list', () => {
    // The list is a flex column with a max-height and every row clips its
    // overflow, which drops the automatic minimum size of the row: without
    // flex-shrink: 0 a line with dozens of stops squeezed every row down to a
    // couple of pixels and clipped the stop name and the buttons away. Layout
    // is not observable in the DOM, so this guards the rule itself.
    const css = readFileSync(componentPath, 'utf8');
    for (const selector of ['.tracker-dest-item-wrap', '.tracker-dest-actions']) {
      const rule = css.match(new RegExp(`${selector.replace('.', '\\.')} \\{[^}]*\\}`))?.[0] ?? '';
      expect(`${selector}: ${rule}`).toMatch(/flex-shrink:\s*0/);
    }
  });

  it('escapes stop names coming from the external feed', () => {
    const { el, doc, line } = mountTracker();
    (CATALOG.paradas as any)[4].nombre = '<img src=x onerror=alert(1)>';
    try {
      detect(el, doc, line, { num: 8, posy: 43.35, posx: -8.4, sentido: '0' });
      const label = doc.querySelector('.tracker-dest-item[data-stop-id="4"] .tracker-dest-name');
      expect(label.textContent).toBe('<img src=x onerror=alert(1)>');
      expect(label.querySelector('img')).toBeNull();
    } finally {
      (CATALOG.paradas as any)[4].nombre = 'Marineda City';
    }
  });
});

describe('buildUpstreamQueryUrl', () => {
  // func=99 carries "100&mostrar=B" inside `dato`. Percent-encoding the whole
  // value makes the upstream read the line id as "100%26mostrar%3db" and reply
  // with an error instead of the bus map, which is what the native app got.
  it('splits multi-pair dato values into separate query params', () => {
    expect(buildUpstreamQueryUrl(99, '100&mostrar=B'))
      .toBe('https://itranvias.com/queryitr_v3.php?func=99&dato=100&mostrar=B');
    expect(buildUpstreamQueryUrl(99, '42&mostrar=PRB'))
      .toBe('https://itranvias.com/queryitr_v3.php?func=99&dato=42&mostrar=PRB');
  });

  it('leaves single-value dato untouched', () => {
    expect(buildUpstreamQueryUrl(0, '523'))
      .toBe('https://itranvias.com/queryitr_v3.php?func=0&dato=523');
    expect(buildUpstreamQueryUrl(7, '20160101T000000_es_0_20160101T000000'))
      .toBe('https://itranvias.com/queryitr_v3.php?func=7&dato=20160101T000000_es_0_20160101T000000');
  });
});
