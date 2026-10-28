#!/usr/bin/env python3
"""Extract the single machine-readable k6 summary and render comparison rows."""
import json
import re
from pathlib import Path
import sys

if sys.argv[1] == '--table':
    root = Path(sys.argv[2])
    print('| Strategy | Offered req/s | Accepted TPS* | p95 ms | p99 ms | 409 % | Dropped | Checks failed | k6 exit |')
    print('|---|---:|---:|---:|---:|---:|---:|---:|---:|')
    for path in sorted(root.glob('*/result.json')):
        r = json.loads(path.read_text())
        print(f"| {r['strategy']} | {r['offeredRate']} | {r['acceptedTps']:.2f} | "
              f"{r['p95']:.2f} | {r['p99']:.2f} | {100*r['conflictRate']:.2f} | "
              f"{r['dropped']} | {r['checksFailed']} | {path.with_name('exit-code.txt').read_text().strip()} |")
    print('\n*Accepted requests divided by configured arrival-window seconds; includes requests completing during graceful stop.')
else:
    marker = 'LEDGERLINE_RESULT='
    lines = [line.split(marker, 1)[1] for line in Path(sys.argv[1]).read_text().splitlines() if line.startswith(marker)]
    if len(lines) != 1:
        raise SystemExit('Expected exactly one benchmark summary; inspect k6.log')
    result = json.loads(lines[0])
    result['strategy'] = sys.argv[3]
    balances = re.findall(r'merchant \d+: balance=(-?\d+) journalBalance=(-?\d+)', Path(sys.argv[1]).read_text())
    if len(balances) != 1:
        raise SystemExit('Missing or ambiguous merchant balance evidence')
    cached, journal = map(int, balances[0])
    result['merchantBalance'] = cached
    result['merchantJournalBalance'] = journal
    result['balanceMatchesAccepted'] = cached == journal == result['accepted'] * 100
    Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
    if not result['balanceMatchesAccepted']:
        raise SystemExit('Merchant balance does not match accepted transfers; benchmark invalid')
