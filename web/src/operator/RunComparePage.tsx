import { useQueries } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';

import { ApiError, api, isRefusal } from '../api/client';
import type { BotRunDetail, BotRunMark, InstrumentFigures, StrategyFigures } from '../api/types';
import { groupDigits, groupInteger, utcTime } from '../format';
import {
  achievedRate,
  actualDuration,
  comparability,
  comparisonWarnings,
  fillRatio,
  marketMakerShare,
  parseRunIds,
  plannedDuration,
  refusalsText,
} from './compare';
import { STATUS_LABELS, strategiesText } from './runView';

/** While a run is not finished, or its figures are not worked out yet, it is asked for again this often. */
const PENDING_POLL = 5_000;

const STRATEGIES = [
  ['MARKET_MAKER', 'Market makers'],
  ['NOISE', 'Noise'],
  ['MOMENTUM', 'Momentum'],
] as const;

/** One column: the run as the server answered it, or why it isn't there. */
type Compared = { id: string; detail: BotRunDetail | undefined; missing: string | null };

/**
 * Two to four finished runs side by side: their settings, then what happened in each, from the figures worked out once
 * each run finished. The runs are the address's (`runs=`, comma separated), so a comparison can be shared and reopened;
 * a run that can't be compared keeps its column and says why. Simulation-model evidence: these are simulated bots.
 */
export function RunComparePage() {
  const [params] = useSearchParams();
  const { ids, dropped, overflow } = parseRunIds(params.get('runs'));
  const answers = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['bot-run', id],
      queryFn: () => api.botRun(id),
      // Figures never change once worked out; until then, and while the run goes on, ask again. A refusal is final.
      refetchInterval: (query: { state: { data: BotRunDetail | undefined; error: unknown } }) => {
        const { data, error } = query.state;
        if (isRefusal(error)) return false;
        if (data === undefined) return PENDING_POLL;
        const state = comparability(data.run);
        return state === 'NOT_KEPT' || (state === 'FINISHED' && data.figures !== null) ? false : PENDING_POLL;
      },
    })),
  });
  const columns: Compared[] = ids.map((id, index) => {
    const answer = answers[index];
    const detail = answer?.data;
    let missing: string | null = null;
    if (detail === undefined) {
      if (answer?.isError) {
        missing = answer.error instanceof ApiError && answer.error.status === 404 ? 'No such run' : 'Could not be loaded';
      } else {
        missing = 'Loading…';
      }
    }
    return { id, detail, missing };
  });
  const loaded = columns.flatMap((column) => (column.detail === undefined ? [] : [column.detail]));
  const warnings = comparisonWarnings(loaded.map((detail) => detail.run));

  return (
    <div className="compare">
      <section className="panel" aria-labelledby="compare-title">
        <div className="heading-row">
          <h1 id="compare-title">Compare bot runs</h1>
          <Link to={ids.length > 0 ? `/operator?compare=${ids.join(',')}` : '/operator'}>Back to bot runs</Link>
        </div>
        <p className="muted">
          Simulation-model evidence: what simulated bots did in this simulated market, such as how the share of market
          makers changes it. Runs in one simulation share the bots' accounts and the book, so a later run starts where an
          earlier one left them.
        </p>
        {dropped.length > 0 && (
          <p className="chart-note">
            Not run ids, so left out: {dropped.join(', ')}.
          </p>
        )}
        {overflow.length > 0 && (
          <p className="chart-note">
            At most four runs are compared; {groupInteger(overflow.length)} more left out.
          </p>
        )}
        {ids.length < 2 && (
          <p>
            Choose two to four finished runs in <Link to="/operator">Recent runs</Link> to compare them.
          </p>
        )}
        {warnings.map((warning) => (
          <p key={warning} className="chart-note">
            {warning}
          </p>
        ))}
      </section>
      {ids.length > 0 && (
        <>
          <Settings columns={columns} />
          <Figures columns={columns} />
        </>
      )}
    </div>
  );
}

/** The column headers: each run by its number and start, so the tables below read the same way. */
function RunHeaders({ columns, corner }: { columns: readonly Compared[]; corner: string }) {
  return (
    <thead>
      <tr>
        <th scope="col">
          <span className="visually-hidden">{corner}</span>
        </th>
        {columns.map((column, index) => (
          <th key={column.id} scope="col" className="compare-run">
            Run {index + 1}
            <span className="muted">
              {column.detail === undefined ? column.missing : `Started ${utcTime(column.detail.run.startedAt)}`}
            </span>
          </th>
        ))}
      </tr>
    </thead>
  );
}

