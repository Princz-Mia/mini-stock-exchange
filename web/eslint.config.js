import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

const STORAGE = ['localStorage', 'sessionStorage', 'indexedDB'];

/** Bans each named store, as a global and as a property of the global object. */
function noStorage(names, message) {
  return {
    'no-restricted-globals': ['error', ...names.map((name) => ({ name, message }))],
    'no-restricted-properties': [
      'error',
      ...['window', 'globalThis', 'self'].flatMap((object) => names.map((property) => ({ object, property, message }))),
    ],
  };
}

const NO_STORAGE = 'No browser storage: the session is an HttpOnly cookie and the CSRF token stays in memory.';

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { globals: globals.browser },
    plugins: { 'react-hooks': reactHooks },
    rules: reactHooks.configs.recommended.rules,
  },
  {
    // Credentials and tokens never go into browser storage: the session is an HttpOnly cookie and the CSRF token
    // lives in memory. Display preferences are the one exception, and only through their own module.
    files: ['src/**/*.{ts,tsx}'],
    rules: noStorage(STORAGE, NO_STORAGE),
  },
  {
    // The theme and chart settings, in localStorage only, and nothing else. Tests may read it to check that.
    files: ['src/app/preferences.ts', 'src/**/*.test.{ts,tsx}'],
    rules: noStorage(
      STORAGE.filter((name) => name !== 'localStorage'),
      'Display preferences use localStorage only.',
    ),
  },
);
