// Load scenarios for hr-sync. One line, from the repo root, after compose is up
// and generate.py has seeded the database:
//
// docker run --rm --network hrsync-load --env-file load/.env -e SCENARIO=ingest -e BASE_URL=http://app:8080 -v "$PWD:/work" -w /work grafana/k6 run load/k6.js
//
// SCENARIO is ingest, contention, reads, mixed, or ramp.
// ramp reads RAMP_EXEC (default ingest) and holds 2, 4, 8, 16, 32, then 48 VUs.
// VUS, WARMUP (default 10s) and DURATION (default 60s) apply to the four scenarios.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BATCH = 100;
const COLD_IDS = 100000;
const MAX_VU_SLOT = 64;

const eventsAttempted = new Counter('events_attempted');
const eventsClassified = new Counter('events_classified');
const eventsApplied = new Counter('events_applied');
const eventsStale = new Counter('events_stale');
const eventsDuplicate = new Counter('events_duplicate');
const eventsRejected = new Counter('events_rejected');
const eventsOther = new Counter('events_other');
const eventsUnknown = new Counter('events_unknown');
const eventsHttpFail = new Counter('events_http_fail');
const readLeaks = new Counter('read_leaks');
const coldExhausted = new Counter('cold_exhausted');
const steadyReqs = new Counter('steady_reqs');
const steadyFailed = new Counter('steady_failed');
const steadyLatency = new Trend('steady_latency_ms');
const warmReqs = new Counter('warm_reqs');
const warmLatency = new Trend('warm_latency_ms');
const coldReqs = new Counter('cold_reqs');
const coldLatency = new Trend('cold_latency_ms');
const stageLatency = {};
const stageReqs = {};
[2, 4, 8, 16, 32, 48].forEach(function (level) {
  stageLatency[level] = new Trend('stage_' + level + '_ms');
  stageReqs[level] = new Counter('stage_' + level + '_reqs');
});

const base = __ENV.BASE_URL || 'http://localhost:8092';
const scenario = __ENV.SCENARIO || 'ingest';
const vus = Number(__ENV.VUS || 8);
const warmup = __ENV.WARMUP || '10s';
const duration = __ENV.DURATION || '60s';
const stage = __ENV.STAGE || '20s';

function seconds(text) {
  const match = /^(\d+)(s|m)$/.exec(text);
  if (!match) {
    throw new Error('duration must look like 10s or 1m, got ' + text);
  }
  return match[2] === 'm' ? Number(match[1]) * 60 : Number(match[1]);
}

function hold(name, execName, count, start, dur, extraTags) {
  return {
    executor: 'constant-vus',
    vus: count,
    duration: dur,
    startTime: start + 's',
    exec: execName,
    gracefulStop: '5s',
    tags: Object.assign({ phase: name }, extraTags || {}),
  };
}

function scenarioOptions() {
  const warmSeconds = seconds(warmup);
  const total = (warmSeconds + seconds(duration)) + 's';
  if (scenario === 'ingest') {
    return {
      'warmup-ingest': hold('warmup', 'ingest', vus, 0, warmup),
      'steady-ingest': hold('steady', 'ingest', vus, warmSeconds, duration),
    };
  }
  if (scenario === 'contention') {
    return {
      'warmup-contention': hold('warmup', 'contention', vus, 0, warmup),
      'steady-contention': hold('steady', 'contention', vus, warmSeconds, duration),
    };
  }
  if (scenario === 'reads') {
    const half = Math.max(1, Math.ceil(vus / 2));
    return {
      'warmup-warm': hold('warmup', 'readWarm', half, 0, warmup, { cache: 'warm' }),
      'steady-warm': hold('steady', 'readWarm', half, warmSeconds, duration, { cache: 'warm' }),
      'steady-cold': hold('steady', 'readCold', half, warmSeconds, duration, { cache: 'cold' }),
    };
  }
  if (scenario === 'mixed') {
    const readers = Math.max(1, Math.floor(vus / 2));
    const writers = Math.max(1, vus - readers);
    return {
      'warmup-ingest': hold('warmup', 'ingest', writers, 0, warmup),
      'steady-ingest': hold('steady', 'ingest', writers, warmSeconds, duration),
      'warmup-read': hold('warmup', 'readWarm', readers, 0, warmup, { cache: 'warm' }),
      'steady-read': hold('steady', 'readWarm', readers, warmSeconds, duration, { cache: 'warm' }),
      metrics: {
        executor: 'constant-vus',
        vus: 1,
        duration: total,
        startTime: '0s',
        exec: 'scrape',
        gracefulStop: '5s',
        tags: { phase: 'metrics' },
      },
    };
  }
  if (scenario === 'ramp') {
    const levels = [2, 4, 8, 16, 32, 48];
    const execName = __ENV.RAMP_EXEC || 'ingest';
    const stageSeconds = seconds(stage);
    const gap = 8;
    const scenarios = {};
    levels.forEach((level, index) => {
      scenarios['vu' + level] = hold(
        'stage',
        execName,
        level,
        index * (stageSeconds + gap),
        stage,
        { stage: String(level) }
      );
    });
    return scenarios;
  }
  throw new Error('unknown SCENARIO ' + scenario);
}

