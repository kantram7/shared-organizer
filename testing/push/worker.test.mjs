import test from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const moduleUrl = process.env.WORKER_UNDER_TEST ? pathToFileURL(process.env.WORKER_UNDER_TEST) : new URL('../../backend/push/worker.mjs', import.meta.url);
const { default: worker, deliver, notificationFor, activeMember, pageDocuments, FetchBudget, BudgetExceeded, fetchJson, batchDocuments } = await import(moduleUrl);
const NOW = Date.parse('2026-11-17T12:00:00Z');
const env = { FIREBASE_PROJECT: 'project-test', SPACE_ID: 'space-test' };
const root = 'spaces/space-test';
test('batch reads match full document names and distinguish missing from incomplete response', () => {
  const values=batchDocuments([{missing:'docs/b'}, {found:{name:'docs/a',fields:{value:{stringValue:'ok'}}}}], ['docs/a','docs/b']);
  assert.deepEqual(values.get('docs/a'),{value:'ok'});assert.equal(values.get('docs/b'),null);
  assert.throws(()=>batchDocuments([{missing:'docs/b'}],['docs/a','docs/b']),/Incomplete/);
  assert.throws(()=>batchDocuments([{missing:'docs/wrong'}],['docs/a']),/Invalid/);
  assert.throws(()=>batchDocuments([{missing:'docs/a'},{missing:'docs/a'}],['docs/a']),/Invalid/);
});
test('transport uses Workers-supported manual redirect mode and refuses redirects', async () => {
  let calls = 0;
  const transport = async (_url, init) => { calls++; assert.equal(init.redirect, 'manual'); return new Response('', {status: 302, headers:{Location:'https://untrusted.invalid'}}); };
  await assert.rejects(fetchJson('https://provider.test', {}, new FetchBudget(), transport), /redirect refused/);
  assert.equal(calls, 1);
  const result = await fetchJson('https://provider.test', {}, new FetchBudget(), async (_url, init) => { assert.equal(init.redirect, 'manual'); return Response.json({ok:true}); });
  assert.deepEqual(result.data, {ok:true});
});
const entry = (change = {}) => ({ type: 'entry', shared: true, deleted: false, revision: 'revision1', data: { id: 'entry1', visibility: 'SHARED', who: 'BOTH', kind: 'TASK', invite: 'NONE', title: 'Private title must not enter payload', ...change } });
function setup({ record = entry(), eventChange = {}, deviceNext = '', queueNext = '', limit = 45, batched = false } = {}) {
  const budget = new FetchBudget({ now: () => NOW, limit });
  const documents = new Map([
    [root, { uids: ['u1', 'u2'], roles: { u1: 'USER_1', u2: 'USER_2' } }],
    ['members/u1', { spaceId: 'space-test' }], ['members/u2', { spaceId: 'space-test' }],
    [root + '/records/entry_entry1', record], [root + '/records/entry_entry1/revisions/revision1', { actor: 'u1' }],
  ]);
  const events = [{ id: 'revision1', actor: 'u1', kind: 'record', recordId: 'entry_entry1', revision: 'revision1', createdAt: new Date(NOW).toISOString(), ...eventChange }];
  const devices = [{ id: 'u1_device000000', authUid: 'u1', canonicalUid: 'u1', token: 'test-token-user-one-long-enough' }, { id: 'u2_device000000', authUid: 'u2', canonicalUid: 'u2', token: 'test-token-user-two-long-enough' }];
  const requests = [], removed = [], sent = []; let failedGetPath = null;
  const db = {
    async get(path) { budget.take(); requests.push(path); if (path === failedGetPath) throw Error('Simulated unavailable provider'); return documents.has(path) ? structuredClone(documents.get(path)) : null; },
    async listPage(path, size) { budget.take(); requests.push(path); return path.endsWith('/pushQueue') ? { documents: structuredClone(events.slice(0, size)), nextPageToken: queueNext || (events.length > size ? 'next-queue-page' : '') } : { documents: structuredClone(devices.slice(0, size)), nextPageToken: deviceNext || (devices.length > size ? 'next-device-page' : '') }; },
    async remove(path) { budget.take(); removed.push(path); },
  };
  if (batched) db.getMany = async paths => { budget.take(); requests.push('batchGet'); return new Map([...new Set(paths)].map(path => { if (path === failedGetPath) throw Error('Simulated unavailable provider'); return [path, documents.has(path) ? structuredClone(documents.get(path)) : null]; })); };
  let resultFor = () => 'sent';
  const send = async (token, data) => { budget.take(); sent.push({ token, data }); return resultFor(token, data); };
  return { documents, events, devices, requests, removed, sent, budget, setResult: fn => { resultFor = fn; }, setGetFailure: path => { failedGetPath = path; }, run: () => deliver(env, { db, send, budget, now: () => NOW }) };
}

