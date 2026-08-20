/*
 * Demo console for the URL shortener.
 *
 * Two constraints shaped this file and are worth knowing before editing it.
 *
 * The page is served by the application under a strict CSP: `script-src 'self'` and no
 * `unsafe-inline`. So there are no inline handlers, no CDN imports and no eval anywhere here.
 * If a change needs any of those, the CSP is the thing that should win.
 *
 * Everything rendered from a response goes through textContent, never innerHTML. Destinations
 * are attacker-controlled by definition in a link shortener, and building markup out of them
 * would put a DOM XSS hole in the console of a service whose entire point is handling hostile
 * URLs safely.
 */

const state = {
  token: null,
  claims: null,
  username: null,
  roles: [],
  links: [],
};

const $ = (id) => document.getElementById(id);
const el = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};

/* ── The wire log ─────────────────────────────────────────────────────────── */

/** Headers worth surfacing: each one is a design decision made visible. */
const NOTABLE_HEADERS = [
  'location', 'retry-after', 'www-authenticate',
  'x-ratelimit-limit', 'x-ratelimit-remaining',
  'content-security-policy', 'strict-transport-security',
  'x-frame-options', 'x-content-type-options', 'referrer-policy', 'permissions-policy',
];

function statusClass(status) {
  if (!status) return 'serr';
  return 's' + Math.floor(status / 100) + 'xx';
}

function logExchange(entry) {
  const wire = $('wire');
  const placeholder = wire.querySelector('.empty');
  if (placeholder) placeholder.remove();

  const row = el('div', 'entry');
  const head = el('div', 'entry-head');
  head.appendChild(el('span', 'entry-method', entry.method));
  head.appendChild(el('span', 'entry-path', entry.path));

  const label = entry.opaque ? '302' : (entry.status || 'ERR');
  head.appendChild(el('span', 'entry-status ' + statusClass(entry.opaque ? 302 : entry.status), String(label)));
  head.appendChild(el('span', 'entry-ms', entry.ms + 'ms'));

  const body = el('div', 'entry-body');

  if (entry.opaque) {
    body.appendChild(el('p', 'entry-note',
      'The browser withholds redirect responses from fetch, so the status and Location are '
      + 'hidden here. The request did reach the server and the click was recorded.'));
  }
  if (entry.error) {
    body.appendChild(el('p', 'entry-note', entry.error));
  }

  if (entry.requestBody !== undefined) {
    body.appendChild(el('h4', null, 'Request'));
    body.appendChild(el('pre', null, JSON.stringify(entry.requestBody, null, 2)));
  }

  const shown = NOTABLE_HEADERS
    .filter((name) => entry.headers && entry.headers[name])
    .map((name) => name + ': ' + entry.headers[name]);
  if (shown.length) {
    body.appendChild(el('h4', null, 'Response headers'));
    body.appendChild(el('pre', null, shown.join('\n')));
  }

  if (entry.body !== undefined && entry.body !== null && entry.body !== '') {
    body.appendChild(el('h4', null, 'Response body'));
    const text = typeof entry.body === 'string' ? entry.body : JSON.stringify(entry.body, null, 2);
    body.appendChild(el('pre', null, text.length > 1400 ? text.slice(0, 1400) + '\n…' : text));
  }

  head.addEventListener('click', () => row.classList.toggle('open'));
  row.appendChild(head);
  row.appendChild(body);
  wire.prepend(row);

  while (wire.children.length > 60) wire.lastChild.remove();
  return row;
}

/** A verdict from a scenario, shown alongside the exchanges that produced it. */
function logVerdict(text, ok) {
  const wire = $('wire');
  const placeholder = wire.querySelector('.empty');
  if (placeholder) placeholder.remove();

  const row = el('div', 'entry open');
  const head = el('div', 'entry-head');
  head.appendChild(el('span', 'entry-method', ok ? '✓' : '✕'));
  head.appendChild(el('span', 'entry-path', 'scenario'));
  head.appendChild(el('span', 'entry-status ' + (ok ? 's2xx' : 's4xx'), ok ? 'PASS' : 'CHECK'));
  head.appendChild(el('span', 'entry-ms', ''));
  const body = el('div', 'entry-body');
  body.appendChild(el('p', null, text));
  head.addEventListener('click', () => row.classList.toggle('open'));
  row.appendChild(head);
  row.appendChild(body);
  wire.prepend(row);
}

