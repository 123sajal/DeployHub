// Load test: many users deploy at the same time and each one must get a working live link (k6, https://k6.io).
//
//   1. Start DeployHub as usual (backend on 8080 with REAL deployments, Docker Desktop running).
//   2. Run this test:   k6 run load-tests/live-links.js                  (10 users)
//                       k6 run -e USERS=100 load-tests/live-links.js     (100 users, see the note on memory below)
//
// Every virtual user (VU) is one person, and they all start together:
//   log in -> create a project -> deploy it -> wait until it is LIVE
//   -> open its live link and expect HTTP 200 -> delete the project again (stops its containers).
// The test passes only when every user got a live link that answers. Accounts are created one by one before that.
//
// This runs REAL Docker builds against your real DeployHub. It reuses the same accounts on every run
// (live-link-1@example.com, live-link-2@example.com, ...), so your database doesn't fill up with new users.
//
// Options (with -e):
//   USERS     how many users deploy at once (default 10)
//   BASE_URL  DeployHub API (default http://localhost:8080)
//   MAX_WAIT  how long one deployment may take to become LIVE (default 20m; deployments queue, 2 build at a time)
//   KEEP=1    leave the apps running afterwards so you can open the links (delete the projects by hand later)
//   REPO, APP_TYPE, BUILD, START, HEALTH   the app every user deploys (default: Heroku's small public Express
//             sample, which needs no database or secrets; apps that need env vars would crash on start)
//
// Memory: builds run 2 at a time and each user deletes its app right after checking the link, so only a few apps
// run at once and 100 users fit in Docker Desktop's ~4 GB. With KEEP=1 every app stays up (about 70 MB each with
// its address proxy): raise Docker Desktop's memory (Settings > Resources) before keeping many.
//
// Sign-ups: DeployHub allows 10 new accounts per network per hour. For more users, start the backend with
//   ./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle \
//       -Dspring-boot.run.arguments=--deployhub.security.max-registrations-per-ip=1000
// Accounts are reused on later runs, so this is only needed until all of them exist.
//
// Paid deploys: each run deletes its projects, so with billing on the second run would need payment (the free
// project is used up). Run the test against a backend with billing off:
//   ./mvnw spring-boot:run -Dspring-boot.run.profiles=oracle \
//       -Dspring-boot.run.arguments=--deployhub.billing.enabled=false
// (combine with the sign-up setting as: -Dspring-boot.run.arguments="--deployhub.billing.enabled=false
//  --deployhub.security.max-registrations-per-ip=1000")

import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 10);
const MAX_WAIT_SECONDS = parseDuration(__ENV.MAX_WAIT || '20m');
const KEEP = !!__ENV.KEEP;
const PASSWORD = 'live-link-passphrase';
const PROJECT_NAME = 'live-link-test';
const APP = {
  repositoryUrl: __ENV.REPO || 'https://github.com/heroku/node-js-getting-started.git',
  branch: 'main',
  appType: __ENV.APP_TYPE || 'NODE_JS',
  buildCommand: __ENV.BUILD || 'npm ci --omit=dev',
  startCommand: __ENV.START || 'npm start',
  healthCheckPath: __ENV.HEALTH || '/',
};
const FINISHED = ['LIVE', 'FAILED', 'CANCELLED', 'SUPERSEDED'];

export const options = {
  noCookiesReset: true,
  setupTimeout: '5m', // creating up to 100 accounts one by one
  scenarios: {
    deploy_together: {
      executor: 'per-vu-iterations', // every user starts at the same moment and deploys once
      vus: USERS,
      iterations: 1,
      maxDuration: `${MAX_WAIT_SECONDS + 300}s`,
    },
  },
  thresholds: {
    got_live_link: ['rate>=0.99'],              // every user got a live link that answers
    seconds_to_live: [`p(95)<${MAX_WAIT_SECONDS}`],
    'http_req_duration{kind:api}': ['p(95)<2000'], // the API stays responsive while it builds
  },
};

const gotLiveLink = new Rate('got_live_link');
const secondsToLive = new Trend('seconds_to_live');
const deploymentsFailed = new Counter('deployments_failed');
const linkSecondsAfterLive = new Trend('link_seconds_after_live');

const json = { 'Content-Type': 'application/json' };

function parseDuration(text) {
  const m = /^(\d+)(s|m|h)?$/.exec(text);
  if (!m) throw new Error(`MAX_WAIT must look like 90s, 20m or 1h, not "${text}"`);
  return Number(m[1]) * ({ s: 1, m: 60, h: 3600 }[m[2] || 's']);
}

