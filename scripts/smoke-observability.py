#!/usr/bin/env python3
"""Exercise the live lab and prove metrics, a trace and the Kafka projection are observable."""
import json
from pathlib import Path
import time
from urllib.request import Request, urlopen
from urllib.parse import urlencode
import uuid

BASE = 'http://localhost:8080'

def call(url, data=None, headers=None, method=None):
    body = None if data is None else json.dumps(data).encode()
    request = Request(url, data=body, headers={'Content-Type': 'application/json', **(headers or {})}, method=method)
    with urlopen(request, timeout=10) as response:
        return json.load(response)

def eventually(check, timeout=45):
    deadline = time.monotonic() + timeout
    error = None
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except Exception as exc:
            error = exc
        time.sleep(1)
    raise AssertionError(f'Condition not met: {error}')

faucet = call(BASE+'/accounts', {'name': 'telemetry-faucet-'+uuid.uuid4().hex, 'currency': 'INR', 'type': 'SYSTEM'})['id']
customer = call(BASE+'/accounts', {'name': 'telemetry-user-'+uuid.uuid4().hex, 'currency': 'INR'})['id']
# A known traceparent lets us request THIS trace, rather than accepting unrelated old traces.
trace_id = uuid.uuid4().hex
parent = f'00-{trace_id}-{uuid.uuid4().hex[:16]}-01'
for n in range(60):
    call(BASE+'/transfers', {'fromAccountId': faucet, 'toAccountId': customer, 'amount': 100},
         {'Idempotency-Key': uuid.uuid4().hex, 'traceparent': parent})
    time.sleep(.25)
projection = eventually(lambda: (v if (v := call(BASE+f'/accounts/{customer}/projection'))['balance'] == 6000 else None))
def healthy_target():
    result = call('http://localhost:9090/api/v1/targets')
    return any(t['health'] == 'up' and t['labels'].get('job') == 'ledgerline'
               for t in result['data']['activeTargets'])

eventually(healthy_target)
query = urlencode({'query': 'http_server_requests_seconds_count{uri="/transfers",status="201"}'})
metric = eventually(lambda: (v if (v := call('http://localhost:9090/api/v1/query?'+query))['data']['result'] else None))
trace = eventually(lambda: call('http://localhost:3200/api/traces/'+trace_id, headers={'Accept': 'application/json'}))
# Tempo's JSON encodes the resource batches under either batches or resourceSpans across versions.
assert trace.get('batches') or trace.get('resourceSpans'), 'Trace response contains no spans'
assert 'ledgerline.transaction' in json.dumps(trace), 'Transaction span is missing from exported trace'
dashboard = call('http://localhost:3000/api/dashboards/uid/ledgerline-lab')
assert len(dashboard['dashboard']['panels']) == 10
Path('artifacts/observability').mkdir(parents=True, exist_ok=True)
Path('artifacts/observability/smoke.json').write_text(json.dumps({'projection': projection, 'metric': metric,
    'traceId': trace_id, 'trace': trace, 'dashboardTitle': dashboard['dashboard']['title']}, indent=2))
print('PASS: Kafka projection, Prometheus scrape/request metric, Tempo trace and 10 provisioned panels')
print('Trace ID:', trace_id)
