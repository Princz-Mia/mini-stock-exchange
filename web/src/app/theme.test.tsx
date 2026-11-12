import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The real files, as text: the script the page's <head> loads, and the stylesheet.
import headScript from '../../public/theme.js?raw';
import css from '../styles.css?raw';
import { PREFERENCE_KEYS, storeTheme, storedTheme } from './preferences';
import { ThemeSwitch } from './ThemeSwitch';
import { applyTheme, currentScheme, startTheme, useScheme } from './theme';

const root = document.documentElement;

/** A system scheme the test can change, as the browser does when the user switches the operating system's. */
function systemScheme(initial: 'light' | 'dark') {
  let dark = initial === 'dark';
  const listeners = new Set<() => void>();
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return query === '(prefers-color-scheme: dark)' && dark;
    },
    media: query,
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }));
  return {
    set(next: 'light' | 'dark') {
      dark = next === 'dark';
      listeners.forEach((listener) => listener());
    },
  };
}

/** Runs the real `public/theme.js` the way the page's `<head>` does, before any of the app's code. */
function runHeadScript() {
  new Function(headScript)();
}

/** Only this app's own keys, so a test proves nothing else was stored. */
const storedKeys = () => Object.keys(window.localStorage);

beforeEach(() => {
  window.localStorage.clear();
  delete root.dataset.theme;
  systemScheme('light');
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('stored theme', () => {
  it('follows the system until a theme is chosen, and stores following the system as nothing', () => {
    expect(storedTheme()).toBe('system');
    storeTheme('dark');
    expect(window.localStorage.getItem(PREFERENCE_KEYS.theme)).toBe('dark');
    expect(storedTheme()).toBe('dark');
    storeTheme('system');
    expect(storedKeys()).toEqual([]);
  });

  it('ignores a stored value that is not a theme', () => {
    window.localStorage.setItem(PREFERENCE_KEYS.theme, 'sepia');
    expect(storedTheme()).toBe('system');
  });

  it('follows the system when storage refuses access, and choosing still works for the page', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(storedTheme()).toBe('system');
    expect(() => storeTheme('dark')).not.toThrow();
    runHeadScript();
    expect(root.dataset.theme).toBeUndefined();

    const first = render(<ThemeSwitch />);
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'dark' } });
    expect(root.dataset.theme).toBe('dark');

    // Signing out and in again mounts a new switch; it shows the theme on screen, not the one storage lost.
    first.unmount();
    render(<ThemeSwitch />);
    expect(screen.getByLabelText('Theme')).toHaveValue('dark');
  });
});

describe('the head script', () => {
  it('forces the stored theme before the app loads, under the key the app stores', () => {
    storeTheme('dark');
    runHeadScript();
    expect(root.dataset.theme).toBe('dark');
  });

  it('wins over the system: a stored light theme on a dark system draws light', () => {
    systemScheme('dark');
    expect(currentScheme()).toBe('dark');
    storeTheme('light');
    runHeadScript();
    expect(root.dataset.theme).toBe('light');
    expect(currentScheme()).toBe('light');
  });

  it('leaves the page to the system without a stored theme, or with one it does not know', () => {
    runHeadScript();
    expect(root.hasAttribute('data-theme')).toBe(false);
    window.localStorage.setItem(PREFERENCE_KEYS.theme, 'system');
    runHeadScript();
    expect(root.hasAttribute('data-theme')).toBe(false);
  });
});

describe('ThemeSwitch', () => {
  it('forces a theme at once and remembers it, and System lets the page follow the system again', async () => {
    render(<ThemeSwitch />);
    const select = screen.getByLabelText('Theme');
    expect(select).toHaveValue('system');

    fireEvent.change(select, { target: { value: 'dark' } });
    expect(root.dataset.theme).toBe('dark');
    expect(storedTheme()).toBe('dark');
    await waitFor(() => expect(select).toHaveValue('dark'));

    fireEvent.change(select, { target: { value: 'light' } });
    expect(root.dataset.theme).toBe('light');
    await waitFor(() => expect(select).toHaveValue('light'));

    fireEvent.change(select, { target: { value: 'system' } });
    expect(root.hasAttribute('data-theme')).toBe(false);
    expect(storedKeys()).toEqual([]);
    await waitFor(() => expect(select).toHaveValue('system'));
  });

  it('shows the theme the page was started with', () => {
    storeTheme('light');
    const stop = startTheme();
    render(<ThemeSwitch />);
    expect(screen.getByLabelText('Theme')).toHaveValue('light');
    stop();
  });

  it('stores nothing but the theme', () => {
    render(<ThemeSwitch />);
    fireEvent.change(screen.getByLabelText('Theme'), { target: { value: 'dark' } });
    expect(storedKeys().every((key) => Object.values<string>(PREFERENCE_KEYS).includes(key))).toBe(true);
    // eslint-disable-next-line no-restricted-properties -- only reads it, to show nothing went there
    expect(window.sessionStorage.length).toBe(0);
  });
});

