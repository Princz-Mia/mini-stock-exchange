import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useKeyboardScroll } from './useKeyboardScroll';

function Panel() {
  const panel = useKeyboardScroll<HTMLElement>();
  return (
    <section ref={panel} aria-label="Panel">
      <table />
    </section>
  );
}

/** jsdom lays nothing out, so the widths are what the test says they are. */
function widths(scroll: number, client: number) {
  vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(scroll);
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(client);
}

describe('useKeyboardScroll', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('makes a panel that scrolls sideways reachable by Tab', () => {
    widths(900, 360);
    render(<Panel />);
    expect(screen.getByRole('region', { name: 'Panel' })).toHaveAttribute('tabindex', '0');
  });

  it('adds no Tab stop while everything fits', () => {
    widths(360, 360);
    render(<Panel />);
    expect(screen.getByRole('region', { name: 'Panel' })).not.toHaveAttribute('tabindex');
  });

  it('follows the content as it grows and shrinks', () => {
    widths(360, 360);
    const { rerender } = render(<Panel />);
    widths(900, 360);
    rerender(<Panel />);
    expect(screen.getByRole('region', { name: 'Panel' })).toHaveAttribute('tabindex', '0');
    widths(360, 360);
    rerender(<Panel />);
    expect(screen.getByRole('region', { name: 'Panel' })).not.toHaveAttribute('tabindex');
  });
});
