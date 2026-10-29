// Load test: 100 users using DeployHub at the same time (k6, https://k6.io).
//
//   1. Start the test backend:   ./load-tests/start-test-backend.sh        (wait for "Started")
//   2. Run this test:            k6 run load-tests/hundred-users.js
//
// Each virtual user (VU) is one person. On its first round it signs up, creates a project and deploys it
// (simulated). Then, until the test ends, it keeps using the app like the web pages do: who am I, dashboard,
// projects, deployments, logs. Each round also checks security: no login = 401, another user's project = 404.
//
// Options (with -e): BASE_URL (default http://localhost:8081), USERS (default 100), HOLD (default 2m).
// Never point it at your real DeployHub (8080): it would create 100 accounts there.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, group, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const USERS = Number(__ENV.USERS || 100);
const HOLD = __ENV.HOLD || '2m';
const PASSWORD = 'load-test-passphrase';
const RUN = Date.now().toString(36); // fresh emails on every run

export const options = {
  noCookiesReset: true, // a user stays logged in from one round to the next (k6 forgets cookies by default)
  scenarios: {
    hundred_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: USERS }, // users arrive over 30 seconds
        { duration: HOLD, target: USERS },  // everyone active at once
        { duration: '15s', target: 0 },     // users leave
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],                                  // 99% of all checks pass
    http_req_failed: ['rate<0.01'],                         // under 1% unexpected errors
    'http_req_duration{kind:read}': ['p(95)<500'],          // 95% of page data within 0.5 s
    'http_req_duration{kind:write}': ['p(95)<1500'],        // sign-up (BCrypt), create, deploy within 1.5 s
    deployment_live_seconds: ['p(95)<120'],                 // simulated deployments get through the queue
  },
};

const deploymentsLive = new Counter('deployments_live');
const deploymentLiveSeconds = new Trend('deployment_live_seconds');

// Per VU (each VU is its own JavaScript copy, so these are this user's own values).
const me = { email: null, projectId: null, deploymentId: null, deployedAt: 0, live: false };

const json = { 'Content-Type': 'application/json' };
const READ = { tags: { kind: 'read' } };

/** The CSRF token Spring Security put in the XSRF-TOKEN cookie; every POST/PUT/DELETE must echo it. */
function xsrf() {
  const cookies = http.cookieJar().cookiesForURL(BASE);
  return cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
}

function write(method, path, body, expected) {
  const res = http.request(method, `${BASE}${path}`, body === null ? null : JSON.stringify(body), {
    headers: { ...json, 'X-XSRF-TOKEN': xsrf() },
    tags: { kind: 'write', name: path.replace(/\d+/g, '{id}') },
    responseCallback: http.expectedStatuses(expected),
  });
  return res;
}

function read(path, expected = 200) {
  return http.get(`${BASE}${path}`, {
    ...READ,
    tags: { kind: 'read', name: path.replace(/\d+/g, '{id}').replace(/\?.*/, '') },
    responseCallback: http.expectedStatuses(expected),
  });
}

function signUpAndSetUp() {
  group('sign up', () => {
    read('/api/auth/csrf', 204); // first visit: get the CSRF cookie
    me.email = `load-${RUN}-${exec.vu.idInTest}@example.com`;
    const res = write('POST', '/api/auth/register',
      { displayName: `Load User ${letters(exec.vu.idInTest)}`, email: me.email, password: PASSWORD }, 201);
    if (!check(res, { 'sign up: 201 Created': (r) => r.status === 201 })) {
      fail(`sign up failed (${res.status}): ${res.body}`);
    }
  });

  group('create project', () => {
    const res = write('POST', '/api/projects', {
      name: 'load-test-app',
      repositoryUrl: 'https://github.com/123sajal/Employee-Management-',
      branch: 'main',
      appType: 'NODE_JS',
      buildCommand: 'npm ci',
      startCommand: 'node server.js',
    }, 201);
    check(res, { 'create project: 201 Created': (r) => r.status === 201 });
    me.projectId = res.json('id');
  });

  group('deploy', () => {
    const res = write('POST', `/api/projects/${me.projectId}/deployments`, null, 202);
    check(res, { 'deploy: 202 Accepted': (r) => r.status === 202 });
    me.deploymentId = res.json('id');
    me.deployedAt = Date.now();
  });
}

function useTheApp() {
  group('dashboard', () => {
    check(read('/api/auth/me'), { 'me: my own account': (r) => r.status === 200 && r.json('email') === me.email });
    check(read('/api/dashboard/summary'), { 'dashboard summary: 200': (r) => r.status === 200 });
    check(read('/api/dashboard/metrics'), { 'dashboard metrics: 200': (r) => r.status === 200 });
  });

  group('projects', () => {
    const list = read('/api/projects?page=0&size=20');
    check(list, {
      'projects: only my own project': (r) => r.status === 200 && r.json('items').length === 1
        && r.json('items.0.id') === me.projectId,
    });
    check(read(`/api/projects/${me.projectId}`), { 'project detail: 200': (r) => r.status === 200 });
  });

  group('deployments', () => {
    check(read(`/api/deployments?projectId=${me.projectId}&size=10`), { 'deployment list: 200': (r) => r.status === 200 });
    const d = read(`/api/deployments/${me.deploymentId}`);
    check(d, { 'deployment detail: 200': (r) => r.status === 200 });
    check(read(`/api/deployments/${me.deploymentId}/logs?limit=100`), { 'logs: 200': (r) => r.status === 200 });
    if (!me.live && d.status === 200 && d.json('status') === 'LIVE') {
      me.live = true;
      deploymentsLive.add(1);
      deploymentLiveSeconds.add((Date.now() - me.deployedAt) / 1000);
    }
  });

  group('security', () => {
    // Someone else's project (ids are given out in order, so the next id belongs to another user or nobody).
    check(read(`/api/projects/${me.projectId + 1}`, 404), { "other user's project: 404": (r) => r.status === 404 });
    // Without the login cookie the API must refuse.
    const anonymous = http.get(`${BASE}/api/projects`, {
      cookies: { JSESSIONID: { value: 'not-a-session', replace: true } },
      jar: new http.CookieJar(),
      tags: { kind: 'read', name: '/api/projects (no login)' },
      responseCallback: http.expectedStatuses(401),
    });
    check(anonymous, { 'no login: 401': (r) => r.status === 401 });
  });
}

export default function () {
  if (exec.vu.iterationInScenario === 0) {
    signUpAndSetUp();
  }
  useTheApp();
  sleep(1 + Math.random() * 2); // "think time": a person reads the page for 1-3 seconds
}

export function setup() {
  const res = http.get(`${BASE}/api/health`);
  if (res.status !== 200) {
    fail(`DeployHub is not answering at ${BASE} (start it with ./load-tests/start-test-backend.sh)`);
  }
  if (BASE.includes(':8080') && !__ENV.I_AM_SURE) {
    fail('Refusing to load-test your real DeployHub on 8080 (it would create 100 accounts). Use the test backend.');
  }
}

// Names may contain only letters and spaces: 1 -> "A", 26 -> "Z", 27 -> "AA".
function letters(n) {
  let out = '';
  for (let i = Number(n); i > 0; i = Math.floor((i - 1) / 26)) out = String.fromCharCode(65 + ((i - 1) % 26)) + out;
  return out || 'A';
}
