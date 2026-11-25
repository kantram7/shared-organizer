import test from 'node:test';
import assert from 'node:assert/strict';
import {LIMITS,principal,chatAllowed,validateFiles,validateMessage,recordNotice,enrichedRecordNotice,plainNotificationText,eligibleTime,claimable,stableId} from '../../backend/functions/core.mjs';
const now=1_800_000_000_000;
const team={uids:['one','two'],roles:{one:'USER_1',two:'USER_2'}};
const record={type:'entry',shared:true,deleted:false,readers:['one','two'],data:{id:'entry',visibility:'SHARED',who:'BOTH',kind:'TASK',invite:'NONE',title:'PRIVATE TITLE',note:'PRIVATE NOTE'}};
test('canonical members require live membership and active space allowlist',()=>{
  assert.equal(principal({spaceId:'home'},team,'one','home',now).uid,'one');
  for(const m of [null,{spaceId:'else'},{spaceId:'home',expiresAt:now-1}])assert.equal(principal(m,team,'one','home',now),null);
  assert.equal(principal({spaceId:'home'},team,'outsider','home',now),null);
});
test('anonymous role delegation is explicit bounded and canonical',()=>{
  assert.equal(principal({spaceId:'home',canonicalUid:'two',expiresAt:now+1},team,'anonymous','home',now).role,'USER_2');
  for(const m of [{spaceId:'home'},{spaceId:'home',canonicalUid:'two'},{spaceId:'home',canonicalUid:'two',expiresAt:now},{spaceId:'home',canonicalUid:'other',expiresAt:now+1}])assert.equal(principal(m,team,'anonymous','home',now),null);
});
test('chat privacy checks current parent, removed plan item and forged chat identifiers',()=>{
  const chat={type:'entry',collectionId:'',itemId:'entry'};
  assert.equal(chatAllowed('entry_entry',chat,record,'two'),true);
  for(const p of [null,{...record,deleted:true},{...record,shared:false},{...record,readers:['one']},{...record,data:{...record.data,visibility:'BUSY'}}])assert.equal(chatAllowed('entry_entry',chat,p,'two'),false);
  assert.equal(chatAllowed('entry_wrong',chat,record,'two'),false);
  const plan={...record,type:'plan',data:{itemIds:['item']}},pc={type:'plan',collectionId:'group',itemId:'item'};
  assert.equal(chatAllowed('plan_group_item',pc,plan,'two'),true);
  assert.equal(chatAllowed('plan_group_item',pc,{...plan,data:{itemIds:[]}},'two'),false);
  assert.equal(chatAllowed('general',{type:'general',collectionId:'',itemId:''},null,'one'),true);
  assert.equal(chatAllowed('general',{type:'entry',collectionId:'',itemId:''},null,'one'),false);
});
const file=(i=1,size=100)=>({id:`file-${i}`,name:'picture.jpg',mime:'image/jpeg',size});
test('attachments preserve descriptors without compression and validate optional thumbnails',()=>{
  const files=[{...file(),thumbnailSize:123,thumbnailMime:'image/webp'}];assert.deepEqual(validateFiles(files),files);
  assert.deepEqual(validateFiles(Array.from({length:10},(_,i)=>file(i))),Array.from({length:10},(_,i)=>file(i)));
});
test('attachment byte boundaries include exact100MiB and exact500MiB',()=>{
  assert.equal(validateFiles(Array.from({length:5},(_,i)=>file(i,LIMITS.fileBytes))).length,5);
  assert.throws(()=>validateFiles([file(1,LIMITS.fileBytes+1)]));
  assert.throws(()=>validateFiles(Array.from({length:6},(_,i)=>file(i,LIMITS.fileBytes))));
});
test('reject empty, duplicate, traversal, malformed MIME, invalid size and oversized thumbnails',()=>{
  for(const files of [[],Array.from({length:11},(_,i)=>file(i)),[file(),file()], [{...file(),id:'../x'}],[{...file(),name:'../x'}],[{...file(),mime:'text/plain;bad'}],[file(1,-1)],[file(1,1.5)],[{...file(),thumbnailSize:524289,thumbnailMime:'image/jpeg'}],[{...file(),thumbnailSize:10,thumbnailMime:'text/html'}]])assert.throws(()=>validateFiles(files));
});
test('message descriptors allow an empty caption but no oversized or forged identifiers',()=>{
  const input={spaceId:'home',chatId:'general',messageId:'id',text:'',format:'markdown-v1',replyTo:'reply'};assert.doesNotThrow(()=>validateMessage(input));
  for(const p of [{...input,spaceId:'../x'},{...input,text:'x'.repeat(4001)},{...input,replyTo:'../m'},{...input,format:'html'}])assert.throws(()=>validateMessage(p));
});
test('notifications never include private record text and distinguish invitation states',()=>{
  for(const d of [{},{invite:'PENDING'},{invite:'PENDING',isGathering:true},{invite:'DECLINED'},{invite:'ACCEPTED'},{proposal:{id:'p'}}]){
    const n=recordNotice({...record,data:{...record.data,...d}},'USER_2');assert.ok(n.title);assert.ok(!JSON.stringify(n).includes('PRIVATE'));
  }
  assert.equal(recordNotice({...record,shared:false},'USER_2'),null);
  assert.equal(recordNotice({...record,data:{...record.data,visibility:'BUSY'}},'USER_2'),null);
  assert.equal(recordNotice({...record,data:{...record.data,who:'USER_1'}},'USER_2').kind,'refresh');
});
test('disabled/unconfigured cutover and old source events never deliver',()=>{
  const config={enabled:true,cutoverAt:now-1000};assert.equal(eligibleTime(now,config,now),true);
  for(const c of [undefined,{}, {...config,enabled:false},{enabled:true}])assert.equal(eligibleTime(now,c,now),false);
  assert.equal(eligibleTime(now-1001,config,now),false);
  assert.equal(eligibleTime(now-86400001,{...config,cutoverAt:0},now),false);
  assert.equal(eligibleTime(now+120000,config,now),false);
});
test('delivery ledger deduplicates completed outcomes and permits expired leases/retries',()=>{
  for(const state of ['sent','suppressed','expired'])assert.equal(claimable({state},now),false);
  assert.equal(claimable({state:'sending',leaseUntil:now+1},now),false);
  for(const ledger of [undefined,{state:'retry'},{state:'sending',leaseUntil:now-1}])assert.equal(claimable(ledger,now),true);
});
test('stable event ID is same across retries and unique across source paths',()=>{
  assert.equal(stableId('home','chat','general','message'),stableId('home','chat','general','message'));
  assert.notEqual(stableId('home','chat','general','message'),stableId('home','chat','other','message'));
  assert.notEqual(stableId('ab','c'),stableId('a','bc'));
});
test('record push enrichment is plain text and preserves a neutral history title',()=>{
 const r={...record,data:{...record.data,title:'**Прогулка** и [ужин](https://example.test/private)',titleFormat:'markdown-v1'}};
 const n=enrichedRecordNotice(recordNotice(r,'USER_2'),r,'User 1');
 assert.equal(n.title,'User 1: Прогулка и ужин');assert.equal(n.historyTitle,'Дела котиков');
 assert.ok(!JSON.stringify(n).includes('https://'));assert.equal(enrichedRecordNotice(null,r,'User 1'),null);
 assert.deepEqual(enrichedRecordNotice({kind:'refresh',title:'',body:''},r,'User 1'),{kind:'refresh',title:'',body:''});
 assert.equal(plainNotificationText('one\n\u202etwo'),'one two');
 assert.equal(plainNotificationText('check_backups'),'check_backups');
});
