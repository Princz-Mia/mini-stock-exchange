import type { BotJob } from '../api/types';
import { groupInteger } from '../format';

/** The most bots one job asks for: what a count holds at the exchange. */
export const MAX_BOTS = 2_147_483_647;

/** The usual split, as the demo seed makes it: a fifth market makers, a fifth momentum, the rest noise. */
export const USUAL_SHARES = { marketMakers: '20', momentum: '20' } as const;

export interface Mix {
  marketMakers: number;
  noise: number;
  momentum: number;
}

/** A whole number as typed, with thousands set apart by commas, spaces or underscores; null for anything else. */
function wholeNumber(text: string): number | null {
  const digits = text.trim().replace(/[\s,_]/g, '');
  return /^\d+$/.test(digits) ? Number(digits) : null;
}

/**
 * The exact count of each strategy that a number of bots and the market makers' and momentum shares give: each share
 * of the total rounded down, as the demo seed rounds a fifth, and noise the rest, so the three always add up to the
 * total. Or why they cannot be worked out, in words.
 */
export function resolveMix(
  count: string,
  shares: { marketMakers: string; momentum: string },
): { mix: Mix; total: number } | { problem: string } {
  if (count.trim() === '') {
    return { problem: 'Type how many bots to create.' };
  }
  const total = wholeNumber(count);
  if (total === null || total < 1 || total > MAX_BOTS) {
    return { problem: `The number of bots is a whole number from 1 to ${groupInteger(MAX_BOTS)}.` };
  }
  const makers = wholeNumber(shares.marketMakers);
  const momentum = wholeNumber(shares.momentum);
  if (makers === null || momentum === null || makers > 100 || momentum > 100) {
    return { problem: 'Each share is a whole percentage from 0 to 100.' };
  }
  if (makers + momentum > 100) {
    return { problem: 'Market makers and momentum together can be at most 100%; noise is the rest.' };
  }
  // At most 2^31 × 100, well inside what a double holds exactly.
  const marketMakers = Math.floor((total * makers) / 100);
  const momentumBots = Math.floor((total * momentum) / 100);
  return { total, mix: { marketMakers, noise: total - marketMakers - momentumBots, momentum: momentumBots } };
}

/** "4,000 market makers, 12,000 noise and 4,000 momentum bots", leaving out none so the sum can be checked. */
export function mixText({ marketMakers, noise, momentum }: Mix): string {
  return (
    `${groupInteger(marketMakers)} ${marketMakers === 1 ? 'market maker' : 'market makers'}, ` +
    `${groupInteger(noise)} noise and ${groupInteger(momentum)} momentum ${momentum === 1 ? 'bot' : 'bots'}`
  );
}

/** The whole percentage made, rounded down, so 100% means every bot is made. */
export function percentMade(job: Pick<BotJob, 'created' | 'total'>): number {
  return Math.floor((job.created * 100) / job.total);
}

/** "About 30 s left", "About 4 min left", "About 1 h 5 min left"; while there is no estimate yet, says so. */
export function timeLeftText(secondsLeft: number | null): string {
  if (secondsLeft === null) {
    return 'Working out the time left…';
  }
  if (secondsLeft < 60) {
    return `About ${Math.max(secondsLeft, 1)} s left`;
  }
  const minutes = Math.ceil(secondsLeft / 60);
  if (minutes < 60) {
    return `About ${minutes} min left`;
  }
  const rest = minutes % 60;
  return `About ${Math.floor(minutes / 60)} h${rest === 0 ? '' : ` ${rest} min`} left`;
}

/** "4,000 of 20,000 bots made (20%)": the bar's value in words, and what the panel says beside it. */
export function progressText(job: Pick<BotJob, 'created' | 'total'>): string {
  return `${groupInteger(job.created)} of ${groupInteger(job.total)} bots made (${percentMade(job)}%)`;
}

/** "Market makers 1,000 of 4,000 · Noise 2,000 of 12,000", leaving out strategies the job makes none of. */
export function strategiesText({ strategies }: Pick<BotJob, 'strategies'>): string {
  return (
    [
      ['Market makers', strategies.marketMakers],
      ['Noise', strategies.noise],
      ['Momentum', strategies.momentum],
    ] as const
  )
    .filter(([, progress]) => progress.requested > 0)
    .map(([label, progress]) => `${label} ${groupInteger(progress.created)} of ${groupInteger(progress.requested)}`)
    .join(' · ');
}

/** What a job that no longer runs came to, in one line, with what it made. */
export function outcomeText(job: BotJob): string {
  const made = groupInteger(job.created);
  const madeStay = job.created === 0 ? 'No bot was made.' : `The ${made} made stay.`;
  switch (job.state) {
    case 'DONE':
      return `Created ${groupInteger(job.total)} ${job.total === 1 ? 'bot' : 'bots'}: ${mixText({
        marketMakers: job.strategies.marketMakers.created,
        noise: job.strategies.noise.created,
        momentum: job.strategies.momentum.created,
      })}.`;
    case 'STOPPED':
      return `Stopped after ${made} of ${groupInteger(job.total)} bots. ${madeStay} The rest were not made.`;
    case 'FAILED':
      return `Creating bots failed after ${made} of ${groupInteger(job.total)}: ${job.failure ?? 'no reason was given'}. ${madeStay} Create the rest once the cause is fixed.`;
    case 'RUNNING':
      return progressText(job);
  }
}

/**
 * What a screen reader is told about a running job: at its start, at each quarter, and when it ends. Never on every
 * poll: the bar itself has the current value for whoever looks.
 */
export function milestone(job: BotJob): string {
  if (job.state !== 'RUNNING') {
    return outcomeText(job);
  }
  const quarter = Math.floor(percentMade(job) / 25) * 25;
  return quarter === 0
    ? `Creating ${groupInteger(job.total)} ${job.total === 1 ? 'bot' : 'bots'}.`
    : `${quarter}% of the ${groupInteger(job.total)} bots are made.`;
}
