import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Route, Routes } from 'react-router';

import { CompatibilityProbe } from './compat/CompatibilityProbe';

const queryClient = new QueryClient();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <header>
          <h1>Mini Stock Exchange</h1>
          <p className="context">Simulation · Virtual funds</p>
        </header>
        <Routes>
          <Route path="*" element={<CompatibilityProbe />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
