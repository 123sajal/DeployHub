// Removes what load-tests/live-links.js leaves behind (after KEEP=1, or a run that was stopped halfway):
// for each test account it cancels the project's unfinished deployments and deletes the project, which stops its
// containers. The accounts themselves stay, so the next run can reuse them.
//
//   k6 run load-tests/cleanup-live-links.js                 (accounts live-link-1..100)
//   k6 run -e USERS=10 load-tests/cleanup-live-links.js
//
// One account at a time, so deletes don't run into each other.

import http from 'k6/http';
import { sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 100);
const PASSWORD = 'live-link-passphrase';
const PROJECT_NAME = 'live-link-test';
const UNFINISHED = ['QUEUED', 'BUILDING', 'STARTING'];

export const options = {
  scenarios: { cleanup: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '30m' } },
};

export default function () {
  let deleted = 0;
  for (let n = 1; n <= USERS; n++) {
    const jar = new http.CookieJar();
    const token = () => (jar.cookiesForURL(BASE)['XSRF-TOKEN'] || [''])[0];
    const send = (method, path, body = null) => http.request(method, `${BASE}${path}`,
      body === null ? null : JSON.stringify(body),
      { jar, headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token() } });

    http.get(`${BASE}/api/auth/csrf`, { jar });
    const login = send('POST', '/api/auth/login', { email: `live-link-${n}@example.com`, password: PASSWORD });
    if (login.status === 401) continue; // no such account: nothing to clean
    if (login.status !== 200) {
      console.error(`live-link-${n}: cannot log in (${login.status}); stopping`);
      return;
    }

    const list = http.get(`${BASE}/api/projects?search=${PROJECT_NAME}&size=100`, { jar });
    const project = list.json('items').find((p) => p.name === PROJECT_NAME);
    if (!project) continue;

    const deployments = http.get(`${BASE}/api/deployments?projectId=${project.id}&size=50`, { jar }).json('items');
    deployments.filter((d) => UNFINISHED.includes(d.status))
      .forEach((d) => send('POST', `/api/deployments/${d.id}/cancel`));

    // A deployment that is already starting finishes first, so keep trying for up to a minute.
    let res;
    for (let attempt = 0; attempt < 20; attempt++) {
      res = send('DELETE', `/api/projects/${project.id}`);
      if (res.status === 204) break;
      sleep(3);
    }
    if (res.status === 204) {
      deleted++;
    } else {
      console.warn(`live-link-${n}: project ${project.id} not deleted (${res.status}); delete it in the app`);
    }
  }
  console.log(`Deleted ${deleted} test project(s).`);
}
