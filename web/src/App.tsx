import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';

import { isRefusal } from './api/client';
import { AppShell, RequireRole } from './app/AppShell';
import { LoginPage } from './auth/LoginPage';
import { HowItWorks } from './help/HowItWorks';
import { OperatorPage } from './operator/OperatorPage';
import { PortfolioPage } from './portfolio/PortfolioPage';
import { TerminalPage } from './terminal/TerminalPage';

/** No optimistic updates anywhere: the screen shows what the server committed, refetched, never guessed. */
function newQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: (failures, error) => !isRefusal(error) && failures < 1 },
      mutations: { retry: false },
    },
  });
}

export function App() {
  const [queryClient] = useState(newQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<AppShell />}>
            <Route path="/help" element={<HowItWorks />} />
            <Route element={<RequireRole role="TRADER" />}>
              <Route path="/terminal" element={<TerminalPage />} />
              <Route path="/portfolio" element={<PortfolioPage />} />
            </Route>
            <Route element={<RequireRole role="OPERATOR" />}>
              <Route path="/operator" element={<OperatorPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/terminal" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