test('HTTP exposes only health, never wake or ID-token authorization', async () => {
  assert.equal((await worker.fetch(new Request('https://worker.test/health'))).status, 200);
  assert.equal((await worker.fetch(new Request('https://worker.test/wake', { method: 'POST', headers: { Authorization: 'Bearer fake-token' } }))).status, 404);
  assert.equal((await worker.fetch(new Request('https://worker.test/health', { method: 'POST' }))).status, 404);
});
test('private records never produce notices; unrelated shared recipient gets silent refresh', () => {
  assert.equal(notificationFor({ ...entry(), shared: false }, 'USER_2'), null);
  assert.equal(notificationFor(entry({ visibility: 'PRIVATE' }), 'USER_2'), null);
  assert.deepEqual(notificationFor(entry({ who: 'USER_1' }), 'USER_2'), { kind: 'refresh', title: '', body: '' });
  assert.equal(notificationFor(entry({ who: 'USER_2' }), 'USER_2').kind, 'event');
});
test('member expiry and canonical allowlist are enforced', () => {
  const space = { id: 'space-test', uids: ['u1'] };
  assert.equal(activeMember({ authUid: 'delegate', canonicalUid: 'u1', spaceId: space.id, expiresAt: new Date(NOW + 1000).toISOString() }, space, NOW), true);
  assert.equal(activeMember({ authUid: 'delegate', canonicalUid: 'other', spaceId: space.id }, space, NOW), false);
  assert.equal(activeMember({ authUid: 'u1', spaceId: space.id, expiresAt: 'invalid' }, space, NOW), false);
  assert.equal(activeMember({ authUid: 'u1', spaceId: space.id, expiresAt: new Date(NOW - 1).toISOString() }, space, NOW), false);
});
test('document fields cannot override the real path ID', () => {
  const page = pageDocuments({ documents: [{ name: root + '/devices/safe-id', fields: { id: { stringValue: '../../bad' } } }], nextPageToken: 'next' });
  assert.equal(page.documents[0].id, 'safe-id'); assert.equal(page.nextPageToken, 'next');
});
test('record change refreshes author devices and notifies other participant without event content', async () => {
  const state = setup(); const result = await state.run();
  assert.equal(result.processed, 1); assert.equal(state.sent.length, 2);
  assert.equal(state.sent.find(x => x.data.authUid === 'u1').data.kind, 'refresh');
  const other = state.sent.find(x => x.data.authUid === 'u2').data;
  assert.equal(other.kind, 'event'); assert.equal(other.recordId, 'entry_entry1'); assert.equal(other.itemId, ''); assert.equal(other.collectionId, ''); assert.equal(other.chatId, '');
  assert.equal(other.createdAt, new Date(NOW).toISOString()); assert.equal(other.eventId, 'revision1'); assert.ok(!JSON.stringify(other).includes('Private title'));
  assert.deepEqual(state.removed, [root + '/pushQueue/revision1']);
});
test('batched reads preserve recipient checks and stop safely on provider failure', async () => {
  const normal=setup(),batch=setup({batched:true}); await normal.run(); await batch.run();
  assert.deepEqual(batch.sent,normal.sent); assert.deepEqual(batch.removed,normal.removed);
  assert.ok(batch.budget.used<normal.budget.used);
  const expired=setup({batched:true}); expired.documents.set('members/u2',{spaceId:'space-test',expiresAt:new Date(NOW-1).toISOString()});
  await expired.run(); assert.ok(expired.sent.every(x=>x.data.authUid==='u1'));
  const failed=setup({batched:true}); failed.setGetFailure('members/u2');
  await assert.rejects(failed.run(),/unavailable/);assert.equal(failed.sent.length,0);assert.equal(failed.removed.length,0);
});
test('who changed to editor still sends silent invalidation to former recipient', async () => {
  const state = setup({ record: entry({ who: 'USER_1' }) }); await state.run();
  assert.equal(state.sent.length, 2); assert.ok(state.sent.every(x => x.data.kind === 'refresh' && !x.data.title && !x.data.body));
});
test('private and superseded events are removed without pushes', async () => {
  for (const record of [{ ...entry(), shared: false }, { ...entry(), revision: 'newer' }]) {
    const state = setup({ record }); await state.run(); assert.equal(state.sent.length, 0); assert.equal(state.removed.length, 1);
  }
});
test('deleted shared record notifies cancellation; no private body leaks', async () => {
  const state = setup({ record: { ...entry(), deleted: true } }); await state.run();
  assert.equal(state.sent.find(x => x.data.authUid === 'u2').data.kind, 'cancel');
});
test('expired recipient and forged canonical binding cannot receive payloads', async () => {
  const state = setup(); state.documents.set('members/u2', { spaceId: 'space-test', expiresAt: new Date(NOW - 1).toISOString() });
  state.devices.push({ id: 'u2_forged000000', authUid: 'u2', canonicalUid: 'u1', token: 'another-test-token-long-enough' });
  await state.run(); assert.ok(state.sent.every(x => x.data.authUid === 'u1'));
});
test('invalid timestamps and path traversal are rejected before referenced document fetch', async () => {
  for (const eventChange of [{ recordId: '../../members/u2' }, { createdAt: 'invalid' }, { createdAt: new Date(NOW + 60_000).toISOString() }]) {
    const state = setup({ eventChange }); await state.run();
    assert.equal(state.sent.length, 0); assert.ok(!state.requests.some(path => path.includes('..'))); assert.equal(state.removed.length, 1);
  }
});
test('entry chat has correct deep-link record ID and never notifies sender devices', async () => {
  const state = setup({ eventChange: { id: 'message1', kind: 'chat', chatId: 'entry_chat', messageId: 'message1' } });
  state.documents.set(root + '/chats/entry_chat', { type: 'entry', itemId: 'entry1' });
  state.documents.set(root + '/chats/entry_chat/messages/message1', { actor: 'u1', deleted: false, text: 'Do not include message text' });
  await state.run(); assert.equal(state.sent.length, 1);
  assert.equal(state.sent[0].data.recordId, 'entry_entry1'); assert.equal(state.sent[0].data.chatId, 'entry_chat'); assert.equal(state.sent[0].data.itemId, 'entry1');
  assert.equal(state.sent[0].data.authUid, 'u2'); assert.ok(!JSON.stringify(state.sent[0]).includes('Do not include'));
});
test('plan chat deep link retains collection and item IDs', async () => {
  const state = setup({ eventChange: { id: 'message1', kind: 'chat', chatId: 'plan_chat', messageId: 'message1' } });
  state.documents.set(root + '/chats/plan_chat', { type: 'plan', collectionId: 'group1', itemId: 'item1' });
  state.documents.set(root + '/chats/plan_chat/messages/message1', { actor: 'u1', deleted: false });
  state.documents.set(root + '/records/plan_group1', { shared: true, deleted: false, data: { itemIds: ['item1'] } });
  await state.run(); assert.equal(state.sent.length, 1);
  assert.equal(state.sent[0].data.collectionId, 'group1'); assert.equal(state.sent[0].data.itemId, 'item1');
  assert.equal(state.sent[0].data.recordId, 'plan_group1');
});
test('device pagination never silently drops unsent recipients or deletes queue', async () => {
  const state = setup({ deviceNext: 'next-devices' }); const result = await state.run();
  assert.equal(result.deferred, true); assert.equal(state.sent.length, 0); assert.equal(state.removed.length, 0);
});
test('queue processes at most three events and leaves the next page for cron', async () => {
  const state = setup(); state.events.splice(0);
  for (let i = 1; i <= 4; i++) state.events.push({ id: 'invalid' + i, actor: 'u1', createdAt: 'invalid', kind: 'record' });
  const result = await state.run(); assert.equal(result.processed, 3); assert.equal(result.deferred, true); assert.equal(state.removed.length, 3);
});
test('partial FCM failure keeps queue for retry with stable event ID', async () => {
  const state = setup(); state.setResult((_token, data) => data.authUid === 'u2' ? 'retry' : 'sent');
  const result = await state.run(); assert.equal(result.deferred, true); assert.equal(state.removed.length, 0); assert.ok(state.sent.every(x => x.data.eventId === 'revision1'));
});
test('unregistered token removes the actual device and permits queue completion', async () => {
  const state = setup(); state.setResult((_token, data) => data.authUid === 'u2' ? 'unregistered' : 'sent');
  await state.run(); assert.ok(state.removed.includes(root + '/devices/u2_device000000')); assert.ok(state.removed.includes(root + '/pushQueue/revision1'));
});
test('budget ceiling and deadline stop work with queue retained', async () => {
  const state = setup({ limit: 5 }); const result = await state.run();
  assert.equal(result.deferred, true); assert.equal(state.budget.used, 5); assert.equal(state.removed.length, 0);
  const capped = new FetchBudget({ limit: 999 }); for (let i = 0; i < 45; i++) capped.take();
  assert.throws(() => capped.take(), BudgetExceeded); assert.equal(capped.used, 45);
  let clock = 0; const timed = new FetchBudget({ now: () => clock, duration: 10 }); clock = 10; assert.throws(() => timed.take(), BudgetExceeded);
});

