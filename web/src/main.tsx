import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { startTheme } from './app/theme';
import './styles.css';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('Missing #root element');
}

// theme.js has already applied the stored theme before the first paint; this also follows choices made in other tabs.
startTheme();

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
