import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import worker from '../../backend/push/retired-worker.mjs';
test('retired default Worker cannot deliver over HTTP or stale cron',async()=>{
 let calls=0;const before=global.fetch;global.fetch=async()=>{calls++;throw Error('Unexpected network');};
 try{assert.equal((await worker.fetch(new Request('https://retired.invalid/health'))).status,200);assert.equal((await worker.fetch(new Request('https://retired.invalid/wake',{method:'POST'}))).status,410);await worker.scheduled({}, {}, {waitUntil(){throw Error('Unexpected background work');}});assert.equal(calls,0);}finally{global.fetch=before;}
});
test('ordinary Cloudflare configuration and legacy setup cannot resurrect sender credentials',()=>{
 const config=fs.readFileSync(new URL('../../backend/push/cloudflare.config.ts',import.meta.url),'utf8');assert.match(config,/retired-worker/);assert.match(config,/triggers: \[\]/);assert.doesNotMatch(config,/GOOGLE_SERVICE_ACCOUNT|FIREBASE_PROJECT|SPACE_ID/);
 const result=spawnSync(process.execPath,[fileURLToPath(new URL('../../backend/push/setup-account.cjs',import.meta.url))],{encoding:'utf8'});assert.notEqual(result.status,0);assert.match(result.stderr,/Cloudflare push setup retired/);
});
