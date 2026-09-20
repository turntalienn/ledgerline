// Send concurrent copies of each request to verify idempotent transfers.
//
// Every iteration fires the SAME transfer (same Idempotency-Key, same body) 5 times in parallel,
// like a client that retries aggressively on a flaky network. Exactly one may apply; the other four
// must replay the same journal entry. At the end, merchant balance must equal the iteration count.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, createAccount, fundedAccounts, getAccount, key } from './lib.js';

export const options = {
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '20s',
  thresholds: { checks: ['rate==1.0'] },
};

export function setup() {
  const [payer] = fundedAccounts('storm', 1, 1000000000000);
  return { payer, merchant: createAccount(`storm-merchant-${Date.now()}`) };
}

export default function (data) {
  const body = JSON.stringify({ fromAccountId: data.payer, toAccountId: data.merchant, amount: 1 });
  const params = { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key('storm') }, tags: { name: 'transfer' } };
  const responses = http.batch(Array.from({ length: 5 }, () => ['POST', `${BASE}/transfers`, body, params]));

  const entries = new Set(responses.map((r) => r.status === 201 ? r.json('journalEntryId') : null));
  const replays = responses.filter((r) => r.headers['Idempotent-Replayed'] === 'true').length;
  check(responses, {
    'all five answered 201': (rs) => rs.every((r) => r.status === 201),
    'one journal entry per key': () => entries.size === 1 && !entries.has(null),
    'exactly one original, four replays': () => replays === 4,
  });
}

export function teardown(data) {
  const m = getAccount(data.merchant);
  console.log(`merchant balance=${m.balance} journalBalance=${m.journalBalance}: must equal the 'iterations' count below`);
}