/** The CSRF token Spring Security put in the XSRF-TOKEN cookie; every POST/PUT/DELETE must echo it. */
function xsrf() {
  const cookies = http.cookieJar().cookiesForURL(BASE);
  return cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : '';
}

function write(method, path, body, expected) {
  return http.request(method, `${BASE}${path}`, body === null ? null : JSON.stringify(body), {
    headers: { ...json, 'X-XSRF-TOKEN': xsrf() },
    tags: { kind: 'api', name: path.replace(/\d+/g, '{id}') },
    responseCallback: http.expectedStatuses(...expected),
  });
}

function read(path, expected = 200) {
  return http.get(`${BASE}${path}`, {
    tags: { kind: 'api', name: path.replace(/\d+/g, '{id}').replace(/\?.*/, '') },
    responseCallback: http.expectedStatuses(expected),
  });
}

function who() {
  return `user ${exec.vu.idInTest}`;
}

function email(n) {
  return `live-link-${n}@example.com`;
}

/** Logs in; setup() has already made sure the account exists. */
function signIn() {
  read('/api/auth/csrf', 204);
  const login = write('POST', '/api/auth/login', { email: email(exec.vu.idInTest), password: PASSWORD }, [200]);
  if (login.status === 200) return true;
  console.error(`${who()}: cannot log in (${login.status}).`
    + (login.status === 429 ? ' Too many failed logins from this network: wait 15 minutes.' : ''));
  return false;
}

/**
 * Makes sure accounts live-link-1..USERS exist, one at a time before anyone deploys. Doing it here, not in the
 * users, means a throttled sign-up stops the test after one failed login instead of 100: DeployHub blocks a
 * network's logins for 15 minutes after 30 failures.
 */
function ensureAccounts() {
  let created = 0;
  for (let n = 1; n <= USERS; n++) {
    const jar = new http.CookieJar(); // every account starts logged out
    http.get(`${BASE}/api/auth/csrf`, { jar });
    const token = () => (jar.cookiesForURL(BASE)['XSRF-TOKEN'] || [''])[0];
    const post = (path, body) => http.post(`${BASE}${path}`, JSON.stringify(body), {
      jar,
      headers: { ...json, 'X-XSRF-TOKEN': token() },
      tags: { kind: 'setup' },
      responseCallback: http.expectedStatuses(200, 201, 409, 429),
    });
    const reg = post('/api/auth/register', { displayName: `Live Link User ${letters(n)}`, email: email(n), password: PASSWORD });
    if (reg.status === 201) { created++; continue; }
    if (reg.status === 409) continue;
    // 429: sign-ups are throttled, and DeployHub checks that before "already exists", so try logging in once.
    if (reg.status === 429 && post('/api/auth/login', { email: email(n), password: PASSWORD }).status === 200) continue;
    fail(`Cannot create ${email(n)} (sign-up ${reg.status}). DeployHub allows 10 sign-ups per network per hour: `
      + 'see "Sign-ups" at the top of this file.');
  }
  return created;
}

/** Stops at once when paid deploys are on: most users would get "payment required" instead of a live link. */
function requireFreeDeploys() {
  const jar = new http.CookieJar();
  http.get(`${BASE}/api/auth/csrf`, { jar });
  const token = (jar.cookiesForURL(BASE)['XSRF-TOKEN'] || [''])[0];
  http.post(`${BASE}/api/auth/login`, JSON.stringify({ email: email(1), password: PASSWORD }),
    { jar, headers: { ...json, 'X-XSRF-TOKEN': token }, tags: { kind: 'setup' } });
  const billing = http.get(`${BASE}/api/billing`, { jar, tags: { kind: 'setup' } });
  if (billing.status === 200 && billing.json('enabled')) {
    fail('Paid deploys are on, so test users would be asked to pay. Restart the backend with '
      + '--deployhub.billing.enabled=false (see "Paid deploys" at the top of this file).');
  }
}

/** The user's test project: reused if an earlier run left it (KEEP=1), otherwise created. */
function projectId() {
  const existing = read(`/api/projects?search=${PROJECT_NAME}&size=100`);
  const found = existing.status === 200 && existing.json('items').find((p) => p.name === PROJECT_NAME);
  if (found) return found.id;
  const res = write('POST', '/api/projects', { name: PROJECT_NAME, ...APP }, [201]);
  check(res, { 'create project: 201 Created': (r) => r.status === 201 });
  return res.status === 201 ? res.json('id') : null;
}