export const options = {
  scenarios: scenarioOptions(),
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'count'],
  insecureSkipTLSVerify: true,
};

const summaryName = __ENV.SUMMARY || scenario;

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error('missing ' + name);
  }
  return value;
}

function login(username, password) {
  const response = http.post(
    base + '/auth/token',
    JSON.stringify({ username: username, password: password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } }
  );
  if (response.status !== 200) {
    throw new Error('login failed for ' + username + ' status ' + response.status);
  }
  return response.json('token');
}

export function setup() {
  return {
    tokenA: login(required('HR_SYNC_BOOTSTRAP_ADMIN_USERNAME'), required('HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD')),
    tokenB: login(required('HR_SYNC_LOAD_ADMIN_B_USERNAME'), required('HR_SYNC_LOAD_ADMIN_B_PASSWORD')),
    tokenOp: login(required('HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME'), required('HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD')),
    tenantA: required('HR_SYNC_BOOTSTRAP_ADMIN_TENANT'),
    tenantB: required('HR_SYNC_LOAD_ADMIN_B_TENANT'),
  };
}

function caller(data) {
  if ((__VU + __ITER) % 2 === 0) {
    return { token: data.tokenA, tenant: data.tenantA, office: 'BLR' };
  }
  return { token: data.tokenB, tenant: data.tenantB, office: 'NYC' };
}

function versionSalt() {
  const name = exec.scenario.name;
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) % 997;
  }
  return hash * 1000;
}

function fields(employeeId, office, invalid) {
  const letter = office === 'BLR' ? 'A' : 'B';
  return {
    name: letter + ' ' + employeeId,
    email: employeeId.replace(/[^A-Za-z0-9]/g, '') + '@load.example',
    phone: invalid ? '12345' : '+15550001111',
    manager: 'M1',
    officeCode: office,
    shift: 'DAY',
  };
}

function recordOutcome(row) {
  if (!row) {
    eventsOther.add(1);
    return;
  }
  if (row.outcome === 'APPLIED') {
    eventsApplied.add(1);
    return;
  }
  if (row.outcome === 'IGNORED' && row.reasonCode === 'STALE_IGNORED') {
    eventsStale.add(1);
    return;
  }
  if (row.outcome === 'IGNORED' && row.reasonCode === 'DUPLICATE') {
    eventsDuplicate.add(1);
    return;
  }
  if (row.outcome === 'REJECTED') {
    eventsRejected.add(1);
    return;
  }
  eventsOther.add(1);
}

function observe(response, kind) {
  const name = exec.scenario.name;
  const ms = response.timings.duration;
  const failed = response.status === 0 || response.status >= 400;
  if (name.indexOf('steady') === 0) {
    steadyReqs.add(1);
    if (failed) {
      steadyFailed.add(1);
    }
    steadyLatency.add(ms);
  }
  if (kind === 'warm' && name.indexOf('warmup') !== 0) {
    warmReqs.add(1);
    warmLatency.add(ms);
  }
  if (kind === 'cold') {
    coldReqs.add(1);
    coldLatency.add(ms);
  }
  const stage = /^vu(\d+)$/.exec(name);
  if (stage) {
    const level = Number(stage[1]);
    stageReqs[level].add(1);
    stageLatency[level].add(ms);
  }
}

function postBatch(token, events) {
  eventsAttempted.add(events.length);
  const response = http.post(base + '/sync/events', JSON.stringify({ events: events }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + token,
    },
    timeout: '15s',
    tags: { name: 'ingest' },
  });
  observe(response, 'ingest');
  // A killed app, or an iteration k6 cuts off mid-request, has no classifiable response.
  if (response.status === 0) {
    eventsUnknown.add(events.length);
    return;
  }
  if (response.status !== 200) {
    eventsHttpFail.add(events.length);
    return;
  }
  let body;
  try {
    body = response.json();
  } catch (error) {
    eventsHttpFail.add(events.length);
    return;
  }
  const results = body && body.results;
  if (!results) {
    eventsHttpFail.add(events.length);
    return;
  }
  if (results.length !== events.length) {
    eventsHttpFail.add(events.length - results.length);
  }
  eventsClassified.add(results.length);
  for (let i = 0; i < results.length; i++) {
    recordOutcome(results[i]);
  }
}

export function ingest(data) {
  const who = caller(data);
  const events = [];
  const salt = versionSalt();
  for (let index = 0; index < BATCH; index++) {
    const slot = index % 20;
    const employeeId = 'a-' + __VU + '-' + (index % 4);
    const invalid = slot === 0;
    const duplicate = slot === 1 || slot === 2;
    const outOfOrder = slot === 3 || slot === 4;
    let version;
    let type = 'UPDATE';
    if (duplicate) {
      version = 1;
      type = 'CREATE';
    } else if (outOfOrder) {
      version = (Date.now() - 60000) * 1000 + __VU * 100 + index + salt + 400;
    } else {
      version = Date.now() * 1000 + __VU * 100 + index + salt;
    }
    events.push({
      tenantId: who.tenant,
      employeeId: employeeId,
      sourceVersion: version,
      type: type,
      fields: fields(employeeId, who.office, invalid),
    });
  }
  postBatch(who.token, events);
}

