import test,{before,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import path from 'node:path';
const require=createRequire(new URL('../../backend/functions/package.json',import.meta.url));
const {getFirestore,Timestamp}=require('firebase-admin/firestore');
const {getMessaging}=require('firebase-admin/messaging');
const {getStorage}=require('firebase-admin/storage');
const {deleteApp,getApp}=require('firebase-admin/app');
process.env.GCLOUD_PROJECT='demo-shared-organizer';
process.env.FIRESTORE_EMULATOR_HOST=`127.0.0.1:${process.env.KOTIKI_FIRESTORE_PORT||8080}`;
process.env.CHAT_ATTACHMENTS_BUCKET='demo-shared-organizer.appspot.com';
const functions=await import('../../backend/functions/index.mjs');
const db=getFirestore(), objects=new Map(), sent=[];
let failNext=false;
const fakeBucket={file:(name)=>({
  getSignedUrl:async options=>{assert.equal(options.version,'v4');return [`https://test.invalid/${name}`];},
  getMetadata:async()=>{if(!objects.has(name))throw Object.assign(new Error('missing'),{code:404});return [objects.get(name)];},
  delete:async(options={})=>{if(options.ifGenerationMatch&&objects.has(name)&&String(objects.get(name).generation)!==String(options.ifGenerationMatch))throw Object.assign(new Error('generation changed'),{code:412});objects.delete(name);}
})};
getStorage().bucket=()=>fakeBucket;
getMessaging().send=async payload=>{if(failNext){failNext=false;throw Object.assign(new Error('temporary'),{code:'messaging/server-unavailable'});}sent.push(payload);return 'mock-fcm';};
const base='spaces/home';
beforeEach(async()=>{
 await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/demo-shared-organizer/databases/(default)/documents`,{method:'DELETE'});
 objects.clear();sent.length=0;failNext=false;
 await db.doc(base).set({uids:['one','two'],roles:{one:'USER_1',two:'USER_2'}});
 await Promise.all(['one','two'].map(uid=>db.doc('members/'+uid).set({spaceId:'home'})));
 await db.doc(base+'/chats/general').set({type:'general',collectionId:'',itemId:'',createdBy:'one'});
 await db.doc('system/pushDelivery').set({enabled:true,cutoverAt:Timestamp.fromMillis(Date.now()-10000)});
});
after(async()=>{await db.terminate();await deleteApp(getApp());});
const call=(name,data,uid='one')=>functions[name].run({data,auth:{uid,token:{}},rawRequest:{}});
const draft=()=>({spaceId:'home',chatId:'general',messageId:'message',files:[{id:'file',name:'photo.jpg',mime:'image/jpeg',size:100,thumbnailSize:10,thumbnailMime:'image/webp'}]});
async function uploaded(){
 const p=draft();const result=await call('prepareAttachments',p);assert.equal(result.attachments.length,1);
  const retry=await call('prepareAttachments',p);assert.equal(retry.attachments[0].id,'file');
 const storagePath=result.attachments[0].storagePath;
 objects.set(storagePath,{size:'100',contentType:'image/jpeg',generation:'1',md5Hash:'original-hash'});
 objects.set(storagePath.replace(/original$/,'thumbnail'),{size:'10',contentType:'image/webp',generation:'2'});
 return {p,result,storagePath};
}
async function publish(){const result=await uploaded();await call('commitAttachmentMessage',{...result.p,text:'',format:'markdown-v1',attachmentIds:['file']});return result;}
test('attachment prepare upload commit retry and download validate original metadata',async()=>{
 const {p,result}=await publish();assert.ok(result.attachments[0].thumbnailUploadUrl);
 assert.equal(result.attachments[0].headers['Content-Length'],'100');
 const manifest=(await db.doc(base+'/attachments/file').get()).data();assert.equal(manifest.state,'ready');assert.equal(manifest.md5Hash,'original-hash');
 assert.equal((await call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']})).alreadySent,true);
 const download=await call('attachmentDownload',{spaceId:'home',attachmentId:'file'},'two');assert.equal(download.size,100);assert.ok(download.expiresAtMillis<=Date.now()+60000);
 const thumb=await call('attachmentDownload',{spaceId:'home',attachmentId:'file',thumbnail:true},'two');assert.equal(thumb.size,10);
 await assert.rejects(call('attachmentDownload',{spaceId:'home',attachmentId:'file'},'stranger'));
});
test('attachment commit rejects missing and mismatched uploads and another sender',async()=>{
 const {p,storagePath}=await uploaded();objects.set(storagePath,{size:'101',contentType:'image/jpeg',generation:'1'});
 await assert.rejects(call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']}));
 await assert.rejects(call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']},'two'));
 assert.equal((await db.doc(base+'/chats/general/messages/message').get()).exists,false);
});
test('download rejects deleted message and expiry without deleting phone copies',async()=>{
 await publish();await db.doc(base+'/chats/general/messages/message').update({deleted:true,text:''});
 await assert.rejects(call('attachmentDownload',{spaceId:'home',attachmentId:'file'},'two'));
 await db.doc(base+'/chats/general/messages/message').update({deleted:false});await db.doc(base+'/attachments/file').update({expiresAt:Timestamp.fromMillis(1)});
 await assert.rejects(call('attachmentDownload',{spaceId:'home',attachmentId:'file'},'two'));
});
test('current parent privacy rejects file preparation and expired delegates',async()=>{
 await db.doc(base+'/chats/entry_private').set({type:'entry',collectionId:'',itemId:'private'});
 await db.doc(base+'/records/entry_private').set({type:'entry',shared:false,deleted:false,readers:['one'],data:{id:'private',visibility:'PRIVATE'}});
 await assert.rejects(call('prepareAttachments',{...draft(),chatId:'entry_private'},'two'));
 await db.doc('members/delegate').set({spaceId:'home',canonicalUid:'one',expiresAt:Timestamp.fromMillis(1)});
 await assert.rejects(call('prepareAttachments',draft(),'delegate'));
});
test('scheduled cleanup removes originals and thumbnails but keeps unavailable metadata',async()=>{
 const {storagePath}=await publish();await db.doc(base+'/attachments/file').update({cleanupAt:Timestamp.fromMillis(1)});
 await functions.cleanupAttachments.run({});assert.equal(objects.has(storagePath),false);assert.equal(objects.has(storagePath.replace(/original$/,'thumbnail')),false);
 assert.equal((await db.doc(base+'/attachments/file').get()).data().state,'expired');
});
async function seedPush(){
 const message={sender:'one',actor:'one',role:'USER_1',text:'secret-content',deleted:false,createdAt:Timestamp.now(),clientTime:Date.now()};
 await db.doc(base+'/chats/general/messages/m').set(message);
 await db.doc(base+'/devices/device-one').set({authUid:'one',canonicalUid:'one',token:'one-token'});
 await db.doc(base+'/devices/device-two').set({authUid:'two',canonicalUid:'two',token:'two-token'});
 return {params:{spaceId:'home',chatId:'general',messageId:'m'},data:{data:()=>message}};
}
test('source trigger sends once across retries and history never leaks message text',async()=>{
 const event=await seedPush();await functions.messageCreated.run(event);await functions.messageCreated.run(event);
 assert.equal(sent.length,1);assert.equal(sent[0].data.messageId,'m');assert.equal(sent[0].data.authUid,'two');assert.ok(!JSON.stringify(sent[0]).includes('secret-content'));
 const history=await db.collection(base+'/notifications/two/items').get();assert.equal(history.size,1);
 await history.docs[0].ref.update({read:true});await db.doc(base+'/devices/device-two-new').set({authUid:'two',canonicalUid:'two',token:'new-token'});
 await functions.messageCreated.run(event);assert.equal((await history.docs[0].ref.get()).data().read,true);
});
test('per-message canonical read receipt suppresses notification before send',async()=>{
 const event=await seedPush();await db.doc(base+'/chats/general/messages/m/reads/two').set({readAt:Timestamp.now(),actor:'two'});
 await functions.messageCreated.run(event);assert.equal(sent.length,0);
});
test('transient FCM failure retries only undelivered recipients',async()=>{
 const event=await seedPush();failNext=true;await assert.rejects(functions.messageCreated.run(event));assert.equal(sent.length,0);
 await functions.messageCreated.run(event);assert.equal(sent.length,1);await functions.messageCreated.run(event);assert.equal(sent.length,1);
});
test('disabled cutover and revoked recipient suppress FCM even with a saved device token',async()=>{
 const event=await seedPush();await db.doc('system/pushDelivery').update({enabled:false});await functions.messageCreated.run(event);assert.equal(sent.length,0);
 await db.doc('system/pushDelivery').update({enabled:true});await db.doc('members/two').delete();await functions.messageCreated.run(event);assert.equal(sent.length,0);
});
test('notification history exists without a registered phone',async()=>{
 const event=await seedPush();await db.doc(base+'/devices/device-two').delete();await functions.messageCreated.run(event);
 assert.equal(sent.length,0);assert.equal((await db.collection(base+'/notifications/two/items').get()).size,1);
});
test('test cohort delivers only exact delegated device auth IDs while production stays disabled',async()=>{
 const event=await seedPush();await db.doc('members/agent-two').set({spaceId:'home',canonicalUid:'two',expiresAt:Timestamp.fromMillis(Date.now()+60000)});
 await db.doc(base+'/devices/agent-device').set({authUid:'agent-two',canonicalUid:'two',token:'agent-token'});
 await db.doc('system/pushDelivery').set({enabled:false,testActorUids:['one'],testDeviceAuthUids:['agent-two'],testCutoverAt:Timestamp.fromMillis(Date.now()-10000)});
 await functions.messageCreated.run(event);assert.equal(sent.length,1);assert.equal(sent[0].token,'agent-token');
});
test('deleting a message immediately removes original and thumbnail',async()=>{
 const {storagePath}=await publish();await db.doc(base+'/chats/general/messages/message').update({deleted:true});
 await functions.attachmentMessageDeleted.run({time:new Date().toISOString(),params:{spaceId:'home',chatId:'general',messageId:'message'},data:{before:{data:()=>({deleted:false})},after:{data:()=>({deleted:true,attachmentIds:['file']})}}});
 assert.equal(objects.has(storagePath),false);assert.equal((await db.doc(base+'/attachments/file').get()).data().state,'expired');
});
test('finalization removes late objects for expired manifests',async()=>{
 const {storagePath}=await publish();await db.doc(base+'/attachments/file').update({state:'expired'});
 await functions.guardAttachmentObject.run({time:new Date().toISOString(),data:{name:storagePath,bucket:'demo-shared-organizer.appspot.com',size:'100',contentType:'image/jpeg',generation:'1'}});
 assert.equal(objects.has(storagePath),false);
});
test('thirty-day retention starts at successful send and idempotent retry never extends it',async()=>{
 const {p,storagePath}=await uploaded(),preparedAt=Date.now()-20*3600000,oldExpiry=preparedAt+30*86400000;
 await db.doc(base+'/attachments/file').update({createdAt:Timestamp.fromMillis(preparedAt),expiresAt:Timestamp.fromMillis(oldExpiry),cleanupAt:Timestamp.fromMillis(Date.now()+3600000)});
 const result=await call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']});
 const message=(await db.doc(base+'/chats/general/messages/message').get()).data(),manifest=(await db.doc(base+'/attachments/file').get()).data();
 assert.equal(manifest.sentAt.toMillis(),message.createdAt.toMillis());
 assert.equal(manifest.expiresAt.toMillis(),message.createdAt.toMillis()+30*86400000);
 assert.equal(manifest.cleanupAt.toMillis(),manifest.expiresAt.toMillis());assert.ok(result.expiresAtMillis>oldExpiry+19*3600000);
 const retry=await call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']});
 assert.equal(retry.alreadySent,true);assert.equal(retry.expiresAtMillis,result.expiresAtMillis);
 assert.equal((await db.doc(base+'/attachments/file').get()).data().expiresAt.toMillis(),result.expiresAtMillis);
 await functions.cleanupAttachments.run({});assert.ok(objects.has(storagePath));
});
test('expired abandoned draft cannot gain a fresh retention window by committing late',async()=>{
 const {p}=await uploaded();await db.doc(base+'/attachments/file').update({cleanupAt:Timestamp.fromMillis(Date.now()-1)});
 await assert.rejects(call('commitAttachmentMessage',{...p,text:'',attachmentIds:['file']}));
 assert.equal((await db.doc(base+'/chats/general/messages/message').get()).exists,false);
});
async function seedRecordPush(){
 await db.doc(base).update({names:{USER_1:'User 1',USER_2:'User 2'}});
 const record={type:'entry',owner:'one',actor:'one',shared:true,deleted:false,readers:['one','two'],revision:'revision-first',updatedAt:Timestamp.now(),data:{id:'task',title:'**Прогулка** и [ужин](https://example.test/hidden)',titleFormat:'markdown-v1',note:'private-details',visibility:'SHARED',who:'USER_2',kind:'TASK',invite:'NONE'}};
 await db.doc(base+'/records/entry_task').set(record);await db.doc(base+'/records/entry_task/revisions/'+record.revision).set(record);
 await db.doc(base+'/devices/device-two').set({authUid:'two',canonicalUid:'two',token:'two-token'});
 return {record,event:{params:{spaceId:'home',recordId:'entry_task',revision:record.revision},data:{data:()=>record}}};
}
test('authorized assignment push includes author and plain title; history retains only neutral current-target references',async()=>{
 const {event}=await seedRecordPush();await functions.recordRevisionCreated.run(event);
 assert.equal(sent.length,1);assert.equal(sent[0].data.title,'User 1: Прогулка и ужин');assert.equal(sent[0].data.kind,'event');
 assert.ok(!JSON.stringify(sent[0]).includes('private-details'));assert.ok(!JSON.stringify(sent[0]).includes('https://'));
 const rows=await db.collection(base+'/notifications/two/items').get();assert.equal(rows.size,1);const history=rows.docs[0].data();
 assert.equal(history.authorUid,'one');assert.equal(history.authorRole,'USER_1');assert.equal(history.recordId,'entry_task');assert.equal(history.targetRevision,'revision-first');
 assert.ok(!JSON.stringify(history).includes('Прогулка'));assert.ok(!JSON.stringify(history).includes('User 1'));assert.ok(!JSON.stringify(history).includes('private-details'));
 // Later revocation cannot disclose an old cached title from history, and retry cannot send it again.
 await db.doc(base+'/records/entry_task').update({readers:['one'],deleted:true});
 await functions.recordRevisionCreated.run(event);assert.equal(sent.length,1);assert.ok(!JSON.stringify((await rows.docs[0].ref.get()).data()).includes('Прогулка'));
});
test('private and busy records never produce a visible titled notification or content history',async()=>{
 const {record,event}=await seedRecordPush();
 for(const visibility of ['PRIVATE','BUSY']){
  await db.doc(base+'/records/entry_task').set({...record,shared:false,readers:['one'],data:{...record.data,visibility}});
  await functions.recordRevisionCreated.run(event);
 }
 assert.equal(sent.length,0);assert.equal((await db.collection(base+'/notifications/two/items').get()).size,0);
});

const readEvent=(uid='two')=>({time:new Date().toISOString(),params:{spaceId:'home',chatId:'general',messageId:'m',uid},data:{after:{data:()=>({readAt:Timestamp.now()})}}});
test('late canonical receipt marks exact history read without sending or duplicating',async()=>{
 const event=await seedPush();await functions.messageCreated.run(event);
 const history=(await db.collection(base+'/notifications/two/items').get()).docs[0];assert.equal(history.data().read,false);
 await db.doc(base+'/chats/general/messages/m/reads/two').set({readAt:Timestamp.now(),actor:'two'});
 await functions.messageRead.run(readEvent());await functions.messageRead.run(readEvent());
 assert.equal((await history.ref.get()).data().read,true);assert.equal(sent.length,1);
});
test('receipt before notification creation never creates an unread history entry',async()=>{
 const event=await seedPush();await db.doc(base+'/chats/general/messages/m/reads/two').set({readAt:Timestamp.now(),actor:'two'});
 await functions.messageRead.run(readEvent());await functions.messageCreated.run(event);
 assert.equal((await db.collection(base+'/notifications/two/items').get()).size,0);assert.equal(sent.length,0);
});
test('read trigger cannot fabricate history or mark a different canonical recipient',async()=>{
 await seedPush();await functions.messageRead.run(readEvent('outside'));assert.equal((await db.collection(base+'/notifications/outside/items').get()).size,0);
 const event=await seedPush();await functions.messageCreated.run(event);const h=(await db.collection(base+'/notifications/two/items').get()).docs[0];
 await functions.messageRead.run(readEvent());assert.equal((await h.ref.get()).data().read,false);
});

test('cleanup isolates one failed object and still removes other due attachments',async()=>{
 const {storagePath}=await publish();const source=(await db.doc(base+'/attachments/file').get()).data();
 await db.doc(base+'/attachments/file').update({cleanupAt:Timestamp.fromMillis(1)});
 const poisonPath=storagePath.replace('/file/','/poison/');objects.set(poisonPath,{size:'100',contentType:'image/jpeg',generation:'changed'});
 await db.doc(base+'/attachments/aaa-poison').set({...source,storagePath:poisonPath,cleanupAt:Timestamp.fromMillis(1)});
 await assert.rejects(functions.cleanupAttachments.run({}),/require retry/);
 assert.ok(objects.has(poisonPath));assert.equal(objects.has(storagePath),false);assert.equal((await db.doc(base+'/attachments/file').get()).data().state,'expired');
});
test('purge never deletes a replacement thumbnail with a different generation',async()=>{
 const {storagePath}=await publish(),thumb=storagePath.replace(/original$/,'thumbnail');objects.set(thumb,{size:'10',contentType:'image/webp',generation:'replacement'});
 await db.doc(base+'/attachments/file').update({cleanupAt:Timestamp.fromMillis(1)});
 await assert.rejects(functions.cleanupAttachments.run({}),/require retry/);assert.ok(objects.has(thumb));
});
