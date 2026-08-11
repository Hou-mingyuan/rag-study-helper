/**
 * k6 smoke: dependency/read endpoints only (no chat/LLM).
 * k6 run loadtest/k6_smoke.js -e BASE_URL=http://127.0.0.1:19050 -e SPACE_ID=1
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = (__ENV.BASE_URL || 'http://127.0.0.1:19050').replace(/\/$/, '');
const SPACE_ID = __ENV.SPACE_ID || '1';

export const options = {
  vus: 2,
  duration: '20s',
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    'http_req_duration{endpoint:health}': ['p(95)<300'],
    'http_req_duration{endpoint:readiness}': ['p(95)<300'],
    'http_req_duration{endpoint:docs}': ['p(95)<300'],
  },
};

export default function () {
  const health = http.get(`${BASE}/api/health`, { tags: { endpoint: 'health' } });
  check(health, { 'health contract': (r) => r.status === 200 && r.json('resCode') === '200' });

  const readiness = http.get(`${BASE}/api/readiness`, { tags: { endpoint: 'readiness' } });
  check(readiness, { 'readiness contract': (r) => r.status === 200 && r.json('obj.status') === 'UP' });

  const docs = http.get(`${BASE}/api/spaces/${SPACE_ID}/documents`, { tags: { endpoint: 'docs' } });
  check(docs, { 'documents contract': (r) => r.status === 200 && r.json('resCode') === '200' });

  sleep(0.4);
}
