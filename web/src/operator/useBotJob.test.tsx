import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import type { BotJob } from '../api/types';
import { botJob } from '../test/botJob';
import { useRefreshOnJobChange } from './useBotJob';

function watch(first: BotJob | null | undefined) {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, 'invalidateQueries').mockResolvedValue(undefined);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const hook = renderHook(({ job }: { job: BotJob | null | undefined }) => useRefreshOnJobChange(job), {
    wrapper,
    initialProps: { job: first },
  });
  /** The lists asked for again so far, each by its key, in order. */
  const asked = () => invalidate.mock.calls.map(([filters]) => (filters?.queryKey ?? [])[0]);
  return { hook, invalidate, asked };
}

const done = (job: BotJob): BotJob => ({ ...job, state: 'DONE', finishedAt: '2026-11-24T10:01:00Z' });

describe('useRefreshOnJobChange', () => {
  it('reads the bots and accounts again once when a job ends, not with each read of its progress', () => {
    const running = botJob({ total: 3_000 });
    const { hook, invalidate, asked } = watch(undefined);

    // The page as it opens: its lists are being read anyway.
    hook.rerender({ job: running });
    expect(invalidate).not.toHaveBeenCalled();
    for (const created of [1_000, 2_000, 2_500]) {
      hook.rerender({ job: { ...running, created, partsDone: created / 1_000 } });
    }
    expect(invalidate).not.toHaveBeenCalled();

    hook.rerender({ job: done({ ...running, created: 3_000 }) });
    expect(asked()).toEqual(['bots', 'accounts']);
    // A read already on its way is left to finish, not stopped and asked again.
    for (const [, options] of invalidate.mock.calls) {
      expect(options).toEqual({ cancelRefetch: false });
    }

    // The same finished job read again changes nothing.
    hook.rerender({ job: done({ ...running, created: 3_000 }) });
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it('reads them again when a job starts, and when a new job ends that the reads in between missed', () => {
    const first = done(botJob({ total: 10, created: 10 }));
    const { hook, asked } = watch(null);

    hook.rerender({ job: botJob({ jobId: '30000000-0000-0000-0000-000000000002' }) });
    expect(asked()).toEqual(['bots', 'accounts']);

    // Another short job came and went between two reads: the same state, another job.
    hook.rerender({ job: first });
    hook.rerender({ job: { ...first, jobId: '30000000-0000-0000-0000-000000000003' } });
    expect(asked()).toEqual(['bots', 'accounts', 'bots', 'accounts', 'bots', 'accounts']);
  });

  it('asks for nothing while the job has not been read', () => {
    const { hook, invalidate } = watch(undefined);

    hook.rerender({ job: undefined });

    expect(invalidate).not.toHaveBeenCalled();
  });
});
