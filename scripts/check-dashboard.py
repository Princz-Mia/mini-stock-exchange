"""Checks the Grafana dashboard against a running stack: Grafana loaded it, and every query works.

Every panel query must be valid PromQL that Prometheus accepts, and must return data once the stack
has traded for a minute; together the queries get two minutes to start. A misspelt metric name is
valid PromQL with no data, so this is what catches it. The only exception is a counter that exists
only once something has happened, such as a refusal. Standard library only, so the compatibility
gate can run it with any Python.

    python scripts/check-dashboard.py http://localhost:9090 http://localhost:3000

The Grafana admin password comes from the GRAFANA_ADMIN_PASSWORD environment variable.
"""

import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

DASHBOARD = Path(__file__).resolve().parent.parent / 'infra' / 'grafana' / 'dashboards' / 'mini-stock-exchange.json'
# Counters registered only when the first event happens: a healthy run may have none.
MAY_BE_EMPTY = ('mse_commands_refused_total',)


def get(url: str, headers: dict[str, str] | None = None) -> tuple[int, dict]:
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b'{}')


def queries(dashboard: dict) -> list[tuple[str, str]]:
    return [(panel['title'], target['expr']) for panel in dashboard['panels'] if panel.get('type') != 'row'
            for target in panel.get('targets', [])]


def main(prometheus: str, grafana: str) -> int:
    dashboard = json.loads(DASHBOARD.read_text(encoding='utf-8'))
    password = os.environ.get('GRAFANA_ADMIN_PASSWORD', '')
    auth = 'Basic ' + base64.b64encode(f'admin:{password}'.encode()).decode()
    failures = []

    status, loaded = get(f'{grafana}/api/dashboards/uid/{dashboard["uid"]}', {'Authorization': auth})
    if status != 200:
        failures.append(f'Grafana has no dashboard {dashboard["uid"]}: HTTP {status}')
    elif len(loaded['dashboard']['panels']) != len(dashboard['panels']):
        failures.append('Grafana loaded a different dashboard than the file')
    else:
        print(f'Grafana loaded "{loaded["dashboard"]["title"]}" in folder "{loaded["meta"].get("folderTitle")}"')

    deadline = time.monotonic() + 120
    for title, expr in queries(dashboard):
        must = not any(name in expr for name in MAY_BE_EMPTY)
        while True:
            status, answer = get(f'{prometheus}/api/v1/query?' + urllib.parse.urlencode({'query': expr}))
            series = len(answer.get('data', {}).get('result', [])) if status == 200 else 0
            if status != 200 or not must or series > 0 or time.monotonic() > deadline:
                break
            time.sleep(5)
        if status != 200:
            failures.append(f'{title}: Prometheus refused the query ({answer.get("error", status)}): {expr}')
        elif must and series == 0:
            failures.append(f'{title}: no data for {expr}')
        else:
            print(f'{series:3d} series  {title}: {expr[:90]}')
    for failure in failures:
        print(f'FAIL {failure}')
    return 1 if failures else 0


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1].rstrip('/'), sys.argv[2].rstrip('/')))
