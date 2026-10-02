/**
 * Where a tracked bus stands on its line, for the phase-2 tracking bar.
 *
 * The live line feed (func=2) is the only source that works when the bus is not
 * listed at the destination stop yet. It needs three translations that are easy
 * to get wrong and impossible to notice at runtime, so they live here, pure and
 * unit-tested:
 *
 * - func=2 names the stop id `parada` (not `id`) and groups stops by `sentido`.
 *   Reading the wrong field throws, the caller swallows it, and the whole
 *   position fallback silently stops working: tracking then only ends when the
 *   destination feed happens to report the bus.
 * - Both directions share streets, so only the direction that reports the bus
 *   is its direction of travel. Matching across directions can place the bus on
 *   its own return and end the journey at once.
 * - The bus is reported at the stop it is heading to (with the distance to it),
 *   not the one it left, so "listed at the destination" is still approaching and
 *   only "listed past it" means it went through.
 *
 * Dónde está el bus en su línea, para la barra de seguimiento (fase 2).
 */

/** A stop the feed reports the bus at, plus how far it still is from it (km). */
export interface BusStopRef {
    id: number;
    km: number;
}

/** Close enough to the stop to count as being there. */
const ARRIVED_KM = 0.05;

const stopsOf = (route: any): number[] =>
    Array.isArray(route?.paradas) ? route.paradas.map((p: any) => parseInt(p.toString())) : [];

const sameDirection = (route: any, reference: number[]): boolean => {
    const rp = stopsOf(route);
    return (
        rp.length >= 2 &&
        reference.length >= 2 &&
        rp[0] === reference[0] &&
        rp[rp.length - 1] === reference[reference.length - 1]
    );
};

/**
 * Stops of `lineData` (a func=2 answer) that report `busId`, restricted to the
 * direction the bus travels in. Empty when the feed doesn't list it anywhere.
 */
export function readBusStops(lineData: any, busId: string | number): { sentido: string | null; stops: BusStopRef[] } {
    const dirs = Array.isArray(lineData?.paradas) ? lineData.paradas : [];
    const isMine = (b: any) => !!b && !!b.bus && b.bus.toString() === busId.toString();
    let sentido: string | null = null;
    const stops: BusStopRef[] = [];

    for (const dir of dirs) {
        if (!Array.isArray(dir.paradas)) continue;
        if (!dir.paradas.some((p: any) => Array.isArray(p.buses) && p.buses.some(isMine))) continue;
        if (sentido === null) sentido = String(dir.sentido);
        if (String(dir.sentido) !== sentido) continue;
        for (const p of dir.paradas) {
            if (!Array.isArray(p.buses) || !p.buses.some(isMine)) continue;
            // `parada` is the real field name; `id` kept as a fallback so a
            // differently shaped answer still works instead of throwing.
            const pid = p.parada ?? p.id;
            if (pid === undefined || pid === null) continue;
            const km = parseFloat((p.buses.find(isMine) as any)?.distancia);
            stops.push({ id: parseInt(pid.toString()), km: isNaN(km) ? 0 : km });
        }
    }

    return { sentido, stops };
}

/** Verdict for the journey, given the bus's stops and the destination stop. */
export interface RouteDecision {
    /** 'unknown': the line's routes can't place this bus against the stop. */
    kind: 'unknown' | 'ahead' | 'arrived' | 'past';
    stopsLeft: number;
    busIndex: number;
    destIndex: number;
    /** Distance to the stop the bus is reported at, in km. */
    km: number;
}

const UNDECIDED: RouteDecision = { kind: 'unknown', stopsLeft: -1, busIndex: -1, destIndex: -1, km: 0 };

/**
 * Places the bus against the destination on the routes of its direction. When
 * several variants fit, the one that leaves the most stops to travel wins: the
 * opposite tie-break would end the journey on a variant where the bus merely
 * appears after the stop.
 */
export function resolveBusRoute(
    routes: any[],
    sentido: string | null,
    stops: BusStopRef[],
    destinationStopId: number,
): RouteDecision {
    const list = Array.isArray(routes) ? routes : [];
    if (!stops.length) return UNDECIDED;

    const ref = list[parseInt(sentido ?? '-1', 10)];
    const refStops = stopsOf(ref);
    const candidates = refStops.length
        ? list.filter((r: any) => sameDirection(r, refStops))
        : list.filter((r: any) => stopsOf(r).length >= 2);

    const dest = parseInt(destinationStopId.toString());

    let best: RouteDecision | null = null;
    for (const route of candidates) {
        const rp = stopsOf(route);
        const d = rp.indexOf(dest);
        if (d === -1) continue;
        for (const stop of stops) {
            const b = rp.indexOf(stop.id);
            if (b === -1) continue;
            if (best && b >= best.busIndex) continue;
            best = { kind: 'ahead', stopsLeft: -1, busIndex: b, destIndex: d, km: stop.km };
        }
    }
    if (!best) return UNDECIDED;

    if (best.busIndex > best.destIndex) return { ...best, kind: 'past' };
    if (best.busIndex === best.destIndex && best.km <= ARRIVED_KM) {
        return { ...best, kind: 'arrived' };
    }
    return { ...best, stopsLeft: Math.max(1, best.destIndex - best.busIndex) };
}
