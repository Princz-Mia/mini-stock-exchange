import { describe, expect, it } from 'vitest';

import { DEFAULT_STRATEGY, populationText, STRATEGIES, strategyNoun, strategyTag } from './strategies';

describe('bot strategies', () => {
  it('lists the strategies in the order runs assign them, noise first chosen', () => {
    expect(STRATEGIES.map((option) => option.value)).toEqual(['MARKET_MAKER', 'NOISE', 'MOMENTUM']);
    expect(DEFAULT_STRATEGY).toBe('NOISE');
  });

  it('names a batch by its strategy and size', () => {
    expect(strategyNoun('MARKET_MAKER', 1)).toBe('a market maker');
    expect(strategyNoun('MARKET_MAKER', 4)).toBe('market makers');
    expect(strategyNoun('NOISE', 1)).toBe('a noise bot');
    expect(strategyNoun('NOISE', 12)).toBe('noise bots');
    expect(strategyNoun('MOMENTUM', 1)).toBe('a momentum bot');
    expect(strategyNoun('MOMENTUM', 2)).toBe('momentum bots');
  });

  it('tags an account with its strategy', () => {
    expect(STRATEGIES.map((option) => strategyTag(option.value))).toEqual(['market maker', 'noise', 'momentum']);
  });

  it('counts the population by strategy, leaving out strategies with none', () => {
    expect(populationText({ marketMakers: 4, noise: 12, momentum: 4, untagged: 0 })).toBe(
      '4 market makers · 12 noise · 4 momentum',
    );
    expect(populationText({ marketMakers: 1, noise: 0, momentum: 0, untagged: 1200 })).toBe(
      '1 market maker · 1,200 untagged',
    );
    expect(populationText({ marketMakers: 0, noise: 0, momentum: 0, untagged: 0 })).toBe('');
  });
});
