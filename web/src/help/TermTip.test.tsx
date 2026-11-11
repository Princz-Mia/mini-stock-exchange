import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { HowItWorks } from './HowItWorks';
import { GLOSSARY, type Term } from './glossary';
import { TermTip, placeTip } from './TermTip';

afterEach(() => vi.restoreAllMocks());

/** The tip's text, the element the button controls. */
function tipOf(button: HTMLElement): HTMLElement {
  return document.getElementById(button.getAttribute('aria-controls')!)!;
}

describe('TermTip', () => {
  it('says what a term means, from the glossary, when its button is pressed, and again hides it', () => {
    render(<TermTip terms={['reserved']} />);
    const button = screen.getByRole('button', { name: 'What is reserved?' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(tipOf(button)).toHaveAttribute('popover', 'auto');
    // Nothing to read while closed, so the page's text is only what is on screen.
    expect(tipOf(button)).toBeEmptyDOMElement();

    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(tipOf(button)).toHaveTextContent(`${GLOSSARY.reserved.term}: ${GLOSSARY.reserved.definition}`);
    // A polite live region says it as it opens: a description added to the focused button would not be read.
    const said = document.querySelector('[aria-live="polite"]')!;
    expect(said).toHaveTextContent(`${GLOSSARY.reserved.term}: ${GLOSSARY.reserved.definition}`);

    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(tipOf(button)).toBeEmptyDOMElement();
    expect(said).toBeEmptyDOMElement();
  });

  it('closes on a press of its button while open, which light dismiss has already closed by the click', () => {
    render(<TermTip terms={['mark']} />);
    const button = screen.getByRole('button', { name: 'What is mark?' });
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');

    // As a browser does: the press counts as outside the popover, so it closes before the click arrives.
    fireEvent.pointerDown(button);
    act(() => tipOf(button).hidePopover());
    fireEvent.click(button);

    expect(button).toHaveAttribute('aria-expanded', 'false');
    // And the next press opens it again.
    fireEvent.pointerDown(button);
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
  });

  it('names two terms together, and shows both', () => {
    render(<TermTip terms={['limit', 'protected']} />);
    const button = screen.getByRole('button', { name: 'What are limit order and protected market order?' });
    fireEvent.click(button);
    expect(tipOf(button)).toHaveTextContent(GLOSSARY.limit.definition);
    expect(tipOf(button)).toHaveTextContent(GLOSSARY.protected.definition);
  });

  it('is placed from its button, the size of its text and the screen', () => {
    render(<TermTip terms={['mark']} />);
    const button = screen.getByRole('button', { name: 'What is mark?' });
    const tip = tipOf(button);
    vi.spyOn(button, 'getBoundingClientRect').mockReturnValue({ top: 100, bottom: 120, left: 300 } as DOMRect);
    vi.spyOn(tip, 'offsetWidth', 'get').mockReturnValue(300);
    vi.spyOn(tip, 'offsetHeight', 'get').mockReturnValue(80);
    vi.spyOn(window, 'innerWidth', 'get').mockReturnValue(390);
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(844);
    fireEvent.click(button);
    expect([tip.style.top, tip.style.left]).toEqual(['126px', '74px']);
  });

  it('closes when the page scrolls or the window is resized', () => {
    render(<TermTip terms={['mark']} />);
    const button = screen.getByRole('button', { name: 'What is mark?' });
    fireEvent.click(button);
    fireEvent.scroll(window);
    expect(button).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(button);
    fireEvent(window, new Event('resize'));
    expect(button).toHaveAttribute('aria-expanded', 'false');
  });

  it("shows each term's text exactly as the glossary has it", () => {
    for (const term of Object.keys(GLOSSARY) as Term[]) {
      const { unmount } = render(<TermTip terms={[term]} />);
      const button = screen.getByRole('button');
      fireEvent.click(button);
      expect(tipOf(button).textContent, term).toBe(`${GLOSSARY[term].term}: ${GLOSSARY[term].definition}`);
      unmount();
    }
  });
});

describe('placeTip', () => {
  const screenSize = { width: 390, height: 844 };
  const tip = { width: 300, height: 160 };

  it('goes under its button, 6 pixels down, when there is room', () => {
    expect(placeTip({ top: 100, bottom: 120, left: 40 }, tip, screenSize)).toEqual({ top: 126, left: 40 });
  });

  it('goes above its button when it would pass the bottom edge, and never above the top edge', () => {
    expect(placeTip({ top: 700, bottom: 720, left: 40 }, tip, screenSize)).toEqual({ top: 534, left: 40 });
    // Exactly 16 pixels from the bottom still fits below.
    expect(placeTip({ top: 642, bottom: 662, left: 40 }, tip, screenSize).top).toBe(668);
    expect(placeTip({ top: 60, bottom: 700, left: 40 }, tip, screenSize).top).toBe(16);
  });

  it('keeps 16 pixels from the left and right edges', () => {
    expect(placeTip({ top: 0, bottom: 20, left: 300 }, tip, screenSize).left).toBe(74);
    expect(placeTip({ top: 0, bottom: 20, left: 4 }, tip, screenSize).left).toBe(16);
  });
});

describe('How this exchange works', () => {
  it('explains every term in the glossary, worded as the tips word it', () => {
    render(<HowItWorks />);
    expect(screen.getByRole('heading', { level: 1, name: 'How this exchange works' })).toBeInTheDocument();
    for (const term of Object.keys(GLOSSARY) as Term[]) {
      const entry = document.getElementById(`term-${term}`)!;
      expect(entry).toHaveTextContent(`${GLOSSARY[term].term}${GLOSSARY[term].definition}`);
    }
  });

  it('explains the order terms with the orders, the money terms with cash and shares, and halts on their own', () => {
    render(<HowItWorks />);
    const section = (name: string) => screen.getByRole('region', { name });
    for (const term of ['limit', 'protected', 'partial'] as const) {
      expect(section('Orders and trades')).toHaveTextContent(GLOSSARY[term].definition);
    }
    for (const term of ['available', 'reserved', 'mark', 'equity'] as const) {
      expect(section('Your cash and shares')).toHaveTextContent(GLOSSARY[term].definition);
    }
    expect(section('When trading is halted')).toHaveTextContent(GLOSSARY.halted.definition);
  });
});
