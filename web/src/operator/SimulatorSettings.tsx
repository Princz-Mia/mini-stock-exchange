import type { ReactNode } from 'react';

import { type ManifestColumn, settingGroups } from './manifest';

/**
 * The settings each run's simulator reported, one column per run, grouped: version and runtime, each strategy, the
 * defaults. A setting the runs disagree on says so in words, not only in colour.
 */
export function SimulatorSettings({
  labelledBy,
  head,
  manifests,
}: {
  labelledBy: string;
  head: ReactNode;
  manifests: readonly ManifestColumn[];
}) {
  const span = manifests.length + 1;
  return (
    <table aria-labelledby={labelledBy}>
      {head}
      {settingGroups(manifests).map((group) => (
        <tbody key={group.key}>
          <tr>
            <th scope="rowgroup" colSpan={span} className="compare-group">
              {group.label}
            </th>
          </tr>
          {group.rows.map((row) => (
            <tr key={row.name} className={row.differs ? 'differs' : undefined}>
              <th scope="row">
                {/* Screen readers don't announce a rowgroup header: the group goes in each row's name. */}
                <span className="visually-hidden">{group.label}:</span> {row.name}
                {/* A space between, so the name and the mark are read as two words. */}
                {row.differs && <> <span className="setting-differs">Differs</span></>}
              </th>
              {row.values.map((value, index) => (
                <td key={index}>{value}</td>
              ))}
            </tr>
          ))}
        </tbody>
      ))}
    </table>
  );
}
