import { describe, it, expect } from 'vitest';
import { readBusStops, resolveBusRoute } from '../src/utils/trackingRoute';

// A func=2 answer, exactly as itranvias.com returns it: the stop id is
// `parada` (not `id`), stops are grouped by direction, and the bus is reported
// at the stop it is heading to with the distance still to cover, in km.
const lineData = (dirs: { sentido: string; paradas: { parada: number; buses: { bus: number; estado: number; distancia: number }[] }[] }[]) => ({
    resultado: 'OK',
    paradas: dirs,
});

describe('readBusStops (func=2 line feed)', () => {
    it('reads the stop id from `parada`, the real field name', () => {
        // Regression: the caller read `p.id`, which is undefined here, so the
        // whole position fallback threw and tracking never ended on its own.
        const data = lineData([
            { sentido: '0', paradas: [{ parada: 523, buses: [{ bus: 420, estado: 0, distancia: 0.167 }] }] },
        ]);
        const { stops } = readBusStops(data, '420');
        expect(stops).toEqual([{ id: 523, km: 0.167 }]);
    });

    it('takes the direction the bus travels in, not the other one', () => {
        // Both directions share streets; only one of them lists the bus.
        const data = lineData([
            { sentido: '0', paradas: [{ parada: 523, buses: [{ bus: 421, estado: 0, distancia: 0.4 }] }] },
            { sentido: '1', paradas: [{ parada: 1, buses: [{ bus: 420, estado: 0, distancia: 0.2 }] }] },
        ]);
        expect(readBusStops(data, '420')).toEqual({ sentido: '1', stops: [{ id: 1, km: 0.2 }] });
    });

    it('returns nothing when the feed does not list the bus at all', () => {
        const data = lineData([{ sentido: '0', paradas: [{ parada: 523, buses: [] }] }]);
        expect(readBusStops(data, '999').stops).toEqual([]);
    });

    it('survives a stop entry without any bus list', () => {
        const data = { resultado: 'OK', paradas: [{ sentido: '0', paradas: [{}] }] };
        expect(readBusStops(data, '420').stops).toEqual([]);
    });

    it('still reads a feed that names the stop id `id`', () => {
        // Tolerated on purpose: a shape change should degrade to a shorter
        // journey, not to the silent dead fallback this code used to be.
        const data = { resultado: 'OK', paradas: [{ sentido: '0', paradas: [{ id: 523, buses: [{ bus: 420, distancia: 0.2 }] }] }] };
        expect(readBusStops(data, '420').stops).toEqual([{ id: 523, km: 0.2 }]);
    });
});

describe('resolveBusRoute (where the bus stands)', () => {
    // Direction 0 runs 10 -> 20 -> 30 -> 40 -> 50 and back (direction 1).
    const routes = [
        { ruta: 1, paradas: [10, 20, 30, 40, 50] },
        { ruta: 2, paradas: [50, 40, 30, 20, 10] },
    ];

    it('counts the stops still ahead in the direction of travel', () => {
        const d = resolveBusRoute(routes, '0', [{ id: 20, km: 0.3 }], 50);
        expect(d.kind).toBe('ahead');
        expect(d.stopsLeft).toBe(3); // 30, 40, 50
    });

    it('does not end the journey while the bus is still approaching', () => {
        // Listed at the destination but still 300 m away: not an arrival.
        expect(resolveBusRoute(routes, '0', [{ id: 50, km: 0.3 }], 50).kind).toBe('ahead');
    });

    it('ends the journey when the bus is on the destination stop', () => {
        expect(resolveBusRoute(routes, '0', [{ id: 50, km: 0.01 }], 50).kind).toBe('arrived');
    });

    it('ends the journey when the bus has gone past the destination', () => {
        // It left 30 behind and is heading for 40: it went through.
        expect(resolveBusRoute(routes, '0', [{ id: 40, km: 0.4 }], 30).kind).toBe('past');
    });

    it('never finishes on the return direction', () => {
        // Destination 50 is the first stop of direction 1, so matching across
        // directions would read the bus as "past" it and end the trip at once.
        expect(resolveBusRoute(routes, '0', [{ id: 20, km: 0.3 }], 50).kind).toBe('ahead');
    });

    it('prefers the variant that leaves the bus furthest from the destination', () => {
        // Same direction, two variants; on one the stop 20 comes after 50.
        const variants = [
            { ruta: 1, paradas: [10, 50, 20, 30] },
            { ruta: 2, paradas: [10, 20, 50, 30] },
        ];
        const d = resolveBusRoute(variants, '0', [{ id: 20, km: 0.3 }], 50);
        expect(d.kind).toBe('ahead');
        expect(d.stopsLeft).toBe(1);
    });

    it('says unknown when the destination is not on the bus route', () => {
        expect(resolveBusRoute(routes, '0', [{ id: 20, km: 0.3 }], 999).kind).toBe('unknown');
    });

    it('says unknown without a feed answer to judge', () => {
        expect(resolveBusRoute(routes, '0', [], 50).kind).toBe('unknown');
    });

    it('works with a line whose routes cannot be indexed by sentido', () => {
        const variants = [{ ruta: 1, paradas: [10, 20, 30] }, { ruta: 2, paradas: [10, 20, 40] }];
        const d = resolveBusRoute(variants, '7', [{ id: 20, km: 0.3 }], 30);
        expect(d.kind).toBe('ahead');
        expect(d.stopsLeft).toBe(1);
    });
});
