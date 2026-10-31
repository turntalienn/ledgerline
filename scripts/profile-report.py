#!/usr/bin/env python3
"""Make reviewable JFR tables and an execution-sample flame graph using only the JDK/Python."""
from collections import Counter
import hashlib
from html import escape
import json
from pathlib import Path
import subprocess
import sys

recording, output = Path(sys.argv[1]).resolve(), Path(sys.argv[2])
output.mkdir(parents=True, exist_ok=True)
# JFR views distinguish socket/lock waits from execution samples. A flame graph alone would
# misleadingly imply that request latency is all CPU work; database contention is often waiting.
for view in ['hot-methods', 'socket-reads-by-host', 'contention-by-site', 'gc', 'allocation-by-class']:
    result = subprocess.run(['jfr', 'view', '--width', '150', view, str(recording)],
                            capture_output=True, text=True, check=True)
    (output / (view+'.txt')).write_text(result.stdout)
raw = subprocess.run(['jfr', 'print', '--json', '--stack-depth', '64', '--events',
                      'jdk.ExecutionSample', str(recording)], capture_output=True, text=True, check=True)
events = json.loads(raw.stdout)['recording']['events']
root = {'name': 'all execution samples', 'count': 0, 'children': {}}
leaves = Counter()
for event in events:
    frames = (event['values'].get('stackTrace') or {}).get('frames', [])
    if not frames:
        continue
    names = [f[ 'method']['type']['name'].replace('/', '.')+'.'+f['method']['name'] for f in frames]
    leaves[names[0]] += 1
    node = root
    node['count'] += 1
    # JFR lists leaf first. Draw callers at the bottom and their callees above them.
    for name in reversed(names):
        node = node['children'].setdefault(name, {'name': name, 'count': 0, 'children': {}})
        node['count'] += 1
if not root['count']:
    raise SystemExit('No execution samples: cannot draw an evidence-based flame graph')

def depth(node):
    return 1+max((depth(c) for c in node['children'].values()), default=0)

width, row = 1400, 20
height = depth(root)*row+85
parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
         '<style>text{font-family:monospace;font-size:11px}rect:hover{stroke:#111;stroke-width:1.5}</style>',
         f'<rect width="{width}" height="{height}" fill="#faf9f6"/>',
         '<text x="10" y="22" style="font-size:16px">Ledgerline JFR execution samples</text>',
         f'<text x="10" y="43">{root["count"]} samples; rectangle width = sample count, NOT elapsed time. Socket/lock waits excluded.</text>']

def draw(node, x, w, level):
    y = height-25-level*row
    hue = 15+int(hashlib.sha256(node['name'].encode()).hexdigest()[:4], 16)%40
    parts.append(f'<g><title>{escape(node["name"])}: {node["count"]} samples ({100*node["count"]/root["count"]:.2f}%)</title>'
                 f'<rect x="{x:.2f}" y="{y}" width="{max(w-.5,0):.2f}" height="19" fill="hsl({hue},80%,68%)"/>')
    if w > 45:
        label = node['name'][:max(1, int(w/7)-2)]
        parts.append(f'<text x="{x+3:.2f}" y="{y+14}">{escape(label)}</text>')
    parts.append('</g>')
    offset = x
    for child in sorted(node['children'].values(), key=lambda n:n['name']):
        cw = w*child['count']/node['count']
        if cw >= .5:
            draw(child, offset, cw, level+1)
        offset += cw

draw(root, 5, width-10, 0)
parts.append('</svg>')
(output/'execution-samples.svg').write_text('\n'.join(parts))
(output/'sample-summary.json').write_text(json.dumps({'samples':root['count'], 'topLeafMethods':leaves.most_common(15)}, indent=2))
print(output)
