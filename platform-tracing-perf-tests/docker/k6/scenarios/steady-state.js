// Open-model steady-state нагрузка (Фаза 17, PR-3; performance-test-matrix M0–M9).
//
// КЛЮЧЕВОЕ: executor = constant-arrival-rate (open model). Closed model (VUs в цикле)
// замаскировал бы деградацию SUT снижением фактического RPS (coordinated omission);
// open model продолжает подавать запросы с фиксированной скоростью и честно показывает
// рост латентности/ошибок.
//
// Параметры через env (задаёт docker-compose env-файл сценария):
//   TARGET_URL    — базовый URL SUT (default http://sut:8080)
//   RATE          — целевой arrival rate, req/s (default 500)
//   WARMUP_MIN    — длительность warmup-фазы, мин (default 2; не входит в оценку SLA)
//   STEADY_MIN    — длительность steady-state, мин (default 10)
//   ERROR_PCT     — доля /api/error в трафике (default 0.05)
//   POST_PCT      — доля POST /api/orders (default 0.15)
//   TRACE_ON_PCT  — доля запросов с X-Trace-On: on (default 0.01; REQ-SAMPLING-001)
import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET_URL || 'http://sut:8080';
const RATE = parseInt(__ENV.RATE || '500', 10);
const WARMUP_MIN = parseInt(__ENV.WARMUP_MIN || '2', 10);
const STEADY_MIN = parseInt(__ENV.STEADY_MIN || '10', 10);
const ERROR_PCT = parseFloat(__ENV.ERROR_PCT || '0.05');
const POST_PCT = parseFloat(__ENV.POST_PCT || '0.15');
const TRACE_ON_PCT = parseFloat(__ENV.TRACE_ON_PCT || '0.01');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            exec: 'trafficMix',
            rate: RATE,
            timeUnit: '1s',
            duration: `${WARMUP_MIN}m`,
            // Консервативные VU-лимиты: на reference-лаборатории k6 с maxVUs=RATE*2
            // получал OOM (exit 137) при mem_limit=1g.
            preAllocatedVUs: Math.max(50, Math.ceil(RATE / 4)),
            maxVUs: Math.max(100, Math.ceil(RATE * 1.2)),
            tags: { phase: 'warmup' },
        },
        steady: {
            executor: 'constant-arrival-rate',
            exec: 'trafficMix',
            rate: RATE,
            timeUnit: '1s',
            startTime: `${WARMUP_MIN}m`,
            duration: `${STEADY_MIN}m`,
            // Консервативные VU-лимиты: на reference-лаборатории k6 с maxVUs=RATE*2
            // получал OOM (exit 137) при mem_limit=1g.
            preAllocatedVUs: Math.max(50, Math.ceil(RATE / 4)),
            maxVUs: Math.max(100, Math.ceil(RATE * 1.2)),
            tags: { phase: 'steady' },
        },
    },
    // Validity gates прогона (performance-test-matrix): dropped_iterations > 0 означает,
    // что генератор не выдержал arrival rate — прогон НЕВАЛИДЕН независимо от латентности.
    thresholds: {
        dropped_iterations: ['count<1'],
        // Деградационные сценарии (M6/M8x) валидируются по дельте с M0 раннером,
        // здесь только страховка от полностью неработающего SUT.
        'http_req_failed{expected_response:true}': ['rate<0.01'],
    },
    // Полный набор перцентилей для summary-export; raw-данные при необходимости — k6 --out csv.
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
    discardResponseBodies: true,
};

function reqParams(base, metricName) {
    // name-тег группирует URL в метриках k6; без него /api/orders/{id} даёт >800k time series
    // и k6 теряет итерации (dropped_iterations) на длинных прогонах.
    return { ...base, tags: { ...(base.tags || {}), name: metricName } };
}

export function trafficMix() {
    const r = Math.random();
    const params = { headers: {} };
    if (Math.random() < TRACE_ON_PCT) {
        // Форс-запись трейса: ветка ForceHeaderRule сэмплера (REQ-SAMPLING-001).
        params.headers['X-Trace-On'] = 'on';
    }

    if (r < ERROR_PCT) {
        // Синтетическая ошибка: error span + exception event (5xx ожидаем).
        const res = http.get(`${TARGET}/api/error`, reqParams(params, 'GET /api/error'));
        check(res, { 'error endpoint returns 5xx': (x) => x.status >= 500 });
    } else if (r < ERROR_PCT + POST_PCT) {
        params.headers['Content-Type'] = 'application/json';
        const res = http.post(`${TARGET}/api/orders`,
                JSON.stringify({ items: 3, warehouse: 'msk-01', totalCents: 129900 }),
                reqParams(params, 'POST /api/orders'));
        check(res, { 'POST 200': (x) => x.status === 200 });
    } else {
        const id = Math.floor(Math.random() * 1000000) + 1;
        const res = http.get(`${TARGET}/api/orders/${id}`, reqParams(params, 'GET /api/orders/{id}'));
        check(res, { 'GET 200': (x) => x.status === 200 });
    }
}
