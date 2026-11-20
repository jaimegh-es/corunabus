// Route planning for the legacy clients. This is a server-side port of the
// direct + one-transfer search used by src/components/TripCalculator.astro, so
// the E71 MIDlet never has to hold the route graph in its tiny heap.
//
// It is intentionally the same heuristic and not a real timetable router:
// 2 minutes per stop, 3 minutes per transfer, walking at ~50 m/min, and only
// one transfer (a second one around a 200 m walk is enough for A Coruña).
//
// Planificador para clientes antiguos: mismo criterio que TripCalculator
// (directo + un transbordo, 2 min por parada, 200 m andando como máximo).

import type { LegacyCatalog, LegacyLine, LegacyStop } from './catalog';

const WALK_TRANSFER_METERS = 200;
const WALK_TO_BOARD_METERS = 600;
const MINUTES_PER_STOP = 2;
const MINUTES_PER_TRANSFER = 3;
const METERS_PER_MINUTE_WALK = 50;
const MAX_ROUTE_SCAN = 45;
const MAX_OPTIONS = 6;

export interface NearbyStop {
  stop: LegacyStop;
  meters: number;
}

export interface PlanLeg {
  lineId: number;
  /** Commercial line number, e.g. "14". */
  line: string;
  fromStopId: number;
  toStopId: number;
  stops: number;
  minutes: number;
  /** Walking meters before boarding (0 for the first leg at the origin stop). */
  walkMeters: number;
}

export type PlanOption =
  | {
      type: 'direct';
      legs: [PlanLeg];
      totalStops: number;
      totalMinutes: number;
      walkMeters: number;
    }
  | {
      type: 'transfer';
      legs: [PlanLeg, PlanLeg];
      totalStops: number;
      totalMinutes: number;
      walkMeters: number;
    };

export function haversineMeters(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const earthRadius = 6371000;
  const toRad = (value: number) => (value * Math.PI) / 180;
  const dLat = toRad(bLat - aLat);
  const dLng = toRad(bLng - aLng);
  const lat1 = toRad(aLat);
  const lat2 = toRad(bLat);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return Math.round(2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(h))));
}

export function nearbyStops(
  catalog: LegacyCatalog,
  lat: number,
  lng: number,
  radius = 800,
  max = 5,
): NearbyStop[] {
  const found: NearbyStop[] = [];
  for (const stop of catalog.stops) {
    if (!stop.lat || !stop.lng) continue;
    const meters = haversineMeters(lat, lng, stop.lat, stop.lng);
    if (meters <= radius) found.push({ stop, meters });
  }
  found.sort((a, b) => a.meters - b.meters);
  return found.slice(0, Math.max(1, max));
}

/** Stops within `meters` of the given stop, nearest first (includes itself). */
export function stopsAround(catalog: LegacyCatalog, stop: LegacyStop, meters: number): NearbyStop[] {
  const found: NearbyStop[] = [{ stop, meters: 0 }];
  for (const other of catalog.stops) {
    if (other.id === stop.id || !other.lat || !other.lng) continue;
    const distance = haversineMeters(stop.lat, stop.lng, other.lat, other.lng);
    if (distance <= meters) found.push({ stop: other, meters: distance });
  }
  found.sort((a, b) => a.meters - b.meters);
  return found.slice(0, 8);
}

function legsMinutes(stops: number, walkMeters: number): number {
  return stops * MINUTES_PER_STOP + Math.ceil(walkMeters / METERS_PER_MINUTE_WALK);
}

export function planDirect(catalog: LegacyCatalog, originId: number, destId: number): PlanOption[] {
  const results: PlanOption[] = [];
  if (originId === destId) return results;

  for (const line of catalog.lines) {
    let best: { stops: number; fromIndex: number; toIndex: number } | null = null;

    for (const route of line.routes) {
      const fromIndex = route.indexOf(originId);
      if (fromIndex === -1) continue;
      const toIndex = route.indexOf(destId);
      if (toIndex === -1 || toIndex <= fromIndex) continue;
      const stops = toIndex - fromIndex;
      if (!best || stops < best.stops) best = { stops, fromIndex, toIndex };
    }

    if (!best) continue;

    const leg: PlanLeg = {
      lineId: line.id,
      line: line.code,
      fromStopId: originId,
      toStopId: destId,
      stops: best.stops,
      minutes: legsMinutes(best.stops, 0),
      walkMeters: 0,
    };

    results.push({
      type: 'direct',
      legs: [leg],
      totalStops: best.stops,
      totalMinutes: leg.minutes,
      walkMeters: 0,
    });
  }

  return results.sort((a, b) => a.totalMinutes - b.totalMinutes);
}

