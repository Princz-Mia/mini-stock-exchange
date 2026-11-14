import type { BotPopulation, BotStrategy } from '../api/types';
import { groupInteger } from '../format';

/** The strategies a bot can be tagged with, in the order runs assign them, with what each does. */
export const STRATEGIES: readonly { value: BotStrategy; label: string; description: string }[] = [
  {
    value: 'MARKET_MAKER',
    label: 'Market maker',
    description: 'Keeps a bid and an ask around the price, leaning against what it holds.',
  },
  { value: 'NOISE', label: 'Noise', description: 'Places random limit orders near the current price.' },
  { value: 'MOMENTUM', label: 'Momentum', description: 'Buys or sells with the recent direction of the price.' },
];

export const DEFAULT_STRATEGY: BotStrategy = 'NOISE';

/** "market makers", "noise bots", "a momentum bot": what a batch of bots with one tag is called. */
export function strategyNoun(strategy: BotStrategy, count: number): string {
  const one = count === 1;
  switch (strategy) {
    case 'MARKET_MAKER':
      return one ? 'a market maker' : 'market makers';
    case 'NOISE':
      return one ? 'a noise bot' : 'noise bots';
    case 'MOMENTUM':
      return one ? 'a momentum bot' : 'momentum bots';
  }
}

/** The tag in the account list: "market maker", "noise" or "momentum". */
export function strategyTag(strategy: BotStrategy): string {
  return STRATEGIES.find((option) => option.value === strategy)!.label.toLowerCase();
}

/** "4 market makers · 12 noise · 4 momentum", with untagged bots last and strategies with none left out. */
export function populationText({ marketMakers, noise, momentum, untagged }: BotPopulation['byStrategy']): string {
  return [
    marketMakers > 0 ? `${groupInteger(marketMakers)} market maker${marketMakers === 1 ? '' : 's'}` : null,
    noise > 0 ? `${groupInteger(noise)} noise` : null,
    momentum > 0 ? `${groupInteger(momentum)} momentum` : null,
    untagged > 0 ? `${groupInteger(untagged)} untagged` : null,
  ]
    .filter((part) => part !== null)
    .join(' · ');
}