export function contention(data) {
  const who = caller(data);
  const events = [];
  const salt = versionSalt();
  for (let index = 0; index < BATCH; index++) {
    const employeeId = 'c-' + (index % 4);
    events.push({
      tenantId: who.tenant,
      employeeId: employeeId,
      sourceVersion: Date.now() * 1000 + __VU * 100 + index + salt,
      type: 'UPDATE',
      fields: fields(employeeId, who.office, false),
    });
  }
  postBatch(who.token, events);
}

function readOne(data, employeeId, cache) {
  const who = caller(data);
  const response = http.get(base + '/employees/' + employeeId, {
    headers: { Authorization: 'Bearer ' + who.token },
    timeout: '10s',
    tags: { name: 'read', cache: cache },
  });
  observe(response, cache);
  if (response.status !== 200) {
    return;
  }
  let body;
  try {
    body = response.json();
  } catch (error) {
    readLeaks.add(1);
    return;
  }
  const letter = who.office === 'BLR' ? 'A' : 'B';
  const ok = check(body, {
    office: function (row) { return row.officeCode === who.office; },
    tenant: function (row) { return row.tenantId === who.tenant; },
    status: function (row) { return row.status === 'ACTIVE'; },
  });
  if (!ok || (body.name && body.name.charAt(0) !== letter)) {
    readLeaks.add(1);
  }
}

export function readWarm(data) {
  readOne(data, 'w-' + (__ITER % 20), 'warm');
}

export function readCold(data) {
  const warming = exec.scenario.name.indexOf('warmup') !== -1;
  const baseId = warming ? 0 : 50000;
  const limit = warming ? 50000 : COLD_IDS;
  const stride = scenario === 'reads' ? Math.max(1, Math.ceil(vus / 2)) : MAX_VU_SLOT;
  const id = baseId + __ITER * stride + (__VU - 1);
  if (id >= limit || id < baseId) {
    coldExhausted.add(1);
    sleep(1);
    return;
  }
  readOne(data, 'd-' + id, 'cold');
}

export function scrape(data) {
  const response = http.get(base + '/actuator/prometheus', {
    headers: {
      Authorization: 'Bearer ' + data.tokenOp,
      Accept: 'text/plain',
    },
    timeout: '10s',
    tags: { name: 'metrics' },
  });
  observe(response, 'metrics');
  sleep(1);
}

function countOf(data, name) {
  const metric = data.metrics[name];
  if (!metric || !metric.values) {
    return 0;
  }
  return metric.values.count || 0;
}

export function handleSummary(data) {
  const slim = {
    scenario: scenario,
    vus: vus,
    warmup: warmup,
    duration: duration,
    events_attempted: countOf(data, 'events_attempted'),
    events_classified: countOf(data, 'events_classified'),
    events_applied: countOf(data, 'events_applied'),
    events_stale: countOf(data, 'events_stale'),
    events_duplicate: countOf(data, 'events_duplicate'),
    events_rejected: countOf(data, 'events_rejected'),
    events_other: countOf(data, 'events_other'),
    events_unknown: countOf(data, 'events_unknown'),
    events_http_fail: countOf(data, 'events_http_fail'),
    read_leaks: countOf(data, 'read_leaks'),
    cold_exhausted: countOf(data, 'cold_exhausted'),
    steady_reqs: countOf(data, 'steady_reqs'),
    steady_failed: countOf(data, 'steady_failed'),
    warm_reqs: countOf(data, 'warm_reqs'),
    cold_reqs: countOf(data, 'cold_reqs'),
    metrics: {},
  };
  ['steady_latency_ms', 'warm_latency_ms', 'cold_latency_ms'].forEach(function (name) {
    if (data.metrics[name]) {
      slim.metrics[name] = data.metrics[name].values;
    }
  });
  [2, 4, 8, 16, 32, 48].forEach(function (level) {
    const latency = 'stage_' + level + '_ms';
    const reqs = 'stage_' + level + '_reqs';
    if (data.metrics[latency]) {
      slim.metrics[latency] = data.metrics[latency].values;
    }
    slim['stage_' + level + '_reqs'] = countOf(data, reqs);
  });
  Object.keys(data.metrics).forEach(function (name) {
    if (name.indexOf('http_req_duration') === 0 || name.indexOf('http_reqs') === 0 || name.indexOf('http_req_failed') === 0) {
      slim.metrics[name] = data.metrics[name].values;
    }
  });
  const text = JSON.stringify(slim, null, 2) + '\n';
  const file = 'load/data/' + summaryName + '-summary.json';
  const out = { stdout: text };
  out[file] = JSON.stringify(slim);
  return out;
}
