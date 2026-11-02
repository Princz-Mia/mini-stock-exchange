import { zodResolver } from '@hookform/resolvers/zod';
import { Separator } from '@radix-ui/react-separator';
import { createChart } from 'lightweight-charts';
import { useEffect, useRef } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';

import { RouteTable } from './RouteTable';

/**
 * Foundation placeholder that drives every pinned UI library through the real build.
 * It shows no market data; the trading terminal replaces it.
 */
const SymbolForm = z.object({
  symbol: z.string().regex(/^[A-Z]{1,8}$/, 'Use 1-8 capital letters'),
});
type SymbolForm = z.infer<typeof SymbolForm>;

export function CompatibilityProbe() {
  const chartHost = useRef<HTMLDivElement>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SymbolForm>({ resolver: zodResolver(SymbolForm), defaultValues: { symbol: '' } });

  useEffect(() => {
    if (chartHost.current === null) {
      return;
    }
    const chart = createChart(chartHost.current, { autoSize: true });
    return () => chart.remove();
  }, []);

  return (
    <main>
      <RouteTable />
      <Separator decorative />
      <form onSubmit={(event) => void handleSubmit(() => undefined)(event)} noValidate>
        <label htmlFor="symbol">Symbol format check</label>
        <input id="symbol" aria-invalid={errors.symbol !== undefined} {...register('symbol')} />
        <button type="submit">Check</button>
        {errors.symbol !== undefined && <p role="alert">{errors.symbol.message}</p>}
      </form>
      <div ref={chartHost} className="chart" data-testid="chart" />
    </main>
  );
}
