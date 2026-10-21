import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'baseline';
const SETTINGS = {
  baseline: { vus: 5, duration: '60s', failThreshold: 'rate<0.02', faultRate: 0 },
  overload: { vus: 20, duration: '60s', failThreshold: 'rate<0.80', faultRate: 0 },
  fault: { vus: 5, duration: '60s', failThreshold: 'rate<0.25', faultRate: 0.1 },
};
const settings = SETTINGS[PROFILE];
if (!settings) throw new Error(`Unknown PROFILE '${PROFILE}'. Use baseline, overload, or fault.`);
const rateLimited = new Rate('gateway_rate_limited');

export const options = {
  vus: Number(__ENV.VUS || settings.vus),
  duration: __ENV.DURATION || settings.duration,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: [settings.failThreshold],
    http_req_duration: ['p(95)<5000'],
    gateway_rate_limited: PROFILE === 'baseline' ? ['rate<0.05'] : ['rate<1'],
  },
};

export default function () {
  const duplicateGroup = Math.floor(__ITER / 5);
  const isDuplicatePair = __ITER % 5 < 2;
  const key = isDuplicatePair
    ? `load-duplicate-${__VU}-${duplicateGroup}`
    : `load-unique-${__VU}-${__ITER}-${Date.now()}`;
  const orderId = isDuplicatePair
    ? `ORDER-DUP-${__VU}-${duplicateGroup}`
    : `ORDER-${__VU}-${__ITER}`;
  const injectFault = PROFILE === 'fault' && Math.random() < settings.faultRate;
  const fault = injectFault ? 'HTTP_500' : 'NORMAL';
  const res = http.post(`${BASE_URL}/api/fulfillments`, JSON.stringify({
    orderId,
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

  rateLimited.add(res.status === 429);
  check(res, { 'known status': (r) => [200, 201, 202, 409, 429, 502, 503].includes(r.status) });
  sleep(1);
}