/* ── The API client ───────────────────────────────────────────────────────── */

async function call(method, path, options = {}) {
  const { body, auth = true, redirect } = options;
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && state.token) headers['Authorization'] = 'Bearer ' + state.token;

  const started = performance.now();
  const result = { method, path, requestBody: body, ms: 0, headers: {} };

  try {
    const response = await fetch(path, {
      method,
      headers,
      redirect,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    result.ms = Math.round(performance.now() - started);

    // A 302 fetched with redirect:'manual' arrives as an opaque response: status 0, no
    // headers. Worth reporting honestly rather than rendering as a failure.
    if (response.type === 'opaqueredirect') {
      result.opaque = true;
      result.status = 302;
    } else {
      result.status = response.status;
      response.headers.forEach((value, name) => { result.headers[name.toLowerCase()] = value; });
      const text = await response.text();
      if (text) {
        try { result.body = JSON.parse(text); } catch { result.body = text; }
      }
    }
  } catch (error) {
    result.ms = Math.round(performance.now() - started);
    result.error = 'Network failure: ' + error.message;
  }

  logExchange(result);
  return result;
}

/* ── Session ──────────────────────────────────────────────────────────────── */

function decodeJwt(token) {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const decode = (segment) => {
    const padded = segment.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(padded + '='.repeat((4 - padded.length % 4) % 4)));
  };
  try {
    return { header: decode(parts[0]), payload: decode(parts[1]) };
  } catch {
    return null;
  }
}

async function signIn(username) {
  const result = await call('POST', '/api/v1/auth/token', {
    auth: false,
    body: { username, password: username + '-password' },
  });

  if (result.status !== 200) {
    logVerdict('Sign-in failed. Are the demo users seeded (SHORTENER_SEED_DEMO_USERS=true)?', false);
    return;
  }

  state.token = result.body.accessToken;
  state.claims = decodeJwt(state.token);
  state.username = username;
  state.roles = result.body.roles || [];
  loadLinks();
  render();
}

function signOut() {
  state.token = null;
  state.claims = null;
  state.username = null;
  state.roles = [];
  state.links = [];
  render();
}

function renderClaims() {
  const list = $('claims');
  list.replaceChildren();
  if (!state.claims) return;

  const { header, payload } = state.claims;
  const expiry = new Date(payload.exp * 1000);
  const rows = [
    ['kid', header.kid || '—'],
    ['alg', header.alg],
    ['sub', payload.sub],
    ['jti', payload.jti || '—'],
    ['roles', (payload.roles || []).join(', ')],
    ['expires', expiry.toLocaleTimeString()],
  ];
  for (const [key, value] of rows) {
    list.appendChild(el('dt', null, key));
    list.appendChild(el('dd', null, String(value)));
  }
}

/* ── Links ────────────────────────────────────────────────────────────────── */

const storageKey = () => 'shortener.links.' + state.username;

function loadLinks() {
  try {
    state.links = JSON.parse(localStorage.getItem(storageKey()) || '[]');
  } catch {
    state.links = [];
  }
}

function saveLinks() {
  try {
    localStorage.setItem(storageKey(), JSON.stringify(state.links.slice(0, 40)));
  } catch {
    // A full or disabled store must not take the console down with it.
  }
}

function rememberLink(link, reused) {
  state.links = state.links.filter((existing) => existing.code !== link.code);
  state.links.unshift({
    code: link.code,
    shortUrl: link.shortUrl,
    originalUrl: link.originalUrl,
    active: link.active,
    reused,
  });
  saveLinks();
  renderLinks();
}

function markDead(code) {
  const link = state.links.find((candidate) => candidate.code === code);
  if (link) { link.active = false; saveLinks(); renderLinks(); }
}

