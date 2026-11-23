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

export function clock(date: Date): string {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

/** "HH:MM" (o "H:MM") al día de hoy; null si viene vacío o mal. */
export function parseClock(value: string | null | undefined, now = new Date()): Date | null {
  if (!value) return null;
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  const date = new Date(now.getTime());
  date.setHours(hours, minutes, 0, 0);
  return date;
}

export type BusAlternative = OptionTiming;

/**
 * Buses que sirven la primera línea de una opción, del recomendado en adelante.
 * Con hora límite se empieza por el último que aún llega a tiempo y se sigue
 * con los siguientes, que llegan más tarde (el usuario puede preferirlos).
 */
export function busAlternatives(
  option: PlanOption,
  buses: number[],
  now: Date,
  target: Date | null,
  max = 5,
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

  const out: BusAlternative[] = [];
  for (let i = start; i < ordered.length && out.length < max; i++) {
    const wait = ordered[i];
    const depart = new Date(now.getTime() + wait * 60_000);
    const arrive = new Date(depart.getTime() + option.totalMinutes * 60_000);
    out.push({
      waitMinutes: wait,
      departAt: clock(depart),
      arriveAt: clock(arrive),
      late: target ? arrive.getTime() > target.getTime() : false,
    });
  }
  return out;
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
