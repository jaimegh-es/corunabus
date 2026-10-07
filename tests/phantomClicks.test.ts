import { describe, expect, it } from 'vitest';
import { guardPhantomClicks, TAP_SLOP_PX } from '../src/utils/phantomClicks';

/**
 * `pointerdown` / `click` are dispatched by type rather than by constructor:
 * happy-dom has no PointerEvent, and the guard only ever reads `clientX`,
 * `clientY`, `detail` and `target` off the event, all of which MouseEvent
 * carries.
 */
function fire(
  target: HTMLElement,
  type: string,
  init: { x?: number; y?: number; detail?: number } = {},
): MouseEvent {
  const event = new MouseEvent(type, {
    bubbles: true,
    cancelable: true,
    clientX: init.x ?? 100,
    clientY: init.y ?? 200,
    detail: init.detail ?? 1,
  });
  target.dispatchEvent(event);
  return event;
}

function mount(html: string) {
  const root = document.createElement('div');
  root.innerHTML = html;
  document.body.appendChild(root);
  const links = Array.from(root.querySelectorAll('a'));
  const opened: string[] = [];
  links.forEach((link) => link.addEventListener('click', () => opened.push(link.dataset.id!)));
  return { root, links, opened, stop: () => root.remove() };
}

describe('guardPhantomClicks', () => {
  it('lets a plain tap through', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    fire(links[1], 'pointerdown', { x: 100, y: 200 });
    fire(links[1], 'click');

    expect(opened).toEqual(['b']);
    dispose();
    stop();
  });

  it('drops a click that lands on another item than the one pressed', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    // The list is rebuilt between press and click, so the click is delivered to
    // whatever now sits under the finger. That is the reported auto-click.
    fire(links[0], 'pointerdown');
    fire(links[1], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('drops a click after the finger dragged (scroll)', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root);

    fire(links[0], 'pointerdown', { x: 100, y: 200 });
    fire(links[0], 'pointermove', { x: 100, y: 200 - (TAP_SLOP_PX + 5) });
    fire(links[0], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('tolerates finger jitter within the slop', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root, { slop: 10 });

    fire(links[0], 'pointerdown', { x: 100, y: 200 });
    fire(links[0], 'pointermove', { x: 107, y: 205 });
    fire(links[0], 'click');

    expect(opened).toEqual(['a']);
    dispose();
    stop();
  });

  it('drops a click with no press behind it at all', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root);

    fire(links[0], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('keeps keyboard activation working', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root);

    // Enter on a focused link: no pointer events, detail 0.
    fire(links[0], 'click', { detail: 0 });

    expect(opened).toEqual(['a']);
    dispose();
    stop();
  });

  it('drops the click a scroll emits through compatibility mouse events', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    // Exactly what Android Chrome does after a drag that scrolls: the pointer is
    // cancelled, and only then are the compatibility mouse events emitted, at
    // the position where the finger actually lifted. Watching mousedown there
    // restarts the record and hands the click to the item that scrolled under
    // the finger, which is the auto-click being reported.
    fire(links[0], 'pointerdown', { x: 100, y: 200 });
    fire(links[0], 'pointermove', { x: 100, y: 600 });
    fire(links[0], 'pointercancel');
    fire(links[1], 'mousedown', { x: 100, y: 600 });
    fire(links[1], 'click', { x: 100, y: 600 });

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('still opens the tapped item after a scroll that led nowhere', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    // A real tap after scrolling: fresh pointer sequence, finger stays put.
    fire(links[1], 'pointerdown', { x: 100, y: 600 });
    fire(links[1], 'click', { x: 100, y: 600 });

    expect(opened).toEqual(['b']);
    dispose();
    stop();
  });

  it('drops the click a short flick that scrolled the list', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    // The reported gesture: the finger lands on a card and drags the list just a
    // little, well inside the finger slop, but the list moves regardless. The
    // finger distance says "tap", the scroll says otherwise, and the scroll is
    // what the gesture was for.
    fire(links[0], 'pointerdown', { x: 100, y: 200 });
    fire(links[0], 'pointermove', { x: 100, y: 195 });
    root.scrollTop = 240;
    fire(links[0], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('drops the click when a nested list scrolls during the press', () => {
    const { root, links, opened, stop } = mount(
      '<div class="stops-list"><a data-id="a" href="/stop/1">A</a></div>',
    );
    const list = root.querySelector('.stops-list') as HTMLElement;
    const dispose = guardPhantomClicks(root);

    fire(links[0], 'pointerdown');
    list.scrollTop = 90;
    fire(links[0], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('drops the click when a scroll event arrives mid-press', () => {
    const { root, links, opened, stop } = mount(
      '<div class="stops-list"><a data-id="a" href="/stop/1">A</a></div>',
    );
    const list = root.querySelector('.stops-list') as HTMLElement;
    const dispose = guardPhantomClicks(root);

    fire(links[0], 'pointerdown');
    // Scroll does not bubble, so the guard only sees it by capturing.
    list.dispatchEvent(new Event('scroll'));
    fire(links[0], 'click');

    expect(opened).toEqual([]);
    dispose();
    stop();
  });

  it('opens the card when the press left every list where it was', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    root.scrollTop = 400; // the list is somewhere down the page...
    fire(links[1], 'pointerdown', { x: 100, y: 200 });
    root.scrollTop = 400; // ...and stays exactly there while it is tapped
    fire(links[1], 'click');

    expect(opened).toEqual(['b']);
    dispose();
    stop();
  });

  it('keeps hand-built click events working', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root);

    // `new Event('click')` carries no detail at all: the rename prompt saves on
    // Enter that way, and it must not be mistaken for a phantom tap.
    links[0].dispatchEvent(new Event('click', { bubbles: true, cancelable: true }));

    expect(opened).toEqual(['a']);
    dispose();
    stop();
  });

  it('only guards what shouldGuard accepts', () => {
    const { root, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><button data-id="pencil">x</button>',
    );
    const button = root.querySelector('button')!;
    const clicked: string[] = [];
    button.addEventListener('click', () => clicked.push('pencil'));
    const dispose = guardPhantomClicks(root, {
      shouldGuard: (target) => (target as HTMLElement)?.tagName === 'A',
    });

    // A click with no press behind it: blocked on the link, allowed on the
    // rename button, which the page wires up in its own way.
    fire(root.querySelector('a')!, 'click');
    fire(button, 'click');

    expect(opened).toEqual([]);
    expect(clicked).toEqual(['pencil']);
    dispose();
    stop();
  });

  it('stops guarding once disposed', () => {
    const { root, links, opened, stop } = mount('<a data-id="a" href="/stop/1">A</a>');
    const dispose = guardPhantomClicks(root);
    dispose();

    fire(links[0], 'click');

    expect(opened).toEqual(['a']);
    stop();
  });

  it('drops the click a cancelled gesture still emits, then recovers', () => {
    const { root, links, opened, stop } = mount(
      '<a data-id="a" href="/stop/1">A</a><a data-id="b" href="/stop/2">B</a>',
    );
    const dispose = guardPhantomClicks(root);

    // Scrolling starts: the browser cancels the pointer. A well-behaved engine
    // emits no click, but if one slips through it is spurious and must not open
    // whatever the list scrolled to.
    fire(links[0], 'pointerdown');
    fire(links[0], 'pointercancel');
    fire(links[1], 'click');
    expect(opened).toEqual([]);

    // The next deliberate tap is unaffected: the guard does not get stuck.
    fire(links[1], 'pointerdown');
    fire(links[1], 'click');
    expect(opened).toEqual(['b']);

    dispose();
    stop();
  });
});
