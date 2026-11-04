import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router';

import { isRefusal } from './api/client';
import { AppShell } from './app/AppShell';
import { LoginPage } from './auth/LoginPage';
import { MarketsPage } from './markets/MarketsPage';
import { PortfolioPage } from './portfolio/PortfolioPage';

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
            <Route path="/terminal" element={<MarketsPage />} />
            <Route path="/portfolio" element={<PortfolioPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/terminal" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
