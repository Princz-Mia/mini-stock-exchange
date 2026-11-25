import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import css from '../styles.css?raw';
import { PageSkeleton, SkeletonPanel, SkeletonRows, TerminalSkeleton } from './Skeleton';

/** The declarations of every rule for exactly this selector, outside and inside media queries alike. */
function rules(source: string, selector: string): string[] {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return [...source.matchAll(new RegExp(`(?:^|[}\\s])${escaped}\\s*\\{([^}]*)\\}`, 'g'))].map((match) => match[1] ?? '');
}

/** The body of each media block with this condition. */
function mediaBlocks(source: string, condition: string): string[] {
  const blocks: string[] = [];
  let from = source.indexOf(`@media ${condition}`);
  while (from !== -1) {
    const open = source.indexOf('{', from);
    let depth = 1;
    let at = open + 1;
    while (depth > 0 && at < source.length) {
      depth += source[at] === '{' ? 1 : source[at] === '}' ? -1 : 0;
      at += 1;
    }
    blocks.push(source.slice(open + 1, at - 1));
    from = source.indexOf(`@media ${condition}`, at);
  }
  return blocks;
}

describe('loading bars', () => {
  it('pulse, in the theme’s own colours, and stand still for anyone who asks for less motion', () => {
    const skeleton = rules(css, '.skeleton').join(';');
    expect(skeleton).toMatch(/animation:\s*skeleton-pulse/);
    expect(skeleton).toMatch(/background:\s*var\(--line\)/);
    expect(skeleton).toMatch(/border-radius:\s*var\(--space-1\)/);
    expect(skeleton, 'no colour of its own: the tokens follow the theme').not.toMatch(/#[0-9a-f]{3,8}\b|rgb\(/i);

    const reduced = mediaBlocks(css, '(prefers-reduced-motion: reduce)');
    expect(
      reduced.some((block) => rules(block, '.skeleton').some((rule) => /animation:\s*none/.test(rule))),
      'the bars stop under prefers-reduced-motion',
    ).toBe(true);
  });

  it('are hidden from assistive technology, which hears instead that the panel is busy', () => {
    const { container } = render(<SkeletonPanel lines={4} kind="row" className="ticket" />);
    const panel = container.firstElementChild;

    expect(panel).toHaveAttribute('aria-busy', 'true');
    expect(panel).toHaveClass('panel', 'skeleton-panel', 'ticket');
    expect(panel?.textContent).toBe('');
    const bars = [...(panel?.children ?? [])];
    expect(bars).toHaveLength(5);
    expect(bars.every((bar) => bar.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(bars.slice(1).every((bar) => bar.classList.contains('skeleton-row'))).toBe(true);
    // Not named, so never taken for the panel it stands for.
    expect(screen.queryByRole('region')).toBeNull();
    expect(screen.queryByRole('heading')).toBeNull();
  });

  it('say "Loading…" inside a panel already drawn, to assistive technology only', () => {
    const { container } = render(<SkeletonRows rows={2} />);
    const rows = container.firstElementChild;
    expect(rows).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading…')).toHaveClass('visually-hidden');
    expect(rows?.querySelectorAll('.skeleton-row[aria-hidden="true"]')).toHaveLength(2);
  });

  it("draw the terminal's panels in their places, with a page passing in a panel it can already fill", () => {
    const { container } = render(<TerminalSkeleton orders={<p>Your orders, read</p>} />);
    const terminal = container.querySelector('.terminal');

    expect([...(terminal?.children ?? [])].map((child) => child.className)).toEqual([
      'panel skeleton-panel watchlist',
      'terminal-market',
      'panel skeleton-panel ticket',
      'visually-hidden',
      'terminal-orders',
    ]);
    expect(screen.getByRole('status')).toHaveTextContent('Loading markets…');
    expect(screen.getByText('Your orders, read')).toBeInTheDocument();
  });

  it("draw the layout of the page asked for, or plain panels for a page without one", () => {
    const layout = (path: string) => {
      const { container, unmount } = render(<PageSkeleton path={path} />);
      const shown = [container.firstElementChild?.className, screen.getByRole('status').textContent];
      unmount();
      return shown;
    };
    expect(layout('/terminal')).toEqual(['terminal', 'Loading markets…']);
    expect(layout('/portfolio')).toEqual(['stack', 'Loading portfolio…']);
    expect(layout('/operator')).toEqual(['operator', 'Loading the market…']);
    expect(layout('/help')).toEqual(['stack', 'Loading…']);
  });
});
