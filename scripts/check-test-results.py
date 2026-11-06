#!/usr/bin/env python3
"""A green Maven build with all database tests skipped is not evidence of ledger correctness."""
from pathlib import Path
import xml.etree.ElementTree as ET
import sys

files = list(Path(sys.argv[1] if len(sys.argv) > 1 else 'target/surefire-reports').glob('TEST-*.xml'))
if not files:
    raise SystemExit('No test reports found')
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in files:
    suite = ET.parse(path).getroot()
    for name in totals:
        totals[name] += int(suite.get(name, 0))
print(totals)
if not totals['tests'] or any(totals[k] for k in ['failures', 'errors', 'skipped']):
    raise SystemExit('Full verification requires zero failures, errors and skipped tests')
