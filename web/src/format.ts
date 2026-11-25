/**
 * Display helpers. Amounts arrive as exact decimal strings such as "10000.00" and stay strings: they are never
 * turned into floating-point numbers, so nothing on screen can be off by a cent.
 */

/** "10000.00" becomes "10,000.00"; "-1234.50" becomes "-1,234.50". */
export function groupDigits(amount: string): string {
  const negative = amount.startsWith('-');
  const [whole = '0', fraction] = (negative ? amount.slice(1) : amount).split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}${grouped}${fraction === undefined ? '' : `.${fraction}`}`;
}

/** Whole numbers such as share counts, grouped the same way. */
export function groupInteger(value: number): string {
  return groupDigits(String(value));
}

const timeFormat = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  timeZone: 'UTC',
});

/** Times are shown in UTC and say so, like the API that sends them. */
export function utcTime(iso: string): string {
  return `${timeFormat.format(new Date(iso))} UTC`;
}

const dateFormat = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });

/** "5 Nov 2026, 16:00:00 UTC": for a moment that may be read days later, such as when a market closed. */
export function utcDateTime(iso: string): string {
  return `${dateFormat.format(new Date(iso))}, ${utcTime(iso)}`;
}