/** Polls the deployment every 3 s until it finishes or MAX_WAIT passes; returns the last answer. */
function waitUntilFinished(deploymentId) {
  const deadline = Date.now() + MAX_WAIT_SECONDS * 1000;
  let d = null;
  while (Date.now() < deadline) {
    const res = read(`/api/deployments/${deploymentId}`);
    if (res.status === 200) {
      d = res.json();
      if (FINISHED.includes(d.status)) return d;
    }
    sleep(3);
  }
  return d;
}

export default function () {
  let id = null;
  let ok = false;

  group('sign in', () => {
    if (!check(signIn(), { 'sign in: logged in': (v) => v })) fail(`${who()}: not logged in`);
  });

  group('create project', () => {
    id = projectId();
    if (!id) fail(`${who()}: project could not be created`);
  });

  group('deploy and get live link', () => {
    const started = Date.now();
    const res = write('POST', `/api/projects/${id}/deployments`, null, [202]);
    if (!check(res, { 'deploy: 202 Accepted': (r) => r.status === 202 })) {
      console.error(`${who()}: deploy refused (${res.status}): ${res.body}`);
      return;
    }
    const d = waitUntilFinished(res.json('id'));
    const status = d ? d.status : 'NO ANSWER';
    const isLive = check(d, {
      'deployment: LIVE': (x) => x !== null && x.status === 'LIVE',
      'deployment: has a live link': (x) => x !== null && !!x.appUrl,
    });
    if (!isLive) {
      deploymentsFailed.add(1);
      const why = d && d.failureReason ? `: ${d.failureReason}` : '';
      console.error(`${who()}: deployment ended ${status} after ${Math.round((Date.now() - started) / 1000)} s${why}`);
      return;
    }
    secondsToLive.add((Date.now() - started) / 1000);

    // The address proxy may still be starting when the deployment turns LIVE: retry for up to 60 s,
    // and measure the gap so a slow link shows up in the results instead of being hidden.
    const liveAt = Date.now();
    const link = `${d.appUrl.replace(/\/$/, '')}${APP.healthCheckPath}`;
    let app;
    do {
      app = http.get(link, {
        tags: { kind: 'app', name: 'live link' },
        timeout: '30s',
        responseCallback: http.expectedStatuses(200),
      });
      if (app.status === 200) break;
      sleep(1);
    } while (Date.now() - liveAt < 60000);
    ok = check(app, { 'live link: answers 200': (r) => r.status === 200 });
    if (ok) linkSecondsAfterLive.add((Date.now() - liveAt) / 1000);
    console.log(`${who()}: LIVE in ${Math.round((liveAt - started) / 1000)} s, link answered ${app.status} `
      + `${Math.round((Date.now() - liveAt) / 1000)} s later -> ${d.appUrl}`);
  });
  gotLiveLink.add(ok);

  if (!KEEP) {
    group('clean up', () => {
      // Deleting several projects at the same moment can hit a database deadlock (503), and a deployment
      // that just turned LIVE may still be finishing (409): try a few times before giving up.
      let res;
      for (let attempt = 1; attempt <= 5; attempt++) {
        res = write('DELETE', `/api/projects/${id}`, null, [204, 409, 503]);
        if (res.status === 204) break;
        sleep(attempt * 2);
      }
      if (res.status !== 204) console.warn(`${who()}: project ${id} not deleted (${res.status}); delete it in the app`);
    });
  }
}

export function setup() {
  const res = http.get(`${BASE}/api/health`);
  if (res.status !== 200) fail(`DeployHub is not answering at ${BASE}. Start the backend first.`);
  if (res.json('deploymentExecutionMode') !== 'REAL') {
    fail(`DeployHub at ${BASE} simulates deployments, so there are no live links. Use a backend in REAL mode.`);
  }
  const created = ensureAccounts();
  requireFreeDeploys();
  console.log(`${USERS} accounts ready (${created} new). They now deploy ${APP.repositoryUrl} at once on ${BASE}`
    + (KEEP ? ' (apps kept running)' : ''));
}

// Names may contain only letters and spaces: 1 -> "A", 26 -> "Z", 27 -> "AA".
function letters(n) {
  let out = '';
  for (let i = Number(n); i > 0; i = Math.floor((i - 1) / 26)) out = String.fromCharCode(65 + ((i - 1) % 26)) + out;
  return out || 'A';
}
