// Wire format for the Java ME client: plain text, one record per line, `|` as
// field separator. No JSON, no nested structures, nothing the MIDlet has to
// parse with buffers bigger than one line.
//
// The client stores the catalog once and only sends back ids, so the wire stays
// small: ~16 KB for the whole catalog (~13 KB of it is stop names).
//
// Formato para el cliente Java ME: texto plano, un registro por línea y `|`
// como separador. Nada de JSON ni de anidamiento.

import type { ArrivalLine } from './arrivals';
import type { LegacyCatalog, LegacyStop } from './catalog';
import { lineLabel } from './catalog';
import type { PlanOption } from './planner';
import type { OptionTiming } from './schedule';
import { stopName } from './catalog';

const LINE_ENDING = '\r\n';

export function sanitize(value: unknown): string {
  return String(value ?? '')
    .replace(/[\r\n\t]+/g, ' ')
    .replace(/\|/g, '/')
    .replace(/\s+/g, ' ')
    .trim();
}

function lines(records: string[]): string {
  return records.join(LINE_ENDING) + LINE_ENDING;
}

export function textResponse(body: string, init: { maxAge?: number; status?: number } = {}): Response {
  const maxAge = init.maxAge ?? 0;
  return new Response(body, {
    status: init.status ?? 200,
    headers: {
      // charset=utf-8: MIDP 2.0 / S60 decode it fine and the stop names keep
      // their accents. UTF-8 is mandatory in CLDC, ISO-8859-1 is not.
      'Content-Type': 'text/plain; charset=utf-8',
      'Cache-Control': maxAge > 0 ? `public, max-age=${maxAge}` : 'no-store',
      'X-Content-Type-Options': 'nosniff',
    },
  });
}

export function errorResponse(code: string, message: string, status = 400): Response {
  return textResponse(lines([`ERR ${sanitize(code)}`, `MSG ${sanitize(message)}`, 'END']), { status });
}

/**
 * Catalog listing.
 *
 * VER <fecha> <lineas> <paradas>
 * L <id>|<numero>|<origen>|<destino>
 * P <id>|<nombre>|<lat>|<lng>
 * END
 */
export function catalogText(catalog: LegacyCatalog): string {
  const records: string[] = [
    `VER ${sanitize(catalog.version)} ${catalog.lines.length} ${catalog.stops.length}`,
  ];

  for (const line of catalog.lines) {
    records.push(
      `L ${line.id}|${sanitize(line.code)}|${sanitize(line.from)}|${sanitize(line.to)}`,
    );
  }

  for (const stop of catalog.stops) {
    records.push(
      `P ${stop.id}|${sanitize(stop.name)}|${stop.lat.toFixed(5)}|${stop.lng.toFixed(5)}`,
    );
  }

  records.push('END');
  return lines(records);
}

/**
 * Arrivals at one stop.
 *
 * P <id>|<nombre>|<lat>|<lng>
 * L <numero>|<min1,min2,min3>      (vacío cuando no hay bus cerca)
 * T <hh:mm>
 * END
 *
 * Lines that serve the stop are always listed, so the screen shows the whole
 * panel even when the upstream has no live buses for a line.
 */
export function stopText(
  catalog: LegacyCatalog,
  stop: LegacyStop,
  arrivals: ArrivalLine[],
  now = new Date(),
): string {
  const serving = new Set<number>();
  for (const lineId of catalog.linesAtStop[stop.id] ?? []) serving.add(lineId);
  for (const arrival of arrivals) serving.add(arrival.lineId);

  const byId = new Map<number, ArrivalLine>();
  for (const arrival of arrivals) byId.set(arrival.lineId, arrival);

  const records: string[] = [
    `P ${stop.id}|${sanitize(stop.name)}|${stop.lat.toFixed(5)}|${stop.lng.toFixed(5)}`,
  ];

  const sorted = [...serving]
    .map((lineId) => ({
      lineId,
      code: catalog.linesById[lineId]?.code ?? String(lineId),
      minutes: byId.get(lineId)?.minutes ?? [],
    }))
    .sort((a, b) => a.code.localeCompare(b.code, 'es', { numeric: true }));

  for (const entry of sorted) {
    records.push(`L ${sanitize(entry.code)}|${entry.minutes.join(',')}`);
  }

  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');
  records.push(`T ${hh}:${mm}`);
  records.push('END');

  return lines(records);
}

