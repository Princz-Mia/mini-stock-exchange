import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';

import { AccountPage } from './account/AccountPage';
import { isRefusal } from './api/client';
import { AppShell, Home, RequireRole } from './app/AppShell';
import { LoginPage } from './auth/LoginPage';
import { HowItWorks } from './help/HowItWorks';
import { OperatorPage } from './operator/OperatorPage';
import { RunComparePage } from './operator/RunComparePage';
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
      {/* Each new address commits with the event that made it, not in a transition: state kept in the address, such
          as the runs ticked to compare or a table's page size, is then current for the next event, and a box or a
          choice bound to it never springs back while a transition is pending. No route suspends, so none waits. */}
      <BrowserRouter useTransitions={false}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<AppShell />}>
            <Route path="/help" element={<HowItWorks />} />
            <Route element={<RequireRole role="TRADER" />}>
              <Route path="/terminal" element={<TerminalPage />} />
              <Route path="/portfolio" element={<PortfolioPage />} />
            </Route>
            {/* An operator's password comes from configuration, so one who also trades has no account page. */}
            <Route element={<RequireRole role="TRADER" unless="OPERATOR" />}>
              <Route path="/account" element={<AccountPage />} />
            </Route>
            <Route element={<RequireRole role="OPERATOR" />}>
              <Route path="/operator" element={<OperatorPage />} />
              <Route path="/operator/runs/compare" element={<RunComparePage />} />
            </Route>
            <Route path="*" element={<Home />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
