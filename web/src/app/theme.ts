import { useSyncExternalStore } from 'react';

import { PREFERENCE_KEYS, THEMES, type Theme, storeTheme, storedTheme } from './preferences';

export type Scheme = 'light' | 'dark';

const SYSTEM_DARK = '(prefers-color-scheme: dark)';

/**
 * Forces a scheme on the page, or lets it follow the system again. The stylesheet keys off `data-theme` on `<html>`,
 * the same attribute `public/theme.js` sets before the first paint. That attribute is the one record of the theme on
 * screen: everything else reads it back rather than keeping a copy that could disagree.
 */
export function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  if (theme === 'system') {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = theme;
  }
}

/** The theme applied to the page now. */
export function appliedTheme(): Theme {
  const forced = document.documentElement.dataset.theme;
  return THEMES.includes(forced as Theme) ? (forced as Theme) : 'system';
}

/**
 * Applies the stored theme, and from then on any theme chosen in another tab of this browser, on every page, the
 * sign-in page included. Called once when the app starts; the returned function stops following other tabs.
 */
export function startTheme(): () => void {
  applyTheme(storedTheme());
  const onStorage = (event: StorageEvent) => {
    // A null key means another tab cleared this site's storage.
    if (event.key === PREFERENCE_KEYS.theme || event.key === null) {
      applyTheme(storedTheme());
    }
  };
  window.addEventListener('storage', onStorage);
  return () => window.removeEventListener('storage', onStorage);
}

function systemScheme(): Scheme {
  return typeof window.matchMedia === 'function' && window.matchMedia(SYSTEM_DARK).matches ? 'dark' : 'light';
}

/** The scheme the page is drawn in now: the forced one, or the system's. */
export function currentScheme(): Scheme {
  const theme = appliedTheme();
  return theme === 'system' ? systemScheme() : theme;
}

function onThemeChange(changed: () => void): () => void {
  const observer = new MutationObserver(changed);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  return () => observer.disconnect();
}

function onSchemeChange(changed: () => void): () => void {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia(SYSTEM_DARK) : null;
  media?.addEventListener('change', changed);
  const stopWatching = onThemeChange(changed);
  return () => {
    media?.removeEventListener('change', changed);
    stopWatching();
  };
}

/**
 * The scheme the page is drawn in, updated when the system's changes or a theme is forced. For what draws its own
 * colours, such as the chart's canvas, which cannot follow the stylesheet by itself.
 */
export function useScheme(): Scheme {
  return useSyncExternalStore<Scheme>(onSchemeChange, currentScheme, () => 'light');
}

/**
 * The theme on screen, and a way to choose another: stored in this browser and applied at once. It reads the page
 * rather than storage, so it shows what is applied even when storage refused the choice or another tab changed it.
 */
export function useTheme(): [Theme, (theme: Theme) => void] {
  const theme = useSyncExternalStore<Theme>(onThemeChange, appliedTheme, () => 'system');
  const choose = (next: Theme) => {
    storeTheme(next);
    applyTheme(next);
  };
  return [theme, choose];
}
