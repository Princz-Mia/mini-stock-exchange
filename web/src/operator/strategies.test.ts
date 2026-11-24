import { describe, expect, it } from 'vitest';

import { populationText, STRATEGIES, strategyTag } from './strategies';

describe('bot strategies', () => {
  it('lists the strategies in the order runs assign them', () => {
    expect(STRATEGIES.map((option) => option.value)).toEqual(['MARKET_MAKER', 'NOISE', 'MOMENTUM']);
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