function renderLinks() {
  const container = $('links');
  container.replaceChildren();
  $('linkCount').textContent = state.links.length ? state.links.length + ' in this browser' : '';

  if (!state.links.length) {
    container.appendChild(el('p', 'empty', 'Nothing yet. Create a link above.'));
    return;
  }

  for (const link of state.links) {
    const card = el('div', 'link' + (link.active ? '' : ' dead'));

    const top = el('div', 'link-top');
    top.appendChild(el('span', 'link-code', link.code));
    if (!link.active) top.appendChild(el('span', 'tag tag-dead', 'retired'));
    else if (link.reused) top.appendChild(el('span', 'tag tag-reused', 'reused'));
    else top.appendChild(el('span', 'tag tag-new', 'new'));
    card.appendChild(top);

    card.appendChild(el('div', 'link-url', link.originalUrl));

    const actions = el('div', 'link-actions');

    const open = el('a', 'btn btn-sm btn-ghost', 'Open');
    open.href = '/' + link.code;
    open.target = '_blank';
    open.rel = 'noopener noreferrer';
    actions.appendChild(open);

    const clicks = el('button', 'btn btn-sm btn-ghost', 'Send 5 clicks');
    clicks.addEventListener('click', () => sendClicks(link.code, 5));
    actions.appendChild(clicks);

    const stats = el('button', 'btn btn-sm btn-ghost', 'Analytics');
    stats.addEventListener('click', () => showStats(link.code));
    actions.appendChild(stats);

    if (link.active) {
      const retire = el('button', 'btn btn-sm btn-ghost', 'Retire');
      retire.addEventListener('click', () => retire_(link.code));
      actions.appendChild(retire);
    }

    card.appendChild(actions);
    container.appendChild(card);
  }
}

async function sendClicks(code, count) {
  // redirect:'manual' keeps the console on screen. The request still reaches the server, so
  // the click is recorded exactly as a real visit would be.
  for (let i = 0; i < count; i++) {
    await call('GET', '/' + code, { auth: false, redirect: 'manual' });
  }
  logVerdict(count + ' clicks sent to /' + code
    + '. Analytics are buffered and flushed about once a second, so give it a moment.', true);
}

async function retire_(code) {
  const result = await call('DELETE', '/api/v1/links/' + code);
  if (result.status === 204) markDead(code);
}

/* ── Analytics ────────────────────────────────────────────────────────────── */

async function showStats(code) {
  const result = await call('GET', '/api/v1/links/' + code + '/stats?windowDays=7');
  if (result.status !== 200) return;

  const stats = result.body;
  $('statsCard').classList.remove('hidden');
  $('statsCode').textContent = code;
  $('statsNote').textContent = stats.accuracyNote;

  const metrics = $('metrics');
  metrics.replaceChildren();
  const cards = [
    [stats.totalClicks, 'total clicks'],
    [stats.uniqueVisitors, 'unique visitors'],
    [stats.clicksLast24Hours, 'last 24 hours'],
  ];
  for (const [value, label] of cards) {
    const card = el('div', 'metric');
    card.appendChild(el('b', null, String(value)));
    card.appendChild(el('span', null, label));
    metrics.appendChild(card);
  }

  const daily = $('daily');
  daily.replaceChildren();
  const series = stats.daily || [];
  if (!series.length) {
    daily.appendChild(el('p', 'empty', 'No clicks recorded in this window yet.'));
    return;
  }
  const peak = Math.max(...series.map((point) => point.clicks));
  for (const point of series) {
    const row = el('div', 'bar-row');
    row.appendChild(el('span', null, point.day));
    const track = el('div', 'bar-track');
    const fill = el('div', 'bar-fill');
    fill.style.width = Math.round((point.clicks / peak) * 100) + '%';
    track.appendChild(fill);
    row.appendChild(track);
    row.appendChild(el('span', null, String(point.clicks)));
    daily.appendChild(row);
  }
}

/* ── Creation ─────────────────────────────────────────────────────────────── */

async function createLink(overrides = {}) {
  const local = $('expiresAt').value;
  const payload = {
    url: overrides.url !== undefined ? overrides.url : $('url').value.trim(),
    alias: overrides.alias !== undefined ? overrides.alias : ($('alias').value.trim() || null),
    // datetime-local yields a naive local time; the API takes an instant.
    expiresAt: overrides.expiresAt !== undefined
      ? overrides.expiresAt
      : (local ? new Date(local).toISOString() : null),
    forceNew: overrides.forceNew !== undefined ? overrides.forceNew : $('forceNew').checked,
  };

  const result = await call('POST', '/api/v1/links', { body: payload });
  if (result.status === 200 || result.status === 201) {
    rememberLink(result.body, result.status === 200);
  }
  return result;
}

/* ── Scenarios ────────────────────────────────────────────────────────────── */

const unique = () => 'https://example.com/demo-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);

