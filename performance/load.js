import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '60s';

export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    http_req_failed: ['rate<0.20'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {
  const duplicateBucket = Math.floor(__ITER / 5);
  const key = __ITER % 5 === 0 ? `dup-${__VU}-${duplicateBucket}` : `unique-${__VU}-${__ITER}-${Date.now()}`;
  const fault = __ITER % 17 === 0 ? 'HTTP_500' : 'NORMAL';
  const res = http.post(`${BASE_URL}/api/fulfillments`, JSON.stringify({
    orderId: `ORDER-${__VU}-${__ITER}`,
    sku: 'SKU-001',
    quantity: 1,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
      'X-Correlation-ID': `load-${__VU}-${__ITER}`,
      'X-Partner-Fault': fault,
    },
  });

  check(res, { 'known status': (r) => [200, 201, 202, 409, 429, 502, 503].includes(r.status) });
  sleep(1);
}
