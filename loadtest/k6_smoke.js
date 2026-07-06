/**
 * k6 smoke: GET /api/health + /api/documents (no chat/LLM)
 * k6 run loadtest/k6_smoke.js -e BASE_URL=http://localhost:18086
 */
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');

export const options = {
  vus: 2,
  duration: '20s',
  thresholds: {
    http_req_failed: ['rate<0.02'],
    'http_req_duration{endpoint:health}': ['p(95)<500'],
    'http_req_duration{endpoint:docs}': ['p(95)<1200'],
  },
};

export default function () {
  const health = http.get(`${BASE}/api/health`, { tags: { endpoint: 'health' } });
  check(health, { 'health 200': (r) => r.status === 200 });

  const docs = http.get(`${BASE}/api/documents`, { tags: { endpoint: 'docs' } });
  check(docs, { 'docs 200': (r) => r.status === 200 });

  sleep(0.4);
}