const scenarios = {
  async dedup() {
    const url = unique();
    const first = await createLink({ url, alias: null, expiresAt: null, forceNew: false });
    const second = await createLink({ url, alias: null, expiresAt: null, forceNew: false });

    const same = first.body && second.body && first.body.code === second.body.code;
    logVerdict(
      `First call ${first.status}, second call ${second.status}, codes ${same ? 'identical' : 'different'}. `
      + 'The second submission returned the existing link rather than minting a new one, so a '
      + 'client that retried after a timeout can tell from the status whether its first attempt landed.',
      first.status === 201 && second.status === 200 && same);
  },

  async blocked() {
    const result = await createLink({
      url: 'https://malware-demo.example/payload',
      alias: null, expiresAt: null, forceNew: false,
    });
    logVerdict(
      result.status === 422
        ? 'Refused with 422. The response names no check and no feed — saying which one caught it '
          + 'would turn screening into an oracle for tuning the next attempt. Nothing was written: '
          + 'no row, no code, no sequence value consumed. Worth knowing before you run this '
          + 'repeatedly: enough refusals in an hour suspends the account and revokes its tokens, '
          + 'after which sign-in fails. Restarting the app restores the demo accounts.'
        : `Expected 422 but got ${result.status}. Is the demo blocklist seeded?`,
      result.status === 422);
  },

  async retired() {
    const created = await createLink({ url: unique(), alias: null, expiresAt: null, forceNew: false });
    if (created.status !== 201) return logVerdict('Could not create a link to retire.', false);

    const code = created.body.code;
    await call('DELETE', '/api/v1/links/' + code);
    markDead(code);
    const visit = await call('GET', '/' + code, { auth: false });

    logVerdict(
      visit.status === 404
        ? 'A retired link answers 404, not 403 and not 410. A 403 would confirm the code exists, '
          + 'which is the one bit an enumeration attacker cannot otherwise obtain.'
        : `Expected 404 but got ${visit.status}.`,
      visit.status === 404);
  },

  async quarantine() {
    const host = 'turns-hostile-' + Date.now() + '.example';
    const created = await createLink({
      url: 'https://' + host + '/landing', alias: null, expiresAt: null, forceNew: false,
    });
    if (created.status !== 201) return logVerdict('Could not create the link.', false);
    const code = created.body.code;

    // Passes screening at creation, then the destination is blocked — the exact sequence a
    // create-time check alone would never catch.
    await call('GET', '/' + code, { auth: false, redirect: 'manual' });
    await call('POST', '/api/v1/admin/blocked-domains', { body: { domain: host, reason: 'console demo' } });
    await call('POST', '/api/v1/admin/rescan?all=true');
    const visit = await call('GET', '/' + code, { auth: false });
    if (visit.status === 410) markDead(code);

    logVerdict(
      visit.status === 410
        ? 'The link redirected normally, then its destination was blocked and the sweep quarantined it. '
          + 'It now answers 410, not 404: someone who followed a link that was later taken down is not '
          + 'the adversary, and telling them so is the difference between a warning and a broken site.'
        : `Expected 410 but got ${visit.status}.`,
      visit.status === 410);
  },

  async ratelimit() {
    const attempts = [];
    for (let i = 0; i < 26; i++) {
      attempts.push(call('POST', '/api/v1/links', {
        body: { url: unique(), alias: null, expiresAt: null, forceNew: true },
      }));
    }
    const results = await Promise.all(attempts);
    const limited = results.filter((result) => result.status === 429);

    logVerdict(
      limited.length
        ? `${limited.length} of 26 rapid creations were refused with 429 and a Retry-After header. `
          + 'The bucket is keyed on the authenticated principal, not the client address, so a pool '
          + 'of IPs does not widen it and shared NAT does not narrow it.'
        : 'None were refused. That is the documented fail-open behaviour: with Redis unreachable the '
          + 'limiter allows everything rather than blocking creation. Start Redis to see the 429.',
      limited.length > 0);
  },

  async revoked() {
    const revoke = await call('POST', '/api/v1/auth/revoke');
    if (revoke.status === 503) {
      return logVerdict(
        'The revocation store is unavailable, so the endpoint answered 503 rather than reporting a '
        + 'success it cannot deliver — telling someone a leaked token is dead while it still works '
        + 'is worse than admitting the failure. Start Redis to complete this scenario.', true);
    }
    const after = await call('GET', '/api/v1/links/' + (state.links[0] ? state.links[0].code : 'abc'));
    logVerdict(
      after.status === 401
        ? 'The token was withdrawn and the very next call was refused with 401, well before the token '
          + 'would have expired on its own. Sign in again to continue.'
        : `Expected 401 after revocation but got ${after.status}.`,
      after.status === 401);
    if (after.status === 401) signOut();
  },
};

