// Exercise payment recovery and reconciliation against a fault-injecting mock provider.
//
// Pay-ins through /payments while the mock PSP misbehaves (faults in application.yml, or change
// them live with PUT /mock-psp/faults). 20% of clients also resend the same request, like a
// mobile app on a bad network. Teardown waits for the resolver to finish PENDING payments, then
// runs reconciliation for today and prints what it found.
// Knobs: RATE (default 20/s), DURATION (default 30s), SETTLE_SECONDS (default 40).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, createAccount, key, pick } from './lib.js';

export const options = {
  scenarios: {
    payins: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 20),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
  setupTimeout: '2m',
  teardownTimeout: '3m',
};

export function setup() {
  const customers = [];
  for (let i = 0; i < 50; i++) customers.push(createAccount(`payin-${i}-${Date.now()}`));
  return { customers };
}

export default function (data) {
  const k = key('pay');
  const body = JSON.stringify({ accountId: pick(data.customers), amount: 1000 + Math.floor(Math.random() * 100000) });
  const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': k }, tags: { name: 'payment' } };

  const first = http.post(`${BASE}/payments`, body, params);
  check(first, { 'payment accepted (201 final / 202 pending)': (r) => r.status === 201 || r.status === 202 });

  if (Math.random() < 0.2) {
    const again = http.post(`${BASE}/payments`, body, params);
    check(again, { 'retry returns the same payment': (r) => r.json('id') === first.json('id') });
  }
}

export function teardown() {
  const wait = Number(__ENV.SETTLE_SECONDS || 40);
  console.log(`waiting ${wait}s for the resolver to finish PENDING payments...`);
  sleep(wait);
  const report = http.post(`${BASE}/reconciliation/runs`).json();
  const byKind = {};
  for (const m of report.mismatches) byKind[m.kind] = (byKind[m.kind] || 0) + 1;
  console.log(`reconciliation run ${report.runId}: matched=${report.matched} mismatches=${JSON.stringify(byKind)}`);
  console.log(`details: ${BASE}/reconciliation/runs/${report.runId}/mismatches.csv`);
}
