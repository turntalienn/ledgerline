// Compare fee revenue and conversion under simulated price-sensitive demand.
//
// Start the app with fees on:  ./mvnw spring-boot:run -Dspring-boot.run.arguments=--ledgerline.fees.enabled=true
// Each payer is assigned to one fee arm by the server (application.yml: ledgerline.fees.arms).
// Simulated demand: a customer sees the quote and goes ahead with probability
//     P(accept) = exp(-ELASTICITY * fee as % of amount)
// so cheap arms convert better and expensive arms earn more per transfer. Which arm earns the most
// in total is the experiment. Teardown prints GET /economics/fee-experiment for the run's window.
// Knobs: ELASTICITY (default 1.5), RATE (default 100/s), DURATION (default 60s), PAYERS (default 300).
import http from 'k6/http';
import { check } from 'k6';
import { BASE, createAccount, fundedAccounts, key, pick, transfer } from './lib.js';

const ELASTICITY = Number(__ENV.ELASTICITY || 1.5);

export const options = {
  scenarios: {
    shoppers: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  setupTimeout: '5m',
};

export function setup() {
  const payers = fundedAccounts('fee-payer', Number(__ENV.PAYERS || 300), 1000000000);
  const payees = [];
  for (let i = 0; i < 50; i++) payees.push(createAccount(`fee-payee-${i}-${Date.now()}`));
  return { payers, payees, from: new Date().toISOString() };
}

export default function (data) {
  const payer = pick(data.payers);
  // Rough long tail of ticket sizes: Rs 4 to Rs 1,600, mostly small.
  const amount = Math.round(Math.exp(6 + Math.random() * 6));
  const quote = http.get(`${BASE}/transfers/quote?fromAccountId=${payer}&amount=${amount}`, { tags: { name: 'quote' } }).json();
  const feePercent = (100 * quote.fee) / amount;
  if (Math.random() >= Math.exp(-ELASTICITY * feePercent)) return; // too expensive, customer walks away

  const res = transfer(payer, pick(data.payees), amount, key('fee'), { name: 'transfer', arm: quote.arm || 'none' });
  check(res, { 'transfer 201': (r) => r.status === 201 });
}

export function teardown(data) {
  const report = http.get(`${BASE}/economics/fee-experiment?from=${encodeURIComponent(data.from)}`).json();
  console.log(`fees enabled: ${report.enabled}`);
  for (const arm of report.arms) {
    console.log(`${arm.arm.padEnd(28)} quotes=${arm.quotes} transfers=${arm.transfers} conversion=${arm.conversion} ` +
      `volume=${arm.volume} revenue=${arm.feeRevenue} avgFeeBps=${arm.avgFeeBps.toFixed(1)}`);
  }
}
