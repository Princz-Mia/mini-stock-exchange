/**
 * Letters and digits that can't be mistaken for one another when read aloud or copied by hand: no 0, O or o, and no 1,
 * l or I.
 */
export const GENERATOR_ALPHABET = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export const GENERATED_LENGTH = 20;

/** Fills an array with random bytes; the browser's `crypto.getRandomValues`, or a seeded source in tests. */
export type RandomBytes = (bytes: Uint8Array<ArrayBuffer>) => void;

const browserRandom: RandomBytes = (bytes) => {
  crypto.getRandomValues(bytes);
};

/**
 * A password of {@link GENERATED_LENGTH} characters from {@link GENERATOR_ALPHABET}, from the browser's cryptographic
 * random source. Bytes that would favour the first letters are drawn again, so every character is equally likely:
 * about 116 bits. It is made here and goes only to the exchange, never to storage.
 */
export function generatePassword(random: RandomBytes = browserRandom): string {
  const size = GENERATOR_ALPHABET.length;
  const limit = 256 - (256 % size);
  const chosen: string[] = [];
  const bytes = new Uint8Array(GENERATED_LENGTH * 2);
  while (chosen.length < GENERATED_LENGTH) {
    random(bytes);
    for (const byte of bytes) {
      if (byte < limit && chosen.length < GENERATED_LENGTH) {
        chosen.push(GENERATOR_ALPHABET.charAt(byte % size));
      }
    }
  }
  return chosen.join('');
}
