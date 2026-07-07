import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const DURATION = __ENV.DURATION || '30s';
const TARGET_RPS = parseInt(__ENV.TARGET_RPS || '5', 10);
const SCENARIO = __ENV.SCENARIO || 'smoke';
const SPRING_PROFILE = __ENV.SPRING_PROFILE || 'smoke';

export const options = {
    scenarios: {
        smoke: {
            executor: 'constant-arrival-rate',
            exec: 'smokeTraffic',
            rate: TARGET_RPS,
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 2,
            maxVUs: 10,
            tags: { tier: 'SMOKE', scenario: SCENARIO, profile: SPRING_PROFILE },
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.10'],
        dropped_iterations: ['count<1'],
    },
};

export function smokeTraffic() {
    const health = http.get(`${BASE_URL}/perf/health`);
    check(health, { 'health 200': (r) => r.status === 200 });

    const fast = http.get(`${BASE_URL}/perf/fast`);
    check(fast, { 'fast 200': (r) => r.status === 200 });

    const valid = http.get(`${BASE_URL}/perf/validation/valid`);
    check(valid, { 'validation valid 200': (r) => r.status === 200 });

    const missing = http.get(`${BASE_URL}/perf/validation/missing`);
    check(missing, { 'validation missing 200': (r) => r.status === 200 });
}
