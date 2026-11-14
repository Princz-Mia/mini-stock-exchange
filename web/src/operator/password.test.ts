import { describe, expect, it, vi } from 'vitest';

import { seeded } from '../test/seeded';
import { GENERATED_LENGTH, generatePassword, GENERATOR_ALPHABET, type RandomBytes } from './password';

/** A repeatable byte source for the generator. */
function seededBytes(seed: number): RandomBytes {
  const draw = seeded(seed);
  return (bytes) => {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = draw(256);
    }
  };
}

describe('generated passwords', () => {
  it('are twenty characters that cannot be mistaken for one another', () => {
    expect(GENERATOR_ALPHABET).toHaveLength(56);
    expect(GENERATOR_ALPHABET).not.toMatch(/[0O1lIo]/);
    const password = generatePassword(seededBytes(7));
    expect(password).toHaveLength(GENERATED_LENGTH);
    expect([...password].every((char) => GENERATOR_ALPHABET.includes(char))).toBe(true);
  });

  it('come from the browser’s random source by default, and differ each time', () => {
    const random = vi.spyOn(crypto, 'getRandomValues');
    const made = new Set(Array.from({ length: 50 }, () => generatePassword()));
    expect(random).toHaveBeenCalled();
    random.mockRestore();
    expect(made.size).toBe(50);
    expect([...made].every((password) => password.length === GENERATED_LENGTH)).toBe(true);
  });

  it('draw again rather than favour the first letters', () => {
    // 256 is not a multiple of 56: bytes from 224 up would make the first 32 letters likelier, so they are skipped.
    let call = 0;
    const source: RandomBytes = (bytes) => {
      bytes.fill(call++ === 0 ? 250 : 57);
    };
    expect(generatePassword(source)).toBe(GENERATOR_ALPHABET.charAt(1).repeat(GENERATED_LENGTH));
  });

  it('use every character about equally', () => {
    const counts = new Map<string, number>();
    const random = seededBytes(2026);
    for (let i = 0; i < 2000; i++) {
      for (const char of generatePassword(random)) {
        counts.set(char, (counts.get(char) ?? 0) + 1);
      }
    }
    // 40,000 characters over 56: about 714 each.
    expect(counts.size).toBe(56);
    for (const count of counts.values()) {
      expect(count).toBeGreaterThan(600);
      expect(count).toBeLessThan(830);
    }
  });
});