/** Where the run stands for a comparison. */
function standing(column: Compared): string {
  if (column.detail === undefined) return column.missing ?? '';
  const { run } = column.detail;
  switch (comparability(run)) {
    case 'FINISHED':
      return 'Finished';
    case 'NOT_KEPT':
      return 'Not comparable: recorded before run windows were kept';
    default:
      return `Not finished yet: ${STATUS_LABELS[run.status]}`;
  }
}

function versionText(detail: BotRunDetail): string {
  const manifest = detail.run.manifest;
  if (manifest === null) return 'Not reported';
  return manifest.reports > 1 ? `${manifest.version} · changed during the run` : manifest.version;
}

type Row = { label: string; value: (detail: BotRunDetail) => ReactNode };

const SETTINGS: readonly Row[] = [
  { label: 'Bots', value: (detail) => strategiesText(detail.run.config.strategies) },
  { label: 'Market-maker share', value: (detail) => marketMakerShare(detail.run.config.strategies) },
  { label: 'Seed', value: (detail) => detail.run.config.seed },
  { label: 'Requested rate', value: (detail) => `${detail.run.config.commandsPerSecond} a second` },
  {
    label: 'Achieved rate',
    value: (detail) => {
      const rate = achievedRate(detail.run, detail.figures);
      return rate === null ? '—' : `${rate} a second`;
    },
  },
  { label: 'Ran for', value: (detail) => actualDuration(detail.run) },
  { label: 'Asked to run for', value: (detail) => plannedDuration(detail.run) },
  { label: 'Largest order', value: (detail) => `${groupInteger(detail.run.config.maxOrderQuantity)} shares` },
  { label: 'Largest position', value: (detail) => `${groupInteger(detail.run.config.maxPosition)} shares` },
  { label: 'Instruments', value: (detail) => detail.run.config.instruments.map((each) => each.symbol).join(', ') },
  { label: 'Simulator version', value: versionText },
  {
    label: 'Command sequences',
    value: ({ run }) => `${run.window.startSequence} to ${run.window.endSequence ?? 'not ended'}`,
  },
];

