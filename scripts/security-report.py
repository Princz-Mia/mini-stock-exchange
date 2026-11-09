"""Summarises the compatibility gate's security scans and decides whether they pass.

Reads the reports the gate writes into one directory:
- gitleaks.json: secrets found in the git history;
- trivy-repo.json: the npm and uv lockfiles, and the Dockerfiles and Compose file;
- image-own-*.json: the images this repository builds;
- image-third-party-*.json: the images Compose pulls.

It fails on what this repository can fix itself:
- any secret in the history;
- a HIGH or CRITICAL vulnerability with a fixed version, in a library (Java, npm or Python) in a lockfile or in one
  of the images it builds;
- a HIGH or CRITICAL misconfiguration in a Dockerfile or the Compose file.

The rest is reported, not failed: an operating system package in a base image is fixed by the base image's next
build, and a third-party image by its next release. Those lines are the report to read. Writes summary.md beside
the reports. Standard library only, so the gate can run it with any Python.

    python scripts/security-report.py runtime/compat-gate/security
"""

import collections
import glob
import json
import os
import sys

SERIOUS = ('CRITICAL', 'HIGH')
ORDER = ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')


def load(path):
    with open(path, encoding='utf-8') as file:
        return json.load(file)


def scan(path):
    """Counts by severity, and the serious findings, each tagged as fixable here or not."""
    report = load(path)
    counts = collections.Counter()
    serious = []
    for result in report.get('Results') or []:
        library = result.get('Class') == 'lang-pkgs'
        for vulnerability in result.get('Vulnerabilities') or []:
            severity = vulnerability['Severity']
            counts[severity] += 1
            if severity in SERIOUS:
                fixed = vulnerability.get('FixedVersion') or ''
                serious.append({
                    'kind': 'library' if library else 'os',
                    'what': f"{vulnerability['PkgName']} {vulnerability['InstalledVersion']}",
                    'fix': fixed,
                    'id': vulnerability['VulnerabilityID'],
                    'where': result.get('Target', ''),
                })
        for misconfiguration in result.get('Misconfigurations') or []:
            if misconfiguration.get('Status') == 'FAIL' and misconfiguration['Severity'] in SERIOUS:
                serious.append({
                    'kind': 'config',
                    'what': misconfiguration['Title'],
                    'fix': misconfiguration.get('Resolution', ''),
                    'id': misconfiguration['ID'],
                    'where': result.get('Target', ''),
                })
    return counts, serious


def main(directory):
    lines = ['# Security scan', '']
    failures = []

    leaks = load(os.path.join(directory, 'gitleaks.json'))
    lines.append(f'Secrets in the git history: {len(leaks)}')
    for leak in leaks:
        failures.append(f"secret ({leak['RuleID']}) in {leak['File']}:{leak['StartLine']} at {leak['Commit'][:12]}")

    lines += ['', '| Scanned | Critical | High | Medium | Low | Must fix here |', '| --- | --- | --- | --- | --- | --- |']
    listed = []
    reports = [os.path.join(directory, 'trivy-repo.json')]
    reports += sorted(glob.glob(os.path.join(directory, 'image-own-*.json')))
    reports += sorted(glob.glob(os.path.join(directory, 'image-third-party-*.json')))
    for path in reports:
        name = os.path.basename(path)[:-len('.json')]
        own = not name.startswith('image-third-party-')
        counts, serious = scan(path)
        must = [f for f in serious if own and (f['kind'] == 'config' or (f['kind'] == 'library' and f['fix']))]
        failures += [f"{name}: {f['id']} {f['what']} (fix: {f['fix'] or 'see report'}) in {f['where']}" for f in must]
        listed += [(name, f) for f in serious if f not in must]
        lines.append(f"| {name} | " + ' | '.join(str(counts[s]) for s in ORDER[:4]) + f' | {len(must)} |')

    lines += ['', 'Listed, not failed (base image or third-party image; serious only):', '']
    grouped = collections.defaultdict(set)
    for name, finding in listed:
        grouped[(name, finding['what'], finding['fix'] or 'no fix yet')].add(finding['id'])
    for (name, what, fix), ids in sorted(grouped.items()):
        lines.append(f"- {name}: {what} -> {fix} ({len(ids)}: {', '.join(sorted(ids)[:3])})")

    lines += ['', f'Must fix here: {len(failures)}']
    lines += [f'- {failure}' for failure in failures]
    with open(os.path.join(directory, 'summary.md'), 'w', encoding='utf-8') as file:
        file.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
