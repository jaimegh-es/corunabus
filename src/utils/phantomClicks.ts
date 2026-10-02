/**
 * Suppress clicks that no tap asked for.
 *
 * In a list of cards that re-renders itself, a tap can land on an item the user
 * never meant to open. Two things have to line up for it:
 *
 * - The touch goes down on one item and the browser decides at touch/click time
 *   which element gets the click. If the list is rebuilt in between (a new
 *   `catalog-loaded`, saving a nickname), the element under the finger is a
 *   different one and the click is delivered to it. The user sees an item
 *   "click itself".
 * - Momentum scrolling that is still settling when a finger lands does not
 *   always cancel the tap, so the click fires on whatever slid underneath.
 *
 * Both look identical from the user's side and both are timing-dependent, which
 * is why this shows up on one phone and not another. The fix is to compare the
 * `click` against the pointer sequence that is supposed to have caused it, and
 * drop it when they disagree: the finger moved (it was a scroll), or the click
 * landed somewhere other than where the finger went down (the list moved).
 *
 * Keyboard and assistive-technology activation is left alone: those clicks carry
 * `detail === 0` and have no pointer sequence, so blocking them would make the
 * list unusable without a touchscreen.
 *
 * Suppress clicks no tap asked for.
 *
 * En una lista de tarjetas que se repinta sola, un toque puede acabar abriendo
 * un item que el usuario no queria. Pasa cuando el dedo baja sobre un item y la
 * lista se reconstruye antes de que el navegador decida a quien entrega el
 * click, o cuando un scroll con inercia sigue moviendo la lista bajo el dedo.
 *
 * Ambas cosas dependen del timing, que es justo por que se ven en un movil y en
 * otro no. Aqui se contrasta el click con la secuencia de puntero que deberia
 * haberlo causado y se descarta cuando no cuadran: el dedo se movio (era un
 * scroll) o el click cae en otro sitio del que started (la lista se movio).
 */

/** How far a finger may drift and still count as a tap, in CSS pixels. */
export const TAP_SLOP_PX = 10;

interface TapState {
  target: EventTarget | null;
  x: number;
  y: number;
  moved: boolean;
}

export interface PhantomClickGuardOptions {
  /**
   * Finger drift tolerated before the press is treated as a scroll.
   * Defaults to {@link TAP_SLOP_PX}.
   */
  slop?: number;
  /** Returns true to keep only clicks on the guarded elements. */
  shouldGuard?: (target: EventTarget | null) => boolean;
}

function coordinates(event: Event): { x: number; y: number } {
  const source = event as Partial<PointerEvent>;
  return {
    x: typeof source.clientX === 'number' ? source.clientX : 0,
    y: typeof source.clientY === 'number' ? source.clientY : 0,
  };
}

/**
 * Guards clicks inside `root`. Returns a function that removes the listeners.
 *
 * @param event.detail === 0 clicks (keyboard, assistive tech, programmatic) are
 * never blocked, so guarding a list does not make it keyboard-inaccessible.
 */
export function guardPhantomClicks(
  root: HTMLElement,
  options: PhantomClickGuardOptions = {},
): () => void {
  const slop = options.slop ?? TAP_SLOP_PX;
  const shouldGuard = options.shouldGuard ?? (() => true);

  let tap: TapState | null = null;

  const onPress = (event: Event) => {
    const { x, y } = coordinates(event);
    tap = { target: event.target, x, y, moved: false };
  };

  const onDrag = (event: Event) => {
    if (!tap) return;
    const { x, y } = coordinates(event);
    if (Math.abs(x - tap.x) > slop || Math.abs(y - tap.y) > slop) tap.moved = true;
  };

  const onRelease = () => {
    tap = null;
  };

  const onClick = (event: Event) => {
    const mouseEvent = event as MouseEvent;
    // A real tap reports a click count of 1 or more. Keyboard activation reports
    // 0, and code that builds its own event (`new Event('click')`, as the
    // rename prompt does when Enter saves it) reports nothing at all. Neither
    // has a pointer sequence to compare, and both must keep working.
    if (typeof mouseEvent.detail !== 'number' || mouseEvent.detail === 0) return;

    const press = tap;
    tap = null;

    if (!press) {
      // A click with no press behind it is the browser tapping on its own.
      block(event);
      return;
    }
    if (press.moved || press.target !== event.target) block(event);
  };

  function block(event: Event): void {
    if (!shouldGuard(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
  }

  root.addEventListener('pointerdown', onPress, true);
  root.addEventListener('pointermove', onDrag, true);
  root.addEventListener('pointercancel', onRelease, true);
  // A touch that turned into a scroll never produces the press we recorded, and
  // browsers differ on which event they do emit for it.
  root.addEventListener('touchstart', onPress, true);
  root.addEventListener('touchmove', onDrag, true);
  root.addEventListener('touchcancel', onRelease, true);
  root.addEventListener('mousedown', onPress, true);
  root.addEventListener('mousemove', onDrag, true);
  root.addEventListener('dragstart', onRelease, true);
  root.addEventListener('click', onClick, true);

  return () => {
    root.removeEventListener('pointerdown', onPress, true);
    root.removeEventListener('pointermove', onDrag, true);
    root.removeEventListener('pointercancel', onRelease, true);
    root.removeEventListener('touchstart', onPress, true);
    root.removeEventListener('touchmove', onDrag, true);
    root.removeEventListener('touchcancel', onRelease, true);
    root.removeEventListener('mousedown', onPress, true);
    root.removeEventListener('mousemove', onDrag, true);
    root.removeEventListener('dragstart', onRelease, true);
    root.removeEventListener('click', onClick, true);
  };
}
