import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';

import { App } from './App';

// Canvas rendering is out of reach in jsdom; the production build still bundles the real library.
vi.mock('lightweight-charts', () => ({
  createChart: () => ({ remove: () => undefined }),
}));

describe('foundation shell', () => {
  it('keeps the simulation context visible', () => {
    render(<App />);

    expect(screen.getByText('Simulation · Virtual funds')).toBeInTheDocument();
  });

  it('renders the route ownership table', () => {
    render(<App />);

    const table = screen.getByRole('table', { name: 'API route owners' });
    expect(table).toHaveTextContent('Market Data');
    expect(table).toHaveTextContent('Execution');
  });

  it('validates through react-hook-form and zod with a labelled field', async () => {
    render(<App />);

    fireEvent.change(screen.getByLabelText('Symbol format check'), { target: { value: 'nova' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Use 1-8 capital letters');
  });
});
