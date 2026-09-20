import http from 'k6/http';
import { check } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

let seq = 0;
/** Unique per request across VUs and runs. (__VU/__ITER are not defined in setup(), so not used here.) */
export function key(prefix) {
  seq++;
  return `${prefix}-${Date.now()}-${seq}-${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;
}

export function createAccount(name, type) {
  const res = http.post(`${BASE}/accounts`, JSON.stringify({ name, currency: 'INR', type: type || 'USER' }),
    { headers: JSON_HEADERS, tags: { name: 'setup' } });
  check(res, { 'account created': (r) => r.status === 201 });
  return res.json('id');
}

export function transfer(from, to, amount, idempotencyKey, tags) {
  return http.post(`${BASE}/transfers`, JSON.stringify({ fromAccountId: from, toAccountId: to, amount }),
    { headers: Object.assign({ 'Idempotency-Key': idempotencyKey }, JSON_HEADERS), tags });
}

/** n USER accounts, each paid `amount` paise by a fresh SYSTEM faucet (which may go negative). */
export function fundedAccounts(prefix, n, amount) {
  const stamp = Date.now();
  const faucet = createAccount(`faucet-${prefix}-${stamp}`, 'SYSTEM');
  const ids = [];
  for (let i = 0; i < n; i++) {
    const id = createAccount(`${prefix}-${i}-${stamp}`);
    check(transfer(faucet, id, amount, key('fund'), { name: 'setup' }), { funded: (r) => r.status === 201 });
    ids.push(id);
  }
  return ids;
}

export function getAccount(id) {
  return http.get(`${BASE}/accounts/${id}`, { tags: { name: 'setup' } }).json();
}

export function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}
