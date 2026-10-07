/**
 * Suppress clicks that no tap asked for.
 *
 * The fingerprint of the bug is a list that opens an item while you are
 * scrolling it: you put your finger down on a card (with many cards there is no
 * empty space to spare), drag the list a few pixels, and lifting the finger
 * opens the card. Three mechanisms produce it, and they are all invisible from
 * the outside because they differ only in timing:
 *
 * - A short flick moves the finger less than a tap tolerates while still
 *   scrolling the list, so the press still reads as a tap.
 * - The list repaints between press and click (`catalog-loaded`, saving a
 *   nickname), so the click is delivered to a card the finger never touched.
 * - The click is synthesised after `pointercancel` by the compatibility mouse
 *   events Android emits for a touch, at the position where the finger lifted.
 *
 * The only thing all three share is that **the page moved**. Measuring the
 * finger is therefore the wrong instrument: a flick scrolls hundreds of
 * pixels per finger pixel, so distance from the finger never detects it. What
 * is measured instead is the scroll position of the whole chain of scrollable
 * ancestors, taken when the finger goes down and again when the click arrives.
 * A genuine tap leaves it untouched; everything else changes it.
 *
 * Clicks with no pointer sequence behind them are left alone: those are the
 * keyboard, assistive technology, and code building its own event, and
 * blocking them would make the list unusable without a touchscreen.
 *
 * Suppresses clicks nobody asked for.
 *
 * La huella de este fallo es una lista que abre un item mientras la mueves:
 * el dedo baja sobre una tarjeta, arrastras la lista unos pocos pixeles y al
 * soltar se abre esa misma tarjeta.
 *
 * Los tres mecanismos que lo producen comparten una sola cosa: **la pagina se
 * movio**. Medir el dedo es por tanto el instrumento equivocado, porque una
 * empujonada recorre cientos de pixeles de lista por cada uno del dedo. Aqui se
 * mide la posicion de scroll de toda la cadena de contenedores desplazables, al
 * bajar el dedo y otra vez al llegar el click. Un toque de verdad no la toca.
 */

/** Finger drift tolerated before the press is treated as a scroll. */
export const TAP_SLOP_PX = 10;

/**
 * Scroll distance tolerated between press and click. A real tap moves none of
 * it; this only exists so sub-pixel rounding cannot cancel a genuine scroll.
 */
export const SCROLL_TOLERANCE_PX = 2;

interface TapState {
  target: EventTarget | null;
  x: number;
  y: number;
  moved: boolean;
  scrolled: boolean;
  scroll: number;
}

export interface PhantomClickGuardOptions {
  /** Finger drift tolerated before the press counts as movement. */
  slop?: number;
  /** Scroll tolerated between press and click, in CSS pixels. */
  scrollTolerance?: number;
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
 * Sum of every `scrollTop` from `target` up to the root of the document, which
 * covers the tapped element, each scrollable card list it sits in, and the page
 * itself. Summing rather than comparing one value is deliberate: scrolling an
 * inner list cancels out against the outer one only by accident, whereas a sum
 * changes whenever anything moved.
 */
function scrollState(target: EventTarget | null): number {
  let total = 0;
  let node = (target as Partial<Element> | null)?.parentElement ?? null;
  while (node) {
    if (typeof node.scrollTop === 'number') total += node.scrollTop;
    node = node.parentElement;
  }
  return total;
}

/**
 * Guards clicks inside `root`. Returns a function that removes the listeners.
 */
export function guardPhantomClicks(
  root: HTMLElement,
  options: PhantomClickGuardOptions = {},
): () => void {
  const slop = options.slop ?? TAP_SLOP_PX;
  const scrollTolerance = options.scrollTolerance ?? SCROLL_TOLERANCE_PX;
  const shouldGuard = options.shouldGuard ?? (() => true);

  let tap: TapState | null = null;

  const onPress = (event: Event) => {
    const { x, y } = coordinates(event);
    tap = { target: event.target, x, y, moved: false, scrolled: false, scroll: scrollState(event.target) };
  };

  const onDrag = (event: Event) => {
    if (!tap) return;
    const { x, y } = coordinates(event);
    if (Math.abs(x - tap.x) > slop || Math.abs(y - tap.y) > slop) tap.moved = true;
  };

  // Scroll does not bubble, but the capture phase still walks past this node on
  // its way down to the target, so any list scrolling inside the guarded
  // container is seen here even though the event itself does not escape it.
  const onScroll = () => {
    if (tap) tap.scrolled = true;
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
      block(event);
      return;
    }
    if (press.moved || press.scrolled) block(event);
    else if (Math.abs(scrollState(press.target) - press.scroll) > scrollTolerance) block(event);
    else if (press.target !== event.target) block(event);
  };

  function block(event: Event): void {
    if (!shouldGuard(event.target)) return;
    event.preventDefault();
    event.stopPropagation();
  }

  const attached: Array<[string, EventListener]> = [];
  const attach = (type: string, handler: EventListener) => {
    root.addEventListener(type, handler, true);
    attached.push([type, handler]);
  };

  // Exactly one pointer source, never two. After a touch gesture Android also
  // emits the compatibility mouse events (mousedown, mouseup, click) *after*
  // pointercancel, at the position where the finger lifted. Listening to
  // mousedown as well therefore restarted the record right before the click,
  // wiping both "the finger moved" and "where the finger went down", and the
  // click reached whichever card the scroll had parked under it. That made the
  // guard a no-op on exactly the gesture it existed for.
  // Una sola fuente de puntero, nunca dos.
  const hasPointers = typeof window !== 'undefined' && 'PointerEvent' in window;
  const hasTouch = !hasPointers && typeof window !== 'undefined' && 'ontouchstart' in window;

  if (hasPointers) {
    attach('pointerdown', onPress);
    attach('pointermove', onDrag);
    attach('pointercancel', onRelease);
  } else if (hasTouch) {
    attach('touchstart', onPress);
    attach('touchmove', onDrag);
    attach('touchcancel', onRelease);
  } else {
    attach('mousedown', onPress);
    attach('mousemove', onDrag);
  }
  attach('scroll', onScroll);
  attach('dragstart', onRelease);
  attach('click', onClick);

  return () => {
    for (const [type, handler] of attached) root.removeEventListener(type, handler, true);
    attached.length = 0;
  };
}
