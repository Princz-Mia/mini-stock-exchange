import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CommandStatus } from './CommandStatus';

describe('the status of a command', () => {
  afterEach(() => vi.useRealTimers());

  it('names the command and counts the seconds it has been on its way', () => {
    vi.useFakeTimers();
    render(
      <CommandStatus
        outcome={{ kind: 'sending', since: Date.now() }}
        action="Starting the run"
        done={() => ''}
        onRetry={() => undefined}
      />,
    );
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Starting the run… 0 s');

    act(() => vi.advanceTimersByTime(7_000));
    expect(status).toHaveTextContent('Starting the run… 7 s');
    // A screen reader hears the action once, not a new count every second.
    expect(screen.getByText('7 s')).toHaveAttribute('aria-hidden', 'true');
  });

  it('counts from when the command was sent, not from when the status was drawn', () => {
    vi.useFakeTimers();
    const since = Date.now() - 12_000;
    render(<CommandStatus outcome={{ kind: 'sending', since }} action="Halting trading" done={() => ''} onRetry={() => undefined} />);

    expect(screen.getByRole('status')).toHaveTextContent('Halting trading… 12 s');
  });

  it('says when no answer came at all, and retries under the same key when asked', () => {
    const retry = vi.fn();
    render(
      <CommandStatus
        outcome={{ kind: 'unresolved', problem: null, waited: 20_000 }}
        action="Starting the run"
        done={() => ''}
        onRetry={retry}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Not confirmed: no answer came within 20 s, so it may or may not have applied. Retrying will not apply it twice.',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
