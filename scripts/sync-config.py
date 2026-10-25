#!/usr/bin/env python3
"""Merge new default config keys into the live plugin config.

Bukkit writes ``plugins/<Name>/config.yml`` once and never updates it, so a
plugin update can ship new defaults that a running server never picks up.
This helper adds the keys that are missing from the live config (existing
values are kept) and reports the values that differ from the defaults.

Usage: sync-config.py <default.yml> <live.yml>

Output lines:
    added <section>.<key>
    differs <section>.<key>: <live value> (default: <default value>)
"""

import re
import sys

TOP_RE = re.compile(r"^([A-Za-z0-9_-]+):")
NESTED_RE = re.compile(r"^\s+([A-Za-z0-9_-]+):(.*)$")


def parse(lines):
    """Return (section order, {section: {key: value}}, {section: {key: line}})."""
    order, values, raws = [], {}, {}
    section = None
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if TOP_RE.match(line):
            section = TOP_RE.match(line).group(1)
            if section not in values:
                values[section], raws[section] = {}, {}
                order.append(section)
            continue
        nested = NESTED_RE.match(line)
        if nested and section is not None:
            key = nested.group(1)
            values[section].setdefault(key, nested.group(2).strip())
            raws[section].setdefault(key, line)
    return order, values, raws


def strip_comment(value):
    """Drop an inline comment so only the real value is compared."""
    return value.split("#", 1)[0].strip()


def section_spans(lines):
    """Map each top-level section to its (start, end) line range."""
    starts = [(index, TOP_RE.match(line).group(1))
              for index, line in enumerate(lines) if TOP_RE.match(line)]
    spans = {}
    for position, (index, name) in enumerate(starts):
        end = starts[position + 1][0] if position + 1 < len(starts) else len(lines)
        spans[name] = (index, end)
    return spans


def insert_missing(lines, missing):
    """Insert the given raw lines at the end of their section."""
    grouped = {}
    for section, raw in missing:
        grouped.setdefault(section, []).append(raw)
    for section, raws in grouped.items():
        spans = section_spans(lines)
        if section in spans:
            _, end = spans[section]
            lines[end:end] = raws
        else:
            if lines and lines[-1].strip():
                lines.append("")
            lines.append(f"{section}:")
            lines.extend(raws)
    return lines


def main():
    if len(sys.argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    default_path, live_path = sys.argv[1], sys.argv[2]
    with open(default_path, encoding="utf-8") as handle:
        default_lines = handle.read().splitlines()
    with open(live_path, encoding="utf-8") as handle:
        live_lines = handle.read().splitlines()

    default_order, default_values, default_raws = parse(default_lines)
    _, live_values, _ = parse(live_lines)

    added, differs = [], []
    for section in default_order:
        for key, default_value in default_values[section].items():
            if key not in live_values.get(section, {}):
                added.append((section, key, default_raws[section][key]))
            else:
                live_value = live_values[section][key]
                if strip_comment(live_value) != strip_comment(default_value):
                    differs.append((section, key, live_value, default_value))

    if added:
        live_lines = insert_missing(live_lines, [(section, raw) for section, _, raw in added])
        with open(live_path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(live_lines) + "\n")

    for section, key, _ in added:
        print(f"added {section}.{key}")
    for section, key, live_value, default_value in differs:
        print(f"differs {section}.{key}: {live_value} (default: {default_value})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
