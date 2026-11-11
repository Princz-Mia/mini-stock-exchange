import { THEMES, type Theme } from './preferences';
import { useTheme } from './theme';

const LABELS: Record<Theme, string> = { system: 'System', light: 'Light', dark: 'Dark' };

/** The colour theme for this browser: follow the system, or force light or dark. */
export function ThemeSwitch() {
  const [theme, choose] = useTheme();
  return (
    <label className="theme-switch">
      <span className="muted">Theme</span>
      <select value={theme} onChange={(event) => choose(event.target.value as Theme)}>
        {THEMES.map((each) => (
          <option key={each} value={each}>
            {LABELS[each]}
          </option>
        ))}
      </select>
    </label>
  );
}
