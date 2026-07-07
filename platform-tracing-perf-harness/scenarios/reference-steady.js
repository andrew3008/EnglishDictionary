import http from 'k6/http';
import { check } from 'k6';

/**
 * REFERENCE tier — open-model constant-arrival-rate load (W-004 evidence).
 *
 * Required env:
 *   BASE_URL, TARGET_RPS, WARMUP_DURATION, DURATION, ENDPOINT
 *
 * S0/S1/S4 must use identical TARGET_RPS (pin before E2; calibrate to ~60–70% of S0 saturation).
 * Record in reference-summary.json:
 *   metrics.load.achievedRpsAvg, achievedRpsPct, droppedIterations
 * dropped_iterations > 0 or achievedRpsPct < 0.95 => non-authoritative evidence.
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '100', 10);
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '2m';
const DURATION = __ENV.DURATION || '10m';
const ENDPOINT = __ENV.ENDPOINT || '/perf/work';

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            exec: 'referenceTraffic',
            rate: TARGET_RPS,
            timeUnit: '1s',
            duration: WARMUP_DURATION,
            preAllocatedVUs: Math.max(50, Math.ceil(TARGET_RPS / 4)),
            maxVUs: Math.max(100, Math.ceil(TARGET_RPS * 1.2)),
            tags: { tier: 'REFERENCE', phase: 'warmup' },
        },
        steady: {
            executor: 'constant-arrival-rate',
            exec: 'referenceTraffic',
            rate: TARGET_RPS,
            timeUnit: '1s',
            startTime: WARMUP_DURATION,
            duration: DURATION,
            preAllocatedVUs: Math.max(50, Math.ceil(TARGET_RPS / 4)),
            maxVUs: Math.max(100, Math.ceil(TARGET_RPS * 1.2)),
            tags: { tier: 'REFERENCE', phase: 'steady' },
        },
    },
    thresholds: {
        dropped_iterations: ['count==0'],
        http_req_failed: ['rate<0.001'],
    },
};

export function referenceTraffic() {
    const res = http.get(`${BASE_URL}${ENDPOINT}`);
    check(res, { 'status 200': (r) => r.status === 200 });
}
