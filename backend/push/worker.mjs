// Cron-only delivery bridge. No client credentials or Firebase ID tokens are accepted over HTTP.
const encoder = new TextEncoder();
const MAX_FETCHES = 45, BATCH_SIZE = 3, MAX_DEVICES = 8;
const DAY = 86_400_000;
// Delivery needs routing and access metadata, never message text, event titles or notes.
const READ_FIELDS = ['uids', 'roles', 'spaceId', 'canonicalUid', 'expiresAt', 'type', 'shared', 'deleted', 'revision', 'actor', 'collectionId', 'itemId', 'data.id', 'data.visibility', 'data.who', 'data.kind', 'data.invite', 'data.isGathering', 'data.proposal.id', 'data.itemIds'];
let googleToken;
const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
const unb64 = value => Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/').padEnd(Math.ceil(value.length / 4) * 4, '=')), c => c.charCodeAt(0));
export const validId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(value);
const validTime = (value, now) => typeof value === 'string' && Number.isFinite(Date.parse(value)) && Date.parse(value) <= now + 30_000;
export class BudgetExceeded extends Error {}
export class FetchBudget {
  constructor({ limit = MAX_FETCHES, duration = 25_000, now = Date.now } = {}) { this.limit = Math.min(limit, MAX_FETCHES); this.now = now; this.deadline = now() + duration; this.used = 0; }
  check() { if (this.used >= this.limit || this.now() >= this.deadline) throw new BudgetExceeded('Delivery budget exhausted'); }
  take() { this.check(); this.used++; return Math.max(1, Math.min(8_000, this.deadline - this.now())); }
}
export function decodeValue(v) {
  if (!v || typeof v !== 'object') return null;
  if ('stringValue' in v) return v.stringValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('timestampValue' in v) return v.timestampValue;
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(decodeValue);
  if ('mapValue' in v) return Object.fromEntries(Object.entries(v.mapValue.fields || {}).map(([k, x]) => [k, decodeValue(x)]));
  return null;
}
export const documentData = doc => Object.fromEntries(Object.entries(doc.fields || {}).map(([k, v]) => [k, decodeValue(v)]));
export function pageDocuments(response) {
  return { documents: (response?.documents || []).map(d => ({ ...documentData(d), id: d.name.split('/').at(-1) })), nextPageToken: response?.nextPageToken || '' };
}
export function batchDocuments(rows, names) {
  if (!Array.isArray(rows)) throw Error('Invalid batch response');
  const allowed = new Set(names), result = new Map();
  for (const row of rows) {
    const name = row.found?.name || row.missing;
    if (!allowed.has(name) || result.has(name)) throw Error('Invalid batch response');
    result.set(name, row.found ? documentData(row.found) : null);
  }
  if (result.size !== allowed.size) throw Error('Incomplete batch response');
  return result;
}
export function activeMember(member, space, now = Date.now()) {
  if (!member || !space || !Array.isArray(space.uids) || member.spaceId !== space.id) return false;
  const canonical = member.canonicalUid || member.authUid;
  return validId(canonical) && space.uids.includes(canonical) &&
    (!member.expiresAt || (typeof member.expiresAt === 'string' && Number.isFinite(Date.parse(member.expiresAt)) && Date.parse(member.expiresAt) > now));
}
const refreshNotice = () => ({ kind: 'refresh', title: '', body: '' });
/** recipientRole is the device owner's role, not the editor's. Shared changes always invalidate stale reminders. */
export function notificationFor(record, recipientRole) {
  const e = record?.data;
  if (record?.type !== 'entry' || !record.shared || !e || e.visibility !== 'SHARED') return null;
  if (e.who !== 'BOTH' && e.who !== recipientRole) return refreshNotice();
  if (record.deleted) return { kind: 'cancel', title: 'Планы изменились', body: 'Совместная запись отменена' };
  if (e.proposal) return { kind: 'invite', title: 'Предложение котикам', body: 'Предложено другое время. Нужен ответ' };
  if (e.invite === 'PENDING') return { kind: 'invite', title: e.isGathering ? 'Собрание котиков' : 'Тебя зовут', body: 'Открой приглашение и ответь' };
  if (e.invite === 'DECLINED') return { kind: 'invite', title: 'Ответ на приглашение', body: 'Участник отказался от приглашения' };
  if (e.invite === 'ACCEPTED') return { kind: 'event', title: 'Ответ на приглашение', body: 'Встреча подтверждена' };
  return { kind: 'event', title: e.kind === 'TASK' ? 'Дела котиков' : 'Планы изменились', body: 'Обновлена запись с твоим участием' };
}
export async function fetchJson(url, init, budget, fetcher = (url, init) => fetch(url, init)) {
  const timeout = budget.take(); const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetcher(url, { ...init, redirect: 'manual', signal: controller.signal });
    if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
      await response.body?.cancel(); throw Error('Provider redirect refused');
    }
    const reader = response.body?.getReader(); const chunks = []; let length = 0;
    if (reader) {
      while (true) { const { done, value } = await reader.read(); if (done) break; length += value.length; if (length > 1_000_000) { await reader.cancel(); throw Error('Provider response too large'); } chunks.push(value); }
    }
    const bytes = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const text = new TextDecoder().decode(bytes);
    return { status: response.status, ok: response.ok, data: text ? JSON.parse(text) : null };
  } finally { clearTimeout(timer); }
}
async function accessToken(env, budget) {
  if (googleToken?.project === env.FIREBASE_PROJECT && googleToken.until > Date.now() + 60_000) return googleToken.value;
  const sa = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT);
  if (sa.project_id !== env.FIREBASE_PROJECT || typeof sa.client_email !== 'string' || typeof sa.private_key !== 'string') throw Error('Credential project mismatch');
  const now = Math.floor(Date.now() / 1000);
  const header = b64(encoder.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64(encoder.encode(JSON.stringify({ iss: sa.client_email, scope: 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 })));
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s/g, '');
  const key = await crypto.subtle.importKey('pkcs8', unb64(pem), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signed = header + '.' + claims;
  const signature = b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(signed)));
  const response = await fetchJson('https://oauth2.googleapis.com/token', { method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: signed + '.' + signature }) }, budget);
  if (!response.ok || typeof response.data?.access_token !== 'string' || !Number.isFinite(response.data.expires_in)) throw Error('Google authorization failed');
  googleToken = { project: env.FIREBASE_PROJECT, value: response.data.access_token, until: Date.now() + response.data.expires_in * 1000 };
  return googleToken.value;
}
class Store {
  constructor(env, budget) { this.env = env; this.budget = budget; this.base = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT}/databases/(default)/documents`; }
  async request(path, method = 'GET', query = '') {
    const response = await fetchJson(this.base + '/' + path + query, { method, headers: { Authorization: 'Bearer ' + await accessToken(this.env, this.budget) } }, this.budget);
    if (response.status === 404) return null;
    if (!response.ok) { if (response.status === 401) googleToken = null; throw Error('Firestore operation failed'); }
    return response.data;
  }
  async get(path) { const document = await this.request(path, 'GET', '?' + READ_FIELDS.map(field => 'mask.fieldPaths=' + encodeURIComponent(field)).join('&')); return document ? documentData(document) : null; }
  async getMany(paths) {
    const unique = [...new Set(paths)]; if (!unique.length) return new Map();
    const names = unique.map(path => this.base.slice('https://firestore.googleapis.com/v1/'.length) + '/' + path);
    const response = await fetchJson(this.base + ':batchGet', {
      method: 'POST', headers: { Authorization: 'Bearer ' + await accessToken(this.env, this.budget), 'Content-Type': 'application/json' },
      body: JSON.stringify({ documents: names, mask: { fieldPaths: READ_FIELDS } }),
    }, this.budget);
    if (!response.ok) throw Error('Firestore operation failed');
    const result = batchDocuments(response.data, names);
    return new Map(unique.map((path, index) => [path, result.get(names[index])]));
  }
  async listPage(path, size) { return pageDocuments(await this.request(path, 'GET', '?pageSize=' + size + (path.endsWith('/pushQueue') ? '&orderBy=createdAt%20asc' : ''))); }
  async remove(path) { await this.request(path, 'DELETE'); }
}
async function sendFcm(env, budget, token, data) {
  const response = await fetchJson(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT}/messages:send`, {
    method: 'POST', headers: { Authorization: 'Bearer ' + await accessToken(env, budget), 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: { token, data, android: { priority: data.kind === 'refresh' ? 'normal' : 'high', ttl: '86400s' } } }),
  }, budget);
  if (response.ok) return 'sent';
  if (response.status === 401) googleToken = null;
  if (response.data?.error?.details?.some(d => d.errorCode === 'UNREGISTERED')) return 'unregistered';
  return 'retry';
}
function validEvent(event, now) {
  return validId(event.id) && validId(event.actor) && validTime(event.createdAt, now) &&
    (event.kind === 'record' ? validId(event.recordId) && validId(event.revision) && event.id === event.revision :
      event.kind === 'chat' && validId(event.chatId) && validId(event.messageId) && event.id === event.messageId);
}

