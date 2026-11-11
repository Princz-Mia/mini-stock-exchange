// Checks the storage rules in eslint.config.js by linting small snippets as if they were files in src/. Linting the
// real code only shows nothing breaks a rule; this shows the rules still forbid what they should, and allow only
// localStorage in the display preferences module (and in tests, which read it to check what was stored).
import { ESLint } from 'eslint';

const RULES = new Set(['no-restricted-globals', 'no-restricted-properties']);

const CASES = [
  ['src/app/probe.ts', 'export const store = localStorage;', true],
  ['src/app/probe.ts', 'export const store = window.localStorage;', true],
  ['src/terminal/probe.tsx', 'export const store = globalThis.sessionStorage;', true],
  ['src/auth/probe.ts', 'export const store = self.indexedDB;', true],
  ['src/app/preferences.ts', 'export const store = window.localStorage;', false],
  ['src/app/preferences.ts', 'export const store = localStorage;', false],
  ['src/app/preferences.ts', 'export const store = window.sessionStorage;', true],
  ['src/app/preferences.ts', 'export const store = indexedDB;', true],
  ['src/app/probe.test.ts', 'export const store = window.localStorage;', false],
  ['src/app/probe.test.ts', 'export const store = sessionStorage;', true],
];

const eslint = new ESLint();
const wrong = [];
for (const [filePath, code, banned] of CASES) {
  const [result] = await eslint.lintText(code, { filePath });
  const flagged = result.messages.some((message) => RULES.has(message.ruleId));
  if (flagged !== banned) {
    wrong.push(`${filePath}: \`${code}\` should be ${banned ? 'refused' : 'allowed'}`);
  }
}
if (wrong.length > 0) {
  console.error(`Storage lint rules are wrong:\n  ${wrong.join('\n  ')}`);
  process.exit(1);
}
console.log(`Storage lint rules: ${CASES.length} cases as expected.`);
