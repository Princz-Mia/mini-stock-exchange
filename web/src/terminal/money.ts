/** Exact amounts for the ticket's estimate: cents as BigInt, never a floating-point number. */

const PRICE = /^(0|[1-9]\d{0,18})\.\d{2}$/;

export function isPrice(text: string): boolean {
  return PRICE.test(text);
}

export function toCents(price: string): bigint {
  const [whole = '0', fraction = '00'] = price.split('.');
  return BigInt(whole) * 100n + BigInt(fraction);
}

export function fromCents(cents: bigint): string {
  const negative = cents < 0n;
  const absolute = negative ? -cents : cents;
  const fraction = (absolute % 100n).toString().padStart(2, '0');
  return `${negative ? '-' : ''}${absolute / 100n}.${fraction}`;
}

/** The most a buy can cost: the bound times the quantity, which is also what the exchange reserves. */
export function maximumSpend(bound: string, quantity: number): string {
  return fromCents(toCents(bound) * BigInt(quantity));
}
