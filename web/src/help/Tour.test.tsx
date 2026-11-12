import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Tour, type TourEnd } from './Tour';
import { TOURS, type TourScript } from './tours';

/** A page with a part for every step but the ones named, so the tour has something to outline. */
function page(script: TourScript, missing: readonly string[] = []) {
  for (const step of script.steps) {
    if (missing.includes(step.title)) {
      continue;
    }
    // Each anchor is a selector such as section[aria-labelledby="x"], .a .b or nav a[href="/y"]: build a match.
    const host = document.createElement('div');
    host.innerHTML = markupFor(step.anchor);
    document.body.append(host);
  }
}

function markupFor(anchor: string): string {
  const labelled = /^section\[aria-labelledby="([^"]+)"\]$/.exec(anchor);
  if (labelled !== null) {
    return `<section aria-labelledby="${labelled[1]}"></section>`;
  }
  if (anchor === '.terminal-orders .panel') {
    return '<div class="terminal-orders"><section class="panel"></section></div>';
  }
  if (anchor === 'nav a[href="/portfolio"]') {
    return '<nav><a href="/portfolio">Portfolio</a></nav>';
  }
  throw new Error(`no markup for ${anchor}`);
}

const outlined = () => [...document.querySelectorAll('.tour-target')];

/** What a screen reader hears as a step's name: its title and where it is in the tour. */
const named = (script: TourScript, index: number) =>
  `${script.steps[index]!.title}, step ${index + 1} of ${script.steps.length}`;

let scrolled: ScrollIntoViewOptions[] = [];

beforeEach(() => {
  scrolled = [];
  Element.prototype.scrollIntoView = function scrollIntoView(options?: boolean | ScrollIntoViewOptions) {
    scrolled.push(options as ScrollIntoViewOptions);
  };
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

describe.each(['TRADER', 'OPERATOR'] as const)('the %s tour', (role) => {
  const script = TOURS[role];

  it('welcomes, then outlines each part in turn with its title as focus, and ends as finished', () => {
    page(script);
    const ends: TourEnd[] = [];
    render(<Tour script={script} onEnd={(end) => ends.push(end)} />);

    const dialog = screen.getByRole('dialog', { name: script.welcome.title });
    expect(dialog).toHaveAttribute('open');
    expect(dialog).toHaveAccessibleDescription(script.welcome.body);
    expect(screen.getByRole('heading', { name: script.welcome.title })).toHaveFocus();
    expect(outlined()).toEqual([]);

    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    const headings = new Set<HTMLElement>();
    script.steps.forEach((step, index) => {
      expect(screen.getByText(`Step ${index + 1} of ${script.steps.length}`)).toBeInTheDocument();
      expect(screen.getByRole('dialog', { name: named(script, index) })).toHaveAccessibleDescription(step.body);
      const heading = screen.getByRole('heading', { name: named(script, index) });
      // The focused title carries the step's text too, so a screen reader reads the whole step.
      expect(heading).toHaveFocus();
      expect(heading).toHaveAccessibleDescription(step.body);
      // A new element each step, so focus really moves and the step is read out, not just repainted in place.
      expect(headings.has(heading)).toBe(false);
      headings.add(heading);
      expect(outlined()).toEqual([document.querySelector(step.anchor)]);
      const last = index === script.steps.length - 1;
      fireEvent.click(screen.getByRole('button', { name: last ? 'Done' : 'Next' }));
    });

    expect(ends).toEqual(['finished']);
    expect(script.steps.length).toBeGreaterThanOrEqual(4);
    expect(script.steps.length).toBeLessThanOrEqual(6);
  });

  it('goes back a step, and to the welcome', () => {
    page(script);
    render(<Tour script={script} onEnd={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('heading', { name: named(script, 0) })).toHaveFocus();
    expect(outlined()).toEqual([document.querySelector(script.steps[0]!.anchor)]);
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByRole('heading', { name: script.welcome.title })).toBeInTheDocument();
    expect(outlined()).toEqual([]);
  });
});

describe('leaving the tour', () => {
  const script = TOURS.TRADER;

  it('skips from the welcome', () => {
    const onEnd = vi.fn();
    render(<Tour script={script} onEnd={onEnd} />);
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));
    expect(onEnd).toHaveBeenCalledExactlyOnceWith('skipped');
  });

  it('skips from any step, leaving nothing outlined', () => {
    page(script);
    const onEnd = vi.fn();
    const { unmount } = render(<Tour script={script} onEnd={onEnd} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Skip tour' }));
    expect(onEnd).toHaveBeenCalledExactlyOnceWith('skipped');
    unmount();
    expect(outlined()).toEqual([]);
  });

  it('skips on Escape, once, however often it is pressed', () => {
    const onEnd = vi.fn();
    render(<Tour script={script} onEnd={onEnd} />);
    const dialog = screen.getByRole('dialog');
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    fireEvent(dialog, new Event('cancel', { cancelable: true }));
    expect(onEnd).toHaveBeenCalledExactlyOnceWith('skipped');
  });
});

describe('the page under the tour', () => {
  it('says where a part will be when it is not on the page yet, and outlines nothing', () => {
    const script = TOURS.OPERATOR;
    page(script, ['Health']);
    render(<Tour script={script} onEnd={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    for (let step = 1; step < script.steps.length; step++) {
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    }
    const health = script.steps.at(-1)!;
    expect(screen.getByRole('dialog', { name: named(script, script.steps.length - 1) })).toHaveAccessibleDescription(
      `${health.body} ${health.absent}`,
    );
    expect(outlined()).toEqual([]);
  });

  it('outlines a part that arrives after its step opened, and one the page replaces', async () => {
    const script = TOURS.OPERATOR;
    page(script, ['Market state']);
    render(<Tour script={script} onEnd={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    expect(outlined()).toEqual([]);

    const arriving = document.createElement('section');
    arriving.setAttribute('aria-labelledby', 'market-title');
    act(() => document.body.append(arriving));
    await waitFor(() => expect(outlined()).toEqual([arriving]));

    const replacement = arriving.cloneNode() as HTMLElement;
    act(() => arriving.replaceWith(replacement));
    await waitFor(() => expect(outlined()).toEqual([replacement]));
  });

  it('keeps room below the page while it is open, so the card never hides a part', () => {
    const { unmount } = render(<Tour script={TOURS.TRADER} onEnd={() => undefined} />);
    expect(document.documentElement).toHaveClass('tour-open');
    unmount();
    expect(document.documentElement).not.toHaveClass('tour-open');
  });

  it('scrolls each part into view smoothly, and without motion when the system asks for less', () => {
    const script = TOURS.TRADER;
    page(script);
    const { unmount } = render(<Tour script={script} onEnd={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    expect(scrolled.at(-1)).toEqual({ block: 'center', behavior: 'smooth' });
    unmount();

    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query === '(prefers-reduced-motion: reduce)' }));
    render(<Tour script={script} onEnd={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start the tour' }));
    expect(scrolled.at(-1)).toEqual({ block: 'center', behavior: 'auto' });
  });
});
