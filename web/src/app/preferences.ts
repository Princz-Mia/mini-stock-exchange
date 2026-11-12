/**
 * Display preferences kept in this browser: the colour theme and the chart's settings. This is the only module
 * the lint rules let touch browser storage, and it only ever writes the keys below, never a credential, token or
 * account figure. Storage can be missing or refuse access (a private window, blocked site data), so a read that fails
 * gives the default and a write that fails changes nothing but this page.
 */

export const THEMES = ['system', 'light', 'dark'] as const;

export type Theme = (typeof THEMES)[number];

/** Every key this app stores. `public/theme.js` reads the theme key before the app loads, so it must match. */
export const PREFERENCE_KEYS = { theme: 'mse.theme', chart: 'mse.chart' } as const;

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      window.localStorage.removeItem(key);
    } else {
      window.localStorage.setItem(key, value);
    }
  } catch {
    // Not remembered; the page still shows the choice until it is reloaded.
  }
}

/** The stored theme, or "system" when there is none or it is not one of ours. */
export function storedTheme(): Theme {
  const value = read(PREFERENCE_KEYS.theme);
  return THEMES.includes(value as Theme) ? (value as Theme) : 'system';
}

/** Following the system is the default, so it is stored as no entry at all. */
export function storeTheme(theme: Theme): void {
  write(PREFERENCE_KEYS.theme, theme === 'system' ? null : theme);
}

/**
 * The chart's last settings, in the form the address carries them (`chart=line&range=1d&bars=5m`), so the chart
 * reads them as it reads the address and ignores whatever it does not know. Empty when nothing is stored.
 */
export function storedChart(): URLSearchParams {
  return new URLSearchParams(read(PREFERENCE_KEYS.chart) ?? '');
}

export function storeChart(settings: URLSearchParams): void {
  write(PREFERENCE_KEYS.chart, settings.toString());
}