describe('another tab', () => {
  /** What the browser sends this tab when another tab of the same site writes to storage. */
  const fromAnotherTab = (key: string | null, value: string | null) => {
    if (key !== null) {
      if (value === null) {
        window.localStorage.removeItem(key);
      } else {
        window.localStorage.setItem(key, value);
      }
    }
    window.dispatchEvent(new StorageEvent('storage', { key, newValue: value }));
  };

  it('applies a theme chosen there on any page, and a switch shown later agrees with the page', () => {
    // On the sign-in page there is no switch; the app follows other tabs from the start.
    const stop = startTheme();
    fromAnotherTab(PREFERENCE_KEYS.theme, 'dark');
    expect(root.dataset.theme).toBe('dark');

    render(<ThemeSwitch />);
    expect(screen.getByLabelText('Theme')).toHaveValue('dark');

    act(() => fromAnotherTab(PREFERENCE_KEYS.theme, null));
    expect(root.hasAttribute('data-theme')).toBe(false);
    return waitFor(() => expect(screen.getByLabelText('Theme')).toHaveValue('system')).finally(stop);
  });

  it('ignores other keys, and follows storage cleared there', () => {
    const stop = startTheme();
    applyTheme('dark');
    window.localStorage.setItem(PREFERENCE_KEYS.theme, 'dark');
    // Another key changing says nothing about the theme, even if this tab's own copy were stale.
    window.localStorage.removeItem(PREFERENCE_KEYS.theme);
    fromAnotherTab('something-else', '1');
    expect(root.dataset.theme).toBe('dark');

    window.localStorage.clear();
    fromAnotherTab(null, null);
    expect(root.hasAttribute('data-theme')).toBe(false);
    stop();
  });
});

describe('useScheme', () => {
  it('follows the system, and a forced theme over it', () => {
    const system = systemScheme('light');
    const { result } = renderHook(() => useScheme());
    expect(result.current).toBe('light');

    act(() => system.set('dark'));
    expect(result.current).toBe('dark');

    // The attribute changes through a mutation observer, which reports after the current task.
    act(() => {
      root.dataset.theme = 'light';
    });
    return vi.waitFor(() => expect(result.current).toBe('light'));
  });
});

describe('the stylesheet', () => {
  /** The custom properties set in the first block that starts with `selector`, in order. */
  function tokens(selector: string): string[] {
    const start = css.indexOf(selector);
    expect(start, `${selector} is in the stylesheet`).toBeGreaterThanOrEqual(0);
    const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start));
    return body
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.startsWith('--'));
  }

  it('draws the same dark scheme when the system asks for it as when it is forced', () => {
    expect(tokens(":root:not([data-theme='light'])")).toEqual(tokens(":root[data-theme='dark']"));
  });

  it('keeps the dark palette exactly as it was', () => {
    expect(tokens(":root[data-theme='dark']")).toEqual([
      '--bg: #121017;',
      '--surface: #1b1822;',
      '--surface-sunken: #24202d;',
      '--text: #ece9f2;',
      '--muted: #a39fb0;',
      '--line: #322e3d;',
      '--accent: #d1386a;',
      '--secondary: #9b82e0;',
      '--buy: #4cc27b;',
      '--sell: #f0646d;',
      '--fill-text: #121017;',
      '--warn-bg: #3a2e12;',
      '--warn-text: #f3d48b;',
    ]);
  });

  it('applies the dark tokens only under a dark system scheme unless light is forced, after the light ones', () => {
    expect(css).toMatch(/@media \(prefers-color-scheme: dark\) \{\s*:root:not\(\[data-theme='light'\]\) \{/);
    const light = css.indexOf(':root {');
    expect(light).toBeGreaterThanOrEqual(0);
    expect(css.slice(light, css.indexOf('}', light))).toContain('color-scheme: light dark;');
    for (const later of ['@media (prefers-color-scheme: dark)', ":root[data-theme='dark']", ":root[data-theme='light']"]) {
      expect(css.indexOf(later), `${later} comes after the light tokens`).toBeGreaterThan(light);
    }
  });

  it('sets the scheme for form controls and scrollbars when a theme is forced', () => {
    const forced = (theme: string) => {
      const start = css.indexOf(`:root[data-theme='${theme}']`);
      return css.slice(start, css.indexOf('}', start));
    };
    expect(forced('dark')).toContain('color-scheme: dark;');
    expect(forced('light')).toContain('color-scheme: light;');
  });
});