function Settings({ columns }: { columns: readonly Compared[] }) {
  return (
    <section className="panel" aria-labelledby="compare-settings-title">
      <h2 id="compare-settings-title">Settings</h2>
      <table aria-labelledby="compare-settings-title">
        <RunHeaders columns={columns} corner="Setting" />
        <tbody>
          <tr>
            <th scope="row">Comparable</th>
            {columns.map((column) => (
              <td key={column.id}>{standing(column)}</td>
            ))}
          </tr>
          {SETTINGS.map((row) => (
            <tr key={row.label}>
              <th scope="row">{row.label}</th>
              {columns.map((column) => (
                <td key={column.id}>{column.detail === undefined ? '—' : row.value(column.detail)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        The achieved rate counts the placements and cancels answered in the run's window, over how long it ran; a run
        the next one ended has no confirmed stop, so its length runs to its last report.
      </p>
    </section>
  );
}

/** What a column shows in place of a figure it doesn't have. */
function noFigures(column: Compared): string {
  if (column.detail === undefined) return '—';
  if (comparability(column.detail.run) !== 'FINISHED') return '—';
  return column.detail.figures === null ? 'Being worked out…' : '';
}

function mark({ price, source }: BotRunMark): string {
  return `${groupDigits(price)} (${source === 'LAST_TRADE' ? 'last trade' : 'reference'})`;
}

const INSTRUMENT_ROWS: readonly { label: string; value: (row: InstrumentFigures) => string; numeric: boolean }[] = [
  { label: 'Trades', value: (row) => groupInteger(row.trades), numeric: true },
  { label: 'Volume', value: (row) => `${groupInteger(row.volume)} shares`, numeric: true },
  { label: 'Notional', value: (row) => groupDigits(row.notional), numeric: true },
  { label: 'Price at the start', value: (row) => mark(row.startMark), numeric: true },
  { label: 'Price at the end', value: (row) => mark(row.endMark), numeric: true },
];

const STRATEGY_ROWS: readonly { label: string; value: (row: StrategyFigures) => string; numeric: boolean }[] = [
  { label: 'Bots', value: (row) => groupInteger(row.bots), numeric: true },
  { label: 'Placements', value: (row) => groupInteger(row.placements), numeric: true },
  { label: 'Cancels', value: (row) => groupInteger(row.cancels), numeric: true },
  { label: 'Refused', value: refusalsText, numeric: false },
  { label: 'Fill ratio', value: fillRatio, numeric: true },
  { label: 'Trades', value: (row) => groupInteger(row.trades), numeric: true },
  { label: 'Volume', value: (row) => `${groupInteger(row.volume)} shares`, numeric: true },
  { label: 'Notional', value: (row) => groupDigits(row.notional), numeric: true },
  { label: 'Cash change', value: (row) => groupDigits(row.cashChange), numeric: true },
  { label: 'Holdings at the start', value: (row) => groupDigits(row.holdingsStart), numeric: true },
  { label: 'Holdings at the end', value: (row) => groupDigits(row.holdingsEnd), numeric: true },
  { label: 'Estimated profit or loss', value: (row) => groupDigits(row.pnl), numeric: true },
];

function Figures({ columns }: { columns: readonly Compared[] }) {
  const instruments = new Map<string, string>();
  const strategies = new Set<string>();
  for (const column of columns) {
    for (const row of column.detail?.figures?.instruments ?? []) instruments.set(row.instrumentId, row.symbol);
    for (const row of column.detail?.figures?.strategies ?? []) strategies.add(row.strategy);
  }
  const bySymbol = [...instruments].sort((a, b) => a[1].localeCompare(b[1]));
  const span = columns.length + 1;
  return (
    <section className="panel" aria-labelledby="compare-figures-title">
      <h2 id="compare-figures-title">What happened</h2>
      <p className="muted">
        Worked out once each run finished, from its window. The market's figures count every trade in the run's
        instruments, people's too; a strategy's count its bots' side of each trade.
      </p>
      <h3 id="compare-markets-title">Markets</h3>
      <table aria-labelledby="compare-markets-title">
        <RunHeaders columns={columns} corner="Figure" />
        {bySymbol.length === 0 ? (
          <tbody>
            <tr>
              <td colSpan={span} className="muted">
                No figures yet.
              </td>
            </tr>
          </tbody>
        ) : (
          bySymbol.map(([instrumentId, symbol]) => (
            <tbody key={instrumentId}>
              <tr>
                <th scope="rowgroup" colSpan={span} className="compare-group">
                  {symbol}
                </th>
              </tr>
              {INSTRUMENT_ROWS.map((row) => (
                <tr key={row.label}>
                  <th scope="row">{row.label}</th>
                  {columns.map((column) => {
                    const figures = column.detail?.figures;
                    const found = figures?.instruments.find((each) => each.instrumentId === instrumentId);
                    return (
                      <td key={column.id} className={row.numeric && found !== undefined ? 'num' : undefined}>
                        {found !== undefined ? row.value(found) : figures ? 'Not in this run' : noFigures(column)}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          ))
        )}
      </table>
      <h3 id="compare-strategies-title">Strategies</h3>
      <table aria-labelledby="compare-strategies-title">
        <RunHeaders columns={columns} corner="Figure" />
        {strategies.size === 0 ? (
          <tbody>
            <tr>
              <td colSpan={span} className="muted">
                No figures yet.
              </td>
            </tr>
          </tbody>
        ) : (
          STRATEGIES.filter(([strategy]) => strategies.has(strategy)).map(([strategy, name]) => (
            <tbody key={strategy}>
              <tr>
                <th scope="rowgroup" colSpan={span} className="compare-group">
                  {name}
                </th>
              </tr>
              {STRATEGY_ROWS.map((row) => (
                <tr key={row.label}>
                  <th scope="row">{row.label}</th>
                  {columns.map((column) => {
                    const figures = column.detail?.figures;
                    const found = figures?.strategies.find((each) => each.strategy === strategy);
                    return (
                      <td key={column.id} className={row.numeric && found !== undefined ? 'num' : undefined}>
                        {found !== undefined ? row.value(found) : figures ? 'No bots' : noFigures(column)}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          ))
        )}
      </table>
    </section>
  );
}
