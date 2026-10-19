import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = { vus: 1, iterations: 1 };

export default function () {
  const key = `smoke-${Date.now()}`;
  const res = http.post(`${BASE_URL}/api/fulfillments`, JSON.stringify({
    orderId: `ORDER-${Date.now()}`,
    sku: 'SKU-001',
    quantity: 2,
  }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Correlation-ID': key },
  });

  check(res, {
    'status is success': (r) => [200, 201, 202].includes(r.status),
    'has requestId': (r) => r.json('requestId') !== undefined,
  });
}
