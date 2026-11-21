"""The simulator each measured run ran on, from the manifest its worker reported with the run: its
version, the runtime its decisions depend on, and its strategies' settings. A report then names the
code and settings it measured, and says when its runs did not all run the same ones."""

import json
import re
from typing import Any

# A run's manifest as read back: the manifest, None when no worker reported one, or why it could
# not be read.
Reading = dict[str, Any] | None | str

STRATEGIES = (("MARKET_MAKER", "Market makers"), ("NOISE", "Noise"), ("MOMENTUM", "Momentum"))

# A commit as the report reads it; anything else means it could not be read.
COMMIT = re.compile(r"[0-9a-f]{7,40}")


def revision_label(commit: str, changes: str) -> str:
    """The revision the simulator image is built with, as the compatibility gate writes it: the
    commit, and ``.dirty`` when tracked files have uncommitted changes (``changes`` is ``git status
    --porcelain --untracked-files=no``). Empty without a commit, so the worker reports its version
    as unknown rather than a wrong one."""
    if not commit:
        return ""
    return commit + (".dirty" if changes.strip() else "")


def _text(value: Any) -> str:
    return value if isinstance(value, str) else json.dumps(value)


def _same(a: Any, b: Any) -> bool:
    # As JSON values: 10 and 10.0 are the same number, but true is not 1.
    return isinstance(a, bool) == isinstance(b, bool) and a == b


def _flat(manifest: dict[str, Any]) -> dict[str, Any]:
    """What makes two manifests differ, by dotted name. Who reported it and when don't."""
    flat: dict[str, Any] = {"version": manifest.get("version")}
    for name, value in (manifest.get("runtime") or {}).items():
        flat[f"runtime.{name}"] = value
    for strategy, settings in (manifest.get("strategies") or {}).items():
        for name, value in (settings or {}).items():
            flat[f"{strategy}.{name}"] = value
    for name, value in (manifest.get("defaults") or {}).items():
        flat[f"defaults.{name}"] = value
    return flat


def differences(first: dict[str, Any], other: dict[str, Any]) -> list[str]:
    """How ``other`` differs from ``first``, one item per setting, in name order."""
    a, b = _flat(first), _flat(other)
    found = []
    for name in sorted(a.keys() | b.keys()):
        if name not in b:
            found.append(f"{name} absent (was {_text(a[name])})")
        elif name not in a:
            found.append(f"{name} {_text(b[name])} (new)")
        elif not _same(a[name], b[name]):
            found.append(f"{name} {_text(b[name])} (was {_text(a[name])})")
    return found


def _settings(settings: dict[str, Any] | None) -> str:
    if not settings:
        return "none reported"
    return ", ".join(f"{name} {_text(settings[name])}" for name in sorted(settings))


def _commit_line(version: str, commit: str) -> str | None:
    """Whether the worker's version names the commit the report measured. The report's commit
    counts untracked files as changes and the image's doesn't, so only the commit is compared."""
    label = version.partition("+")[2].removesuffix(".dirty")
    sha = commit.split(" ")[0]
    if not COMMIT.fullmatch(sha):
        # The report could not read its own commit: there is nothing to compare with.
        return None
    if label and sha.startswith(label):
        return None
    return f"- The worker's version does not name this report's commit ({commit})."


def report(readings: list[tuple[str, str, Reading]], commit: str) -> list[str]:
    """The report's Simulator section: for each run (its label, such as its rate, and its id) what
    its worker reported, the first manifest in full and how any other differs from it."""
    lines = ["## Simulator", ""]
    reported = [reading for _, _, reading in readings if isinstance(reading, dict)]
    if not reported:
        lines += [
            "No run's worker reported its manifest, so this report cannot name the simulator's "
            "code or settings.",
            "",
        ]
    else:
        first = reported[0]
        alike = all(
            not differences(first, each) and each.get("reports", 1) == 1 for each in reported
        )
        if not alike:
            lines.append(
                "**The runs did not all run the same simulator code and settings, so their "
                "figures are not directly comparable.** The first reported:"
            )
        elif len(reported) < len(readings):
            lines.append(
                f"{len(reported)} of {len(readings)} runs reported, all the same version and "
                "settings; what the others ran is not known:"
            )
        elif len(readings) == 1:
            lines.append("The run's worker reported:")
        else:
            lines.append("Every run's worker reported the same version and settings:")
        lines.append("")
        lines.append(f"- Version: {first.get('version')}.")
        runtime = first.get("runtime") or {}
        lines.append(f"- Runtime: {_settings(runtime)}.")
        strategies = first.get("strategies") or {}
        known = [key for key, _ in STRATEGIES]
        others = [(key, key) for key in sorted(strategies) if key not in known]
        for key, name in [*STRATEGIES, *others]:
            if key in strategies:
                lines.append(f"- {name}: {_settings(strategies[key])}.")
        lines.append(f"- Defaults: {_settings(first.get('defaults'))}.")
        check = _commit_line(str(first.get("version", "")), commit)
        if check is not None:
            lines.append(check)
        lines.append("")
    lines += ["| Run | Id | Simulator |", "| --- | --- | --- |"]
    first = reported[0] if reported else None
    for label, run_id, reading in readings:
        cells = (label, run_id, _cell(reading, first))
        lines.append("| " + " | ".join(_one_cell(cell) for cell in cells) + " |")
    lines.append("")
    return lines


def _cell(reading: Reading, first: dict[str, Any] | None) -> str:
    if reading is None:
        return "not reported"
    if isinstance(reading, str):
        return f"not read ({reading})"
    notes = []
    if first is not None and reading is not first:
        changed = differences(first, reading)
        if changed:
            notes.append("differs: " + "; ".join(changed))
    reports = reading.get("reports", 1)
    if reports > 1:
        notes.append(f"changed during the run: {reports} different reports, the first shown")
    return ", ".join(notes) if notes else "as above"


def _one_cell(text: str) -> str:
    """Text as one Markdown table cell: on one line, with its bars escaped."""
    return " ".join(text.split()).replace("|", "\\|")