export function planTransfer(catalog: LegacyCatalog, originId: number, destId: number): PlanOption[] {
  if (originId === destId) return [];

  const results = new Map<string, PlanOption>();
  const aroundCache = new Map<number, NearbyStop[]>();
  const originStop = catalog.stopsById[originId];
  if (!originStop) return [];

  const around = (stopId: number): NearbyStop[] => {
    const cached = aroundCache.get(stopId);
    if (cached) return cached;
    const stop = catalog.stopsById[stopId];
    const value = stop ? stopsAround(catalog, stop, WALK_TRANSFER_METERS) : [];
    aroundCache.set(stopId, value);
    return value;
  };

  for (const lineId of catalog.linesAtStop[originId] ?? []) {
    const line1 = catalog.linesById[lineId];
    if (!line1) continue;

    for (const route of line1.routes) {
      const fromIndex = route.indexOf(originId);
      if (fromIndex === -1) continue;
      const last = Math.min(route.length - 1, fromIndex + MAX_ROUTE_SCAN);

      for (let i = fromIndex + 1; i <= last; i++) {
        const alightId = route[i];
        if (!alightId || alightId === destId) continue;
        const alightStop = catalog.stopsById[alightId];
        if (!alightStop) continue;

        for (const boarding of around(alightId)) {
          for (const line2Id of catalog.linesAtStop[boarding.stop.id] ?? []) {
            if (line2Id === line1.id) continue;
            const line2 = catalog.linesById[line2Id];
            if (!line2) continue;

            for (const route2 of line2.routes) {
              const boardIndex = route2.indexOf(boarding.stop.id);
              if (boardIndex === -1) continue;
              const destIndex = route2.indexOf(destId);
              if (destIndex === -1 || destIndex <= boardIndex) continue;

              const stops1 = i - fromIndex;
              const stops2 = destIndex - boardIndex;
              if (stops1 + stops2 > 70) continue;

              const key = `${line1.id}-${line2.id}-${alightId}-${boarding.stop.id}`;
              if (results.has(key)) continue;

              const leg1: PlanLeg = {
                lineId: line1.id,
                line: line1.code,
                fromStopId: originId,
                toStopId: alightId,
                stops: stops1,
                minutes: legsMinutes(stops1, 0),
                walkMeters: 0,
              };
              const leg2: PlanLeg = {
                lineId: line2.id,
                line: line2.code,
                fromStopId: boarding.stop.id,
                toStopId: destId,
                stops: stops2,
                minutes: legsMinutes(stops2, 0),
                walkMeters: boarding.meters,
              };

              const totalMinutes =
                legsMinutes(stops1, 0) + MINUTES_PER_TRANSFER + legsMinutes(stops2, boarding.meters);

              results.set(key, {
                type: 'transfer',
                legs: [leg1, leg2],
                totalStops: stops1 + stops2,
                totalMinutes,
                walkMeters: boarding.meters,
              });
            }
          }
        }
      }
    }
  }

  return [...results.values()].sort((a, b) => a.totalMinutes - b.totalMinutes);
}

/** Origin/destination can also be coordinates: we walk to the closest stop. */
export function planRoute(
  catalog: LegacyCatalog,
  originId: number,
  destId: number,
): { origin: LegacyStop; destination: LegacyStop; options: PlanOption[]; walkOrigin: number; walkDestination: number } | null {
  const origin = catalog.stopsById[originId];
  const destination = catalog.stopsById[destId];
  if (!origin || !destination) return null;

  const direct = planDirect(catalog, originId, destId);
  const transfers = planTransfer(catalog, originId, destId);

  // Fastest first, whatever the number of transfers: a 26 min connection is
  // better than a 38 min direct ride.
  const options = [...direct, ...transfers]
    .sort((a, b) => a.totalMinutes - b.totalMinutes)
    .slice(0, MAX_OPTIONS);

  return { origin, destination, options, walkOrigin: 0, walkDestination: 0 };
}

/** Plan between the stops closest to two coordinates (used by the web page). */
export function planBetweenPoints(
  catalog: LegacyCatalog,
  originLat: number,
  originLng: number,
  destLat: number,
  destLng: number,
  maxWalkMeters = WALK_TO_BOARD_METERS,
): { origin: LegacyStop; destination: LegacyStop; options: PlanOption[]; walkOrigin: number; walkDestination: number } | null {
  const originCandidates = nearbyStops(catalog, originLat, originLng, maxWalkMeters, 4);
  const destCandidates = nearbyStops(catalog, destLat, destLng, maxWalkMeters, 4);
  if (!originCandidates.length || !destCandidates.length) return null;

  const options: PlanOption[] = [];
  let bestOrigin = originCandidates[0];
  let bestDest = destCandidates[0];

  for (const origin of originCandidates) {
    for (const destination of destCandidates) {
      const planned = planRoute(catalog, origin.stop.id, destination.stop.id);
      if (!planned) continue;
      for (const option of planned.options) {
        const walk = origin.meters + destination.meters;
        const placement = Math.ceil(origin.meters / METERS_PER_MINUTE_WALK) + Math.ceil(destination.meters / METERS_PER_MINUTE_WALK);
        options.push({
          ...option,
          totalMinutes: option.totalMinutes + placement,
          walkMeters: option.walkMeters + walk,
        });
        if (option.totalMinutes + placement < (options[0]?.totalMinutes ?? Number.POSITIVE_INFINITY)) {
          bestOrigin = origin;
          bestDest = destination;
        }
      }
    }
  }

  if (!options.length) return null;
  options.sort((a, b) => a.totalMinutes - b.totalMinutes);

  return {
    origin: bestOrigin.stop,
    destination: bestDest.stop,
    options: options.slice(0, MAX_OPTIONS),
    walkOrigin: bestOrigin.meters,
    walkDestination: bestDest.meters,
  };
}

/** Lines serving a stop, as catalog lines sorted by commercial number. */
export function linesAtStop(catalog: LegacyCatalog, stopId: number): LegacyLine[] {
  return (catalog.linesAtStop[stopId] ?? [])
    .map((lineId) => catalog.linesById[lineId])
    .filter((line): line is LegacyLine => Boolean(line));
}
