// Horas de paso por cada opción del planificador.
//
// El planificador calcula duraciones; aquí se les pone reloj: cuánto falta para
// el bus que hay que coger en la parada de origen y a qué hora se llegará.
// Con `target` se elige el último bus que aún llega a esa hora (llegar antes
// de las HH:MM), igual que el planificador de la app Android.

import type { PlanOption } from './planner';

export interface OptionTiming {
  /** Minutos hasta el bus en la parada de origen; null si no hay datos en vivo. */
  waitMinutes: number | null;
  departAt: string;
  arriveAt: string;
  /** true si con el próximo bus ya no se llega a la hora pedida. */
  late: boolean;
}

export function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

// El worker corre en UTC, pero las horas de A Coruña son las de Madrid: sin
// esto el reloj del planificador salía una o dos horas desplazado.
const TIME_ZONE = 'Europe/Madrid';

const partsFormat = new Intl.DateTimeFormat('en-GB', {
  timeZone: TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

interface WallClock {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function madridParts(date: Date): WallClock {
  const out: WallClock = { year: 0, month: 0, day: 0, hour: 0, minute: 0, second: 0 };
  for (const part of partsFormat.formatToParts(date)) {
    const value = Number(part.value);
    if (part.type === 'year') out.year = value;
    else if (part.type === 'month') out.month = value;
    else if (part.type === 'day') out.day = value;
    else if (part.type === 'hour') out.hour = value % 24;
    else if (part.type === 'minute') out.minute = value;
    else if (part.type === 'second') out.second = value;
  }
  return out;
}

/** Hora de A Coruña "HH:MM". */
export function clock(date: Date = new Date()): string {
  const parts = madridParts(date);
  return `${pad2(parts.hour)}:${pad2(parts.minute)}`;
}

/** "HH:MM" (o "H:MM") de hoy en A Coruña; null si viene vacío o mal. */
export function parseClock(value: string | null | undefined, now = new Date()): Date | null {
  if (!value) return null;
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;

  const parts = madridParts(now);
  const wallNow = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
  const offset = wallNow - Math.floor(now.getTime() / 1000) * 1000;
  return new Date(Date.UTC(parts.year, parts.month - 1, parts.day, hours, minutes) - offset);
}

export type BusAlternative = OptionTiming;

/**
 * Buses que sirven la primera línea de una opción, empezando por el
 * recomendado. Con hora límite el recomendado es el último que aún llega a
 * tiempo; después vienen los siguientes (que llegan más tarde y pueden
 * preferirse) y, al final, un par de anteriores para poder ir un poco antes.
 *
 * El recomendado tiene que ir primero y no dos puestos más atrás: el cliente
 * Java ME pinta el primer bus de la lista como estimación, así que si delante
 * van los de "salir ahora" la planificación con hora parece ignorada.
 */
export function busAlternatives(
  option: PlanOption,
  buses: number[],
  now: Date,
  target: Date | null,
  max = 6,
): BusAlternative[] {
  if (!buses.length) return [];
  const ordered = [...buses].sort((a, b) => a - b);

  let start = 0;
  if (target) {
    let chosen = -1;
    for (let i = 0; i < ordered.length; i++) {
      const arrival = now.getTime() + (ordered[i] + option.totalMinutes) * 60_000;
      if (arrival <= target.getTime()) chosen = i;
    }
    start = chosen >= 0 ? chosen : 0;
  }

  const indices: number[] = [];
  for (let i = start; i < ordered.length && indices.length < max; i++) indices.push(i);
  for (let i = start - 1; i >= 0 && i >= start - 2 && indices.length < max; i--) indices.push(i);

  return indices.map((index) => {
    const wait = ordered[index];
    const depart = new Date(now.getTime() + wait * 60_000);
    const arrive = new Date(depart.getTime() + option.totalMinutes * 60_000);
    return {
      waitMinutes: wait,
      departAt: clock(depart),
      arriveAt: clock(arrive),
      late: target ? arrive.getTime() > target.getTime() : false,
    };
  });
}

export function timeOptions(
  options: PlanOption[],
  arrivals: Map<number, number[]>,
  now: Date,
  target: Date | null,
): OptionTiming[] {
  return options.map((option) => {
    const buses = arrivals.get(option.legs[0].lineId) ?? [];
    let wait: number | null = buses.length ? buses[0] : null;
    let late = false;

    if (target && buses.length) {
      // Los buses vienen ordenados: el último que llega a tiempo es el bueno.
      let chosen: number | null = null;
      for (const minutes of buses) {
        const arrival = now.getTime() + (minutes + option.totalMinutes) * 60_000;
        if (arrival <= target.getTime()) chosen = minutes;
      }
      if (chosen !== null) wait = chosen;
      else late = true;
    }

    const depart = new Date(now.getTime() + (wait ?? 0) * 60_000);
    const arrive = new Date(depart.getTime() + option.totalMinutes * 60_000);
    return { waitMinutes: wait, departAt: clock(depart), arriveAt: clock(arrive), late };
  });
}
