// Compare balance strategies with many payers transferring to one merchant.
//
// Many payers -> ONE merchant. Run it once per strategy, restarting the app in between:
//   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--ledgerline.balance.strategy=row-lock
//   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--ledgerline.balance.strategy=optimistic
//   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--ledgerline.balance.strategy=sharded
// and compare: transfer p95/p99, http_req_failed (409 = gave up under contention), transfers_conflict.
// Knobs: RATE (req/s, default 200), DURATION (default 30s), PAYERS (default 200).
import { check } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { createAccount, fundedAccounts, getAccount, key, pick, transfer } from './lib.js';

const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '30s';
const PAYERS = Number(__ENV.PAYERS || 200);

const accepted = new Counter('transfers_accepted');
const conflicts = new Counter('transfers_conflict');
const latency = new Trend('transfer_latency', true);
const conflictRate = new Rate('transfer_conflict_rate');
const unexpected = new Rate('transfer_unexpected_rate');
const durationMatch = /^(\d+(?:\.\d+)?)(s|m|h)$/.exec(DURATION);
if (!durationMatch || Number(durationMatch[1]) <= 0) throw new Error('DURATION must be positive seconds, minutes or hours');
const seconds = Number(durationMatch[1]) * ({ s: 1, m: 60, h: 3600 }[durationMatch[2]]);

export const options = {
  scenarios: {
    hot_account: {
      // Open model: requests arrive at RATE per second whether or not earlier ones finished.
      // A closed model (N VUs in a loop) slows its own arrival rate when the server slows down,
      // which hides exactly the tail latency we are trying to measure (coordinated omission).
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
    transfer_unexpected_rate: ['rate==0'],
    'http_req_failed{name:transfer}': ['rate<0.01'],
    'http_req_duration{name:transfer}': ['p(99)<1000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '5m',
};

export function setup() {
  const merchant = createAccount(`merchant-${Date.now()}`);
  const payers = fundedAccounts('payer', PAYERS, 100000000);
  return { merchant, payers };
}

export default function (data) {
  const res = transfer(pick(data.payers), data.merchant, 100, key('hot'), { name: 'transfer' });
  latency.add(res.timings.duration);
  conflictRate.add(res.status === 409);
  unexpected.add(res.status !== 201 && res.status !== 409);
  if (res.status === 201) accepted.add(1);
  if (res.status === 409) conflicts.add(1);
  check(res, { 'transfer 201': (r) => r.status === 201 });
}

export function teardown(data) {
  const m = getAccount(data.merchant);
  console.log(`merchant ${data.merchant}: balance=${m.balance} journalBalance=${m.journalBalance} ` +
    `(equal, and = 100 x transfers_accepted)`);
  check(m, { 'cached balance equals journal': (a) => a.balance === a.journalBalance });
}

// handleSummary runs once, after teardown; stdout survives container removal.
export function handleSummary(data) {
  const values = (name) => data.metrics[name] ? data.metrics[name].values : {};
  const result = {
    offeredRate: RATE, durationSeconds: seconds, payers: PAYERS,
    accepted: values('transfers_accepted').count || 0,
    acceptedTps: (values('transfers_accepted').count || 0) / seconds,
    p95: values('transfer_latency')['p(95)'] || 0,
    p99: values('transfer_latency')['p(99)'] || 0,
    conflictRate: values('transfer_conflict_rate').rate || 0,
    dropped: values('dropped_iterations').count || 0,
    checksFailed: values('checks').fails || 0,
    metrics: data.metrics,
  };
  return { stdout: 'LEDGERLINE_RESULT=' + JSON.stringify(result) + '\n' };
}