/* ── Administration ───────────────────────────────────────────────────────── */

async function refreshAudit() {
  const result = await call('GET', '/api/v1/admin/audit?size=12');
  const container = $('audit');
  container.replaceChildren();
  if (result.status !== 200 || !Array.isArray(result.body)) return;

  if (!result.body.length) {
    container.appendChild(el('p', 'empty', 'No audit events yet.'));
    return;
  }
  for (const event of result.body) {
    const row = el('div', 'audit-row ' + (event.outcome || '').toLowerCase());
    const left = el('div');
    left.appendChild(el('b', null, event.action));
    left.appendChild(el('span', null, ' ' + (event.targetId || '')));
    row.appendChild(left);
    row.appendChild(el('span', 'audit-meta',
      event.actor + ' · ' + new Date(event.occurredAt).toLocaleTimeString()));
    container.appendChild(row);
  }
}

/* ── Rendering ────────────────────────────────────────────────────────────── */

function render() {
  const signedIn = Boolean(state.token);
  const isAdmin = state.roles.includes('ADMIN');

  $('loginRow').classList.toggle('hidden', signedIn);
  $('sessionDetail').classList.toggle('hidden', !signedIn);
  $('adminCard').classList.toggle('hidden', !isAdmin);
  $('createBtn').disabled = !signedIn;
  if (!signedIn) $('statsCard').classList.add('hidden');

  const badge = $('whoBadge');
  badge.textContent = signedIn ? state.username + (isAdmin ? ' · administrator' : '') : 'Not signed in';
  badge.className = 'pill ' + (!signedIn ? 'pill-idle' : isAdmin ? 'pill-admin' : 'pill-user');

  for (const button of document.querySelectorAll('.scenario')) {
    button.disabled = !signedIn || (button.hasAttribute('data-admin') && !isAdmin);
  }

  renderClaims();
  renderLinks();
}

/* ── Wiring ───────────────────────────────────────────────────────────────── */

for (const button of document.querySelectorAll('[data-login]')) {
  button.addEventListener('click', () => signIn(button.dataset.login));
}

$('signOutBtn').addEventListener('click', signOut);

$('revokeBtn').addEventListener('click', async () => {
  const result = await call('POST', '/api/v1/auth/revoke');
  if (result.status === 200) { logVerdict('Token withdrawn. Further calls with it will be refused.', true); signOut(); }
});

$('revokeAllBtn').addEventListener('click', async () => {
  const result = await call('POST', '/api/v1/auth/revoke-all');
  if (result.status === 200) { logVerdict('Every token for this principal was withdrawn.', true); signOut(); }
});

$('createForm').addEventListener('submit', (event) => {
  event.preventDefault();
  createLink();
});

$('closeStats').addEventListener('click', () => $('statsCard').classList.add('hidden'));
$('clearWire').addEventListener('click', () => {
  $('wire').replaceChildren(el('p', 'empty', 'No requests yet.'));
});

for (const button of document.querySelectorAll('.scenario')) {
  button.addEventListener('click', async () => {
    button.classList.add('running');
    button.disabled = true;
    try {
      await scenarios[button.dataset.scenario]();
    } finally {
      button.classList.remove('running');
      render();
    }
  });
}

$('blockBtn').addEventListener('click', async () => {
  const domain = $('blockDomain').value.trim();
  if (!domain) return;
  const result = await call('POST', '/api/v1/admin/blocked-domains', { body: { domain, reason: 'blocked from console' } });
  if (result.status === 201) { $('blockDomain').value = ''; refreshAudit(); }
});

$('rescanBtn').addEventListener('click', async () => {
  const result = await call('POST', '/api/v1/admin/rescan?all=true');
  if (result.status === 200) {
    logVerdict('Sweep complete: ' + result.body.quarantined + ' link(s) quarantined.', true);
    refreshAudit();
  }
});

$('auditBtn').addEventListener('click', refreshAudit);

render();