/** Dependencies are injectable for unit tests; production always uses bounded Google REST transport. */
export async function deliver(env, dependencies = {}) {
  if (!validId(env.FIREBASE_PROJECT) || !validId(env.SPACE_ID)) throw Error('Invalid project configuration');
  const budget = dependencies.budget || new FetchBudget(); const now = dependencies.now || Date.now;
  const db = dependencies.db || new Store(env, budget); const send = dependencies.send || ((token, data) => sendFcm(env, budget, token, data));
  const root = 'spaces/' + env.SPACE_ID;
  const stats = { processed: 0, sent: 0, deferred: false, fetches: 0 };
  try {
    const queuePage = await db.listPage(root + '/pushQueue', BATCH_SIZE); const queue = queuePage.documents.slice(0, BATCH_SIZE);
    if (!queue.length) return stats;
    const space = await db.get(root); if (!space) return stats; space.id = env.SPACE_ID;
    // Explicitly bounded for a two-person space. Never silently truncate recipients and delete their queue events.
    const devicesPage = await db.listPage(root + '/devices', MAX_DEVICES);
    const initialPaths = [...devicesPage.documents.map(d => d.authUid), ...queue.map(e => e.actor)].filter(validId).map(uid => 'members/' + uid);
    for (const event of queue.filter(e => validEvent(e, now()))) {
      if (event.kind === 'record') initialPaths.push(root + '/records/' + event.recordId, root + '/records/' + event.recordId + '/revisions/' + event.revision);
      else initialPaths.push(root + '/chats/' + event.chatId, root + '/chats/' + event.chatId + '/messages/' + event.messageId);
    }
    const prefetched = db.getMany ? await db.getMany(initialPaths) : new Map();
    const get = path => prefetched.has(path) ? Promise.resolve(prefetched.get(path)) : db.get(path);
    const members = new Map();
    async function member(uid) {
      if (!validId(uid)) return null;
      if (!members.has(uid)) { const value = await get('members/' + uid); members.set(uid, value ? { ...value, authUid: uid } : null); }
      return members.get(uid);
    }
    const devices = [];
    for (const device of devicesPage.documents) {
      if (!validId(device.authUid)) continue;
      const recipient = await member(device.authUid);
      if (!activeMember(recipient, space, now())) {
        const expired = typeof recipient?.expiresAt === 'string' && Number.isFinite(Date.parse(recipient.expiresAt)) && Date.parse(recipient.expiresAt) <= now();
        // get() returns null ONLY on Firestore 404; provider/network failures throw and never imply revocation.
        if (validId(device.id) && (recipient === null || expired)) await db.remove(root + '/devices/' + device.id);
      } else if ((recipient.canonicalUid || device.authUid) === device.canonicalUid) devices.push(device);
    }
    if (devicesPage.nextPageToken || devicesPage.documents.length > MAX_DEVICES) {
      // Expired delegate registrations above are removed first; a later tick can advance to the next page.
      console.error('Push device limit exceeded; queue retained for device cleanup'); stats.deferred = true; return stats;
    }
    for (const event of queue) {
      budget.check();
      if (!validId(event.id)) { stats.deferred = true; continue; }
      if (!validEvent(event, now()) || now() - Date.parse(event.createdAt) > DAY) { await db.remove(root + '/pushQueue/' + event.id); stats.processed++; continue; }
      const actor = await member(event.actor);
      if (!activeMember(actor, space, now())) { await db.remove(root + '/pushQueue/' + event.id); stats.processed++; continue; }
      const canonical = actor.canonicalUid || event.actor;
      let record, chatNotice, recordId = '', chatId = '', collectionId = '', itemId = '';
      if (event.kind === 'record') {
        const current = await get(root + '/records/' + event.recordId);
        const revision = await get(root + '/records/' + event.recordId + '/revisions/' + event.revision);
        if (current?.revision === event.revision && revision?.actor === event.actor && current.type === 'entry' && current.shared && current.data?.visibility === 'SHARED') {
          record = current; recordId = event.recordId;
        }
      } else {
        const chat = await get(root + '/chats/' + event.chatId);
        const message = await get(root + '/chats/' + event.chatId + '/messages/' + event.messageId);
        let readable = chat?.type === 'general';
        if (chat?.type === 'plan' && validId(chat.collectionId) && validId(chat.itemId)) {
          const plan = await db.get(root + '/records/plan_' + chat.collectionId);
          readable = plan?.shared && !plan.deleted && Array.isArray(plan.data?.itemIds) && plan.data.itemIds.includes(chat.itemId);
          if (readable) { recordId = 'plan_' + chat.collectionId; collectionId = chat.collectionId; itemId = chat.itemId; }
        }
        if (chat?.type === 'entry' && validId(chat.itemId)) {
          const entry = await db.get(root + '/records/entry_' + chat.itemId);
          readable = entry?.shared && !entry.deleted && entry.data?.visibility === 'SHARED';
          if (readable) { recordId = 'entry_' + chat.itemId; itemId = chat.itemId; }
        }
        if (readable && message?.actor === event.actor && !message.deleted) {
          chatNotice = { kind: 'chat', title: 'Котики на связи', body: 'Новое сообщение в чате' }; chatId = event.chatId;
        }
      }
      let failed = false;
      if (record || chatNotice) {
        for (const device of devices) {
          budget.check();
          if (!validId(device.id) || !validId(device.authUid) || !validId(device.canonicalUid) || typeof device.token !== 'string' || device.token.length < 20 || device.token.length > 4096) continue;
          const recipient = await member(device.authUid);
          if (!activeMember(recipient, space, now()) || (recipient.canonicalUid || device.authUid) !== device.canonicalUid) continue;
          const ownDevice = device.canonicalUid === canonical;
          if (chatNotice && ownDevice) continue;
          const notice = record ? (ownDevice ? refreshNotice() : notificationFor(record, space.roles?.[device.canonicalUid])) : chatNotice;
          if (!notice) continue;
          const data = { ...notice, eventId: event.id, createdAt: event.createdAt, spaceId: env.SPACE_ID, recordId, chatId, collectionId, itemId, authUid: device.authUid, gathering: record?.data?.isGathering ? "true" : "false" };
          const result = await send(device.token, data);
          if (result === 'unregistered') await db.remove(root + '/devices/' + device.id);
          else if (result !== 'sent') failed = true;
          else stats.sent++;
        }
      }
      if (failed) { stats.deferred = true; continue; }
      await db.remove(root + '/pushQueue/' + event.id); stats.processed++;
    }
    if (queuePage.nextPageToken) stats.deferred = true; // More events remain for the next cron tick.
  } catch (error) {
    if (error instanceof BudgetExceeded || error?.name === 'AbortError') stats.deferred = true;
    else throw error;
  } finally { stats.fetches = budget.used; }
  return stats;
}
export default {
  fetch(request) {
    const path = new URL(request.url).pathname;
    return path === '/health' && request.method === 'GET' ? Response.json({ ok: true }) : new Response('Not found', { status: 404 });
  },
  scheduled(_event, env, ctx) {
    ctx.waitUntil(deliver(env).then(stats => console.log(JSON.stringify({ event: 'push_delivery', ...stats }))).catch(error => {
      const known = ['Invalid project configuration', 'Credential project mismatch', 'Google authorization failed', 'Firestore operation failed', 'Provider response too large'];
      console.error(JSON.stringify({ event: 'push_delivery', error: known.includes(error?.message) ? error.message : 'provider_unavailable', errorType: error?.name, location: error?.stack?.match(/index\.js:\d+:\d+/)?.[0], bindings: { project: typeof env.FIREBASE_PROJECT, space: typeof env.SPACE_ID, credential: typeof env.GOOGLE_SERVICE_ACCOUNT } }));
    }));
  },
};