test('expired or missing members are cleaned before pagination guard, queue retained', async () => {
  for (const missing of [false, true]) {
    const state = setup({ deviceNext: 'next-devices' });
    if (missing) state.documents.delete('members/u2');
    else state.documents.set('members/u2', { spaceId: 'space-test', expiresAt: new Date(NOW - 1).toISOString() });
    const result = await state.run();
    assert.equal(result.deferred, true);
    assert.deepEqual(state.removed, [root + '/devices/u2_device000000']);
    assert.equal(state.sent.length, 0);
  }
});
test('network failure or malformed membership is never treated as expired registration', async () => {
  const unavailable = setup(); unavailable.setGetFailure('members/u2');
  await assert.rejects(unavailable.run(), /unavailable provider/); assert.equal(unavailable.removed.length, 0);
  const malformed = setup({ deviceNext: 'next-devices' });
  malformed.documents.set('members/u2', { spaceId: 'space-test', expiresAt: 'malformed' });
  await malformed.run(); assert.equal(malformed.removed.length, 0);
});

test('gathering metadata selects the dedicated Android invitation channel without event content',async()=>{
 const state=setup({record:entry({isGathering:true,invite:'PENDING'})});await state.run();
 const notice=state.sent.find(x=>x.data.authUid==='u2').data;
 assert.equal(notice.kind,'invite');assert.equal(notice.gathering,'true');
 assert.ok(!JSON.stringify(notice).includes('Private title'));
});