/**
 * Nearby stops.
 *
 * S <id>|<nombre>|<metros>
 * END
 */
export function nearbyText(entries: Array<{ stop: LegacyStop; meters: number }>): string {
  const records = entries.map(
    (entry) => `S ${entry.stop.id}|${sanitize(entry.stop.name)}|${entry.meters}`,
  );
  records.push('END');
  return lines(records);
}

/**
 * Planned routes. Stop names are not sent: the client already has the catalog.
 *
 * O <id>|<nombre>
 * D <id>|<nombre>
 * R <n>|D|<linea>|<desde>|<hasta>|<paradas>|<min>|<andando_m>|<espera>|<llegada>|<tarde>
 * R <n>|T|<l1>|<desde>|<bajo>|<p1>|<m1>|<andando_m>|<l2>|<sube>|<hasta>|<p2>|<m2>|<espera>|<llegada>|<tarde>
 * END
 *
 * <espera> son los minutos hasta el bus en la parada de origen (vacío si no
 * hay datos en vivo), <llegada> la hora HH:MM estimada y <tarde> 1 cuando con
 * ese bus ya no se cumple la hora pedida.
 */
export function planText(
  catalog: LegacyCatalog,
  originId: number,
  destId: number,
  options: PlanOption[],
  timings: OptionTiming[] = [],
): string {
  const records: string[] = [
    `O ${originId}|${sanitize(stopName(catalog, originId))}`,
    `D ${destId}|${sanitize(stopName(catalog, destId))}`,
  ];

  options.forEach((option, index) => {
    const n = index + 1;
    const timing = timings[index];
    const tail = timing
      ? `|${timing.waitMinutes ?? ''}|${timing.arriveAt}|${timing.late ? 1 : 0}`
      : '|||';

    if (option.type === 'direct') {
      const leg = option.legs[0];
      records.push(
        `R ${n}|D|${sanitize(leg.line)}|${leg.fromStopId}|${leg.toStopId}|${leg.stops}|${leg.minutes}|${option.walkMeters}${tail}`,
      );
      return;
    }

    const [first, second] = option.legs;
    records.push(
      `R ${n}|T|${sanitize(first.line)}|${first.fromStopId}|${first.toStopId}|${first.stops}|${first.minutes}|${option.walkMeters}|${sanitize(second.line)}|${second.fromStopId}|${second.toStopId}|${second.stops}|${second.minutes}${tail}`,
    );
  });

  records.push('END');
  return lines(records);
}

/** Human summary used by the /m/ pages (and by tests). */
export function planSummary(catalog: LegacyCatalog, option: PlanOption): string {
  const walk = (meters: number) => (meters > 0 ? ` y ${meters} m andando` : '');
  if (option.type === 'direct') {
    const leg = option.legs[0];
    return `${lineLabel(catalog.linesById[leg.lineId])}: ${stopName(catalog, leg.fromStopId)} a ${stopName(catalog, leg.toStopId)} (${leg.stops} paradas, ~${option.totalMinutes} min)`;
  }
  const [first, second] = option.legs;
  const transfer =
    first.toStopId === second.fromStopId
      ? stopName(catalog, first.toStopId)
      : `${stopName(catalog, first.toStopId)} a ${stopName(catalog, second.fromStopId)}${walk(second.walkMeters)}`;
  return `Línea ${first.line} ${first.stops} paradas, transbordo en ${transfer}, línea ${second.line} ${second.stops} paradas (~${option.totalMinutes} min)`;
}
