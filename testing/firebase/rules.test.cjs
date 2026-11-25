const {createRequire}=require('node:module');
const path=require('node:path');
const fs=require('node:fs');
const {test,before,after,beforeEach}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const dep=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'));
const {initializeTestEnvironment,assertSucceeds,assertFails}=dep('@firebase/rules-unit-testing');
const {doc,setDoc,getDoc,getDocs,collection,query,where,writeBatch,deleteDoc,serverTimestamp,disableNetwork,enableNetwork}=dep('firebase/firestore');
let env;
const uids=['user-one','user-two'];
const space={uids,roles:{'user-one':'USER_1','user-two':'USER_2'},names:{USER_1:'User 1',USER_2:'User 2'}};
const root='spaces/home';
const ref=(db,id)=>doc(db,root+'/records/'+id);
const db=(uid)=>env.authenticatedContext(uid).firestore();
function entry(id,changes={}){return {id,title:'Plan',date:'2026-11-17',week:'2026-11-16',lastDate:'2026-11-17',start:'19:00',end:'20:00',kind:'EVENT',who:'BOTH',owner:'USER_1',visibility:'SHARED',editable:true,done:false,note:'Private details',invite:'NONE',incoming:false,resolvedDay:'',isGathering:false,...changes};}
function envelope(data,actor='user-one',changes={}){return {type:'entry',owner:'user-one',readers:data.visibility==='SHARED'?uids:['user-one'],shared:data.visibility==='SHARED',data,deleted:false,revision:crypto.randomUUID(),updatedAt:serverTimestamp(),actor,...changes};}
function projection(e){return !e.deleted && e.data.visibility==='BUSY'?{owner:e.owner,revision:e.revision,active:true,...Object.fromEntries(['date','lastDate','week','start','end','who','kind'].map(k=>[k,e.data[k]]))}:{owner:e.owner,revision:e.revision,active:false};}
function write(client,id,e,{history=true,busy=true}={}){const b=writeBatch(client);b.set(ref(client,id),e);if(history)b.set(doc(client,root+'/records/'+id+'/revisions/'+e.revision),e);if(busy&&e.type==='entry')b.set(doc(client,root+'/busy/'+id),projection(e));return b.commit();}
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-shared-organizer',firestore:{host:'127.0.0.1',port:Number(process.env.KOTIKI_FIRESTORE_PORT||8080),rules:fs.readFileSync(path.resolve(process.env.KOTIKI_RULES_FILE || 'backend/firestore.rules'),'utf8')}});});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async c=>{await setDoc(doc(c.firestore(),root),space);for(const uid of uids)await setDoc(doc(c.firestore(),'members/'+uid),{spaceId:'home'});});});
after(async()=>{await env?.cleanup();});
test('non-members and anonymous clients cannot read or write organizer data',async()=>{const a=db('user-one');await write(a,'one',envelope(entry('one')));for(const c of [db('outsider'),env.unauthenticatedContext().firestore()]){await assertFails(getDoc(ref(c,'one')));await assertFails(write(c,'other',envelope(entry('other'),'outsider')));await assertFails(getDoc(doc(c,root)));}});
test('users cannot grant themselves membership or impersonate the other owner',async()=>{await assertFails(setDoc(doc(db('outsider'),'members/outsider'),{spaceId:'home'}));await assertFails(setDoc(doc(db('user-one'),root),{...space,uids:[...uids,'outsider']}));await assertFails(write(db('user-two'),'one',envelope(entry('one'),'user-two')));});
test('shared records synchronize to second authorized client',async()=>{await assertSucceeds(write(db('user-one'),'one',envelope(entry('one'))));const r=await assertSucceeds(getDoc(ref(db('user-two'),'one')));assert.equal(r.data().data.title,'Plan');const q=query(collection(db('user-two'),root+'/records'),where('readers','array-contains','user-two'));assert.equal((await assertSucceeds(getDocs(q))).size,1);});
test('private record and its history are unreadable by other participant',async()=>{const e=envelope(entry('secret',{visibility:'PRIVATE'}));await write(db('user-one'),'secret',e);await assertFails(getDoc(ref(db('user-two'),'secret')));await assertFails(getDoc(doc(db('user-two'),root+'/records/secret/revisions/'+e.revision)));await assertFails(getDoc(doc(db('user-two'),root+'/busy/secret')));});
test('busy projection contains schedule only, not title or comments',async()=>{const e=envelope(entry('busy',{visibility:'BUSY'}));await write(db('user-one'),'busy',e);const p=(await assertSucceeds(getDoc(doc(db('user-two'),root+'/busy/busy')))).data();assert.equal(p.active,true);assert.equal(p.start,'19:00');assert.equal(p.title,undefined);assert.equal(p.note,undefined);await assertFails(getDoc(ref(db('user-two'),'busy')));});
test('visibility is fixed at creation for every role and access mode',async()=>{
 const a=db('user-one');
 for(const visibility of ['SHARED','PRIVATE','BUSY']){
  const id='fixed-'+visibility;await write(a,id,envelope(entry(id,{visibility})));
  for(const next of ['SHARED','PRIVATE','BUSY'].filter(v=>v!==visibility))await assertFails(write(a,id,envelope(entry(id,{visibility:next}))));
  await assertSucceeds(write(a,id,envelope(entry(id,{visibility,title:'Changed content'}))));
 }
});
test('read-only shared records reject edits by other participant',async()=>{await write(db('user-one'),'one',envelope(entry('one',{editable:false})));await assertFails(write(db('user-two'),'one',envelope(entry('one',{editable:false,title:'Changed'}),'user-two')));});
test('collaborator can edit content but not access flags or owner',async()=>{const a=db('user-one'),b=db('user-two');await write(a,'one',envelope(entry('one')));await assertSucceeds(write(b,'one',envelope(entry('one',{title:'Changed'}),'user-two')));await assertFails(write(b,'one',envelope(entry('one',{editable:false}),'user-two')));await assertFails(write(b,'one',envelope(entry('one',{visibility:'PRIVATE'}),'user-two')));});
test('recipient can accept a read-only invitation, without modifying its time',async()=>{const a=db('user-one'),b=db('user-two'),e=entry('invite',{invite:'PENDING',editable:false});await write(a,'invite',envelope(e));await assertFails(write(b,'invite',envelope({...e,invite:'ACCEPTED',start:'18:00'},'user-two')));await assertSucceeds(write(b,'invite',envelope({...e,invite:'ACCEPTED',resolvedDay:'2026-11-17'},'user-two')));await assertFails(write(b,'invite',envelope({...e,invite:'PENDING'},'user-two')));});
test('author cannot accept own invitation or silently move an accepted one',async()=>{const a=db('user-one'),b=db('user-two'),e=entry('invite',{invite:'PENDING'});await write(a,'invite',envelope(e));await assertFails(write(a,'invite',envelope({...e,invite:'ACCEPTED'})));await write(b,'invite',envelope({...e,invite:'ACCEPTED'},'user-two'));await assertFails(write(a,'invite',envelope({...e,invite:'ACCEPTED',start:'18:00'})));});
test('record writes require matching history and busy projection atomically',async()=>{const a=db('user-one');await assertFails(write(a,'one',envelope(entry('one')),{history:false}));await assertFails(write(a,'one',envelope(entry('one')),{busy:false}));});
test('history cannot be edited, deleted or forged without corresponding record',async()=>{const a=db('user-one'),e=envelope(entry('one'));await write(a,'one',e);const h=doc(a,root+'/records/one/revisions/'+e.revision);await assertFails(setDoc(h,{...e,data:entry('one',{title:'Forgery'})}));await assertFails(setDoc(doc(a,root+'/records/one/revisions/fake-long-revision'),{...e,revision:'fake-long-revision'}));});
test('deletion is a tombstone with history; restore retains both versions',async()=>{const a=db('user-one'),e=entry('one');await write(a,'one',envelope(e));await write(a,'one',envelope(e,'user-one',{deleted:true}));assert.equal((await getDoc(ref(a,'one'))).data().deleted,true);await write(a,'one',envelope(e));const versions=await getDocs(query(collection(a,root+'/records/one/revisions'),where('readers','array-contains','user-one')));assert.equal(versions.size,3);});
test('both conflicting revisions survive; last server commit becomes current',async()=>{const a=db('user-one'),b=db('user-two');await write(a,'one',envelope(entry('one')));await disableNetwork(a);const pending=write(a,'one',envelope(entry('one',{title:'Offline edit'})));await write(b,'one',envelope(entry('one',{title:'Online edit'}),'user-two'));await enableNetwork(a);await pending;assert.equal((await getDoc(ref(b,'one'))).data().data.title,'Offline edit');const history=await getDocs(query(collection(b,root+'/records/one/revisions'),where('readers','array-contains','user-two')));assert.equal(history.size,3);});
test('legacy private lists remain readable only by owner but cannot be recreated',async()=>{const a=db('user-one'),b=db('user-two');const c={id:'list1',name:'List',shared:false,owner:'USER_1',updatedAt:1,items:[]};const e=envelope(c,'user-one',{type:'list',shared:false,readers:['user-one']});await assertFails(write(a,'list1',e));await seedLegacy('list1',e);await assertFails(getDoc(ref(b,'list1')));await assertFails(write(a,'plan1',{...e,type:'plan',revision:crypto.randomUUID()}));});
test('member removal denies further cloud reads and writes',async()=>{const a=db('user-one');await write(a,'one',envelope(entry('one')));await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),root),{...space,uids:['user-two']}));await assertFails(getDoc(ref(a,'one')));await assertFails(write(a,'one',envelope(entry('one',{title:'Rejected'}))));});

const {Timestamp}=dep('firebase/firestore');
async function delegate(uid,canonicalUid,expiresAt=Timestamp.fromMillis(Date.now()+3600000)){
 await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'members/'+uid),{spaceId:'home',canonicalUid,expiresAt}));
}
async function chat(client,id='general',collectionId='',itemId=''){
 await setDoc(doc(client,root+'/chats/'+id),{type:id==='general'?'general':'plan',collectionId,itemId,createdBy:'user-one',createdAt:serverTimestamp()});
}
function message(sender='user-one',actor=sender){return {sender,actor,role:sender==='user-one'?'USER_1':'USER_2',text:'Hello from tests',createdAt:serverTimestamp(),clientTime:Date.now(),deleted:false};}
const msgRef=(client,id='m1',chatId='general')=>doc(client,root+'/chats/'+chatId+'/messages/'+id);
test('anonymous delegates inherit only the configured role and preserve actual actor',async()=>{
 await delegate('anon-one','user-one');await delegate('anon-two','user-two');
 const a=db('anon-one'),b=db('anon-two');const e=envelope(entry('private-delegate',{visibility:'PRIVATE'}),'anon-one');
 await assertSucceeds(write(a,'private-delegate',e));
 await assertSucceeds(getDoc(ref(db('user-one'),'private-delegate')));
 await assertFails(getDoc(ref(b,'private-delegate')));
 assert.equal((await getDoc(ref(a,'private-delegate'))).data().actor,'anon-one');
 await assertFails(setDoc(doc(a,'members/anon-one'),{spaceId:'home',canonicalUid:'user-two'}));
});
test('delegation without expiry denies reads and writes',async()=>{
 await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'members/no-expiry'),{spaceId:'home',canonicalUid:'user-one'}));
 await write(db('user-one'),'existing',envelope(entry('existing')));
 const client=db('no-expiry');
 await assertFails(getDoc(doc(client,root)));
 await assertFails(getDoc(ref(client,'existing')));
 await assertFails(write(client,'no-expiry',envelope(entry('no-expiry'),'no-expiry')));
});
test('delegation requires an explicit timestamp expiry',async()=>{
 await delegate('invalid-expiry','user-one',Date.now()+3600000);
 const client=db('invalid-expiry');
 await assertFails(getDoc(doc(client,root)));
 await assertFails(write(client,'invalid-expiry',envelope(entry('invalid-expiry'),'invalid-expiry')));
});
test('unexpired delegation still allows canonical reads and writes',async()=>{
 await delegate('valid-expiry','user-one');const client=db('valid-expiry');
 await assertSucceeds(getDoc(doc(client,root)));
 await assertSucceeds(write(client,'valid-expiry',envelope(entry('valid-expiry'),'valid-expiry')));
 await assertSucceeds(getDoc(ref(client,'valid-expiry')));
});
test('canonical legacy membership needs no expiry even with explicit canonicalUid',async()=>{
 await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),'members/user-one'),{spaceId:'home',canonicalUid:'user-one'}));
 const client=db('user-one');
 await assertSucceeds(getDoc(doc(client,root)));
 await assertSucceeds(write(client,'canonical-legacy',envelope(entry('canonical-legacy'))));
 await assertSucceeds(getDoc(ref(client,'canonical-legacy')));
});
test('expired delegation denies reads and writes',async()=>{
 await delegate('expired','user-one',Timestamp.fromMillis(Date.now()-10000));
 await assertFails(getDoc(doc(db('expired'),root)));
 await assertFails(write(db('expired'),'expired',envelope(entry('expired'),'expired')));
});
test('general chat synchronizes messages and rejects unknown senders',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 assert.equal((await assertSucceeds(getDoc(msgRef(b)))).data().text,'Hello from tests');
 await assertFails(getDoc(msgRef(db('outsider'))));
 await assertFails(setDoc(msgRef(b,'spoof'),message('user-one','user-two')));
 await assertFails(setDoc(msgRef(a,'wrong-role'),{...message(),role:'USER_2'}));
 await assertFails(setDoc(msgRef(a,'long'),{...message(),text:'x'.repeat(4001)}));
});
test('message deletion is limited to its role and cannot rewrite history',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 const saved=(await getDoc(msgRef(a))).data();
 await assertFails(setDoc(msgRef(b),{...saved,text:'',deleted:true}));
 await assertSucceeds(setDoc(msgRef(a),{...saved,text:'Edited'}));
 await delegate('anon-one','user-one');
 await assertSucceeds(setDoc(msgRef(db('anon-one')),{...saved,text:'',deleted:true}));
});
test('a delegated message retains canonical sender and actual authenticated actor',async()=>{
 await delegate('anon-two','user-two');const a=db('user-one'),b=db('anon-two');await chat(a);
 await assertSucceeds(setDoc(msgRef(b),message('user-two','anon-two')));
 const saved=(await getDoc(msgRef(a))).data();assert.equal(saved.sender,'user-two');assert.equal(saved.actor,'anon-two');
});
test('individual plan chats require an active matching plan item',async()=>{
 const a=db('user-one'),b=db('user-two');
 const data={id:'theme',name:'Theme',shared:true,owner:'USER_1',updatedAt:1,items:[{id:'item',title:'Plan',checked:false}],itemIds:['item']};
 await write(a,'plan_theme',envelope(data,'user-one',{type:'plan',shared:true,readers:uids}));
 await chat(a,'plan_theme_item','theme','item');await setDoc(msgRef(a,'m1','plan_theme_item'),message());
 await assertSucceeds(getDoc(msgRef(b,'m1','plan_theme_item')));
 await assertFails(chat(a,'plan_theme_missing','theme','missing'));
 await write(a,'plan_theme',envelope({...data,items:[],itemIds:[]},'user-one',{type:'plan',shared:true,readers:uids}));
 await assertFails(getDoc(msgRef(b,'m1','plan_theme_item')));
 await assertFails(setDoc(msgRef(a,'m2','plan_theme_item'),message()));
});
test('owner can undo deletion of an accepted invitation without changing its details',async()=>{
 const a=db('user-one'),b=db('user-two'),e=entry('accepted',{invite:'PENDING'});await write(a,'accepted',envelope(e));
 const accepted={...e,invite:'ACCEPTED'};await write(b,'accepted',envelope(accepted,'user-two'));
 await write(a,'accepted',envelope(accepted,'user-one',{deleted:true}));
 await assertSucceeds(write(a,'accepted',envelope(accepted)));
 await assertFails(write(a,'accepted',envelope({...accepted,start:'18:00'})));
});

function proposal(id='offer-one',proposedBy='USER_2',changes={}){
 return {id,proposedBy,title:'Moved meeting',date:'2026-12-03',lastDate:'2026-12-03',start:'20:00',end:'21:00',note:'A counterproposal',...changes};
}
function proposalResponse(base,accept=true){
 const {proposal:p,...rest}=base;
 return {...rest,...(accept?{title:p.title,date:p.date,lastDate:p.lastDate,week:'2026-11-30',start:p.start,end:p.end,note:p.note,invite:'ACCEPTED',resolvedDay:'2026-11-18'}:{}),proposalResponseId:p.id};
}
async function acceptedMeeting(id='meeting'){
 const a=db('user-one'),b=db('user-two');
 const pending=entry(id,{invite:'PENDING',editable:false});
 await write(a,id,envelope(pending));
 const accepted={...pending,invite:'ACCEPTED',resolvedDay:'2026-11-17'};
 await write(b,id,envelope(accepted,'user-two'));
 return {a,b,accepted};
}

test('read-only accepted meeting can receive a proposal without moving agreed time',async()=>{
 const {a,b,accepted}=await acceptedMeeting();
 const offered={...accepted,proposal:proposal()};
 await assertSucceeds(write(b,'meeting',envelope(offered,'user-two')));
 const saved=(await getDoc(ref(a,'meeting'))).data().data;
 assert.equal(saved.date,accepted.date);assert.equal(saved.start,accepted.start);assert.equal(saved.invite,'ACCEPTED');
 await assertFails(write(b,'meeting',envelope({...proposalResponse(offered),proposalResponseId:'offer-one'},'user-two')));
 await assertSucceeds(write(a,'meeting',envelope(proposalResponse(offered))));
 const moved=(await getDoc(ref(b,'meeting'))).data().data;
 assert.equal(moved.date,'2026-12-03');assert.equal(moved.start,'20:00');assert.equal(moved.proposalResponseId,'offer-one');
});

test('rejecting a proposal preserves the accepted meeting and its resolution date',async()=>{
 const {a,b,accepted}=await acceptedMeeting();const offered={...accepted,proposal:proposal()};
 await write(b,'meeting',envelope(offered,'user-two'));
 await assertFails(write(a,'meeting',envelope({...proposalResponse(offered,false),invite:'DECLINED'})));
 await assertSucceeds(write(a,'meeting',envelope(proposalResponse(offered,false))));
 const saved=(await getDoc(ref(b,'meeting'))).data().data;
 assert.deepEqual(saved,{...accepted,proposalResponseId:'offer-one'});
});

test('counter-counterproposal requires the other participant and cannot accept stale version',async()=>{
 const {a,b,accepted}=await acceptedMeeting();
 const first={...accepted,proposal:proposal()};await write(b,'meeting',envelope(first,'user-two'));
 const second={...accepted,proposal:proposal('offer-two','USER_1')};await write(a,'meeting',envelope(second));
 await assertFails(write(a,'meeting',envelope(proposalResponse(second))));
 // Same date/time is intentional: only the response marker distinguishes these versions.
 await assertFails(write(b,'meeting',envelope({...proposalResponse(second),proposalResponseId:'offer-one'},'user-two')));
 await assertFails(write(b,'meeting',envelope({...proposalResponse(second),proposalResponseId:''},'user-two')));
 await assertSucceeds(write(b,'meeting',envelope(proposalResponse(second),'user-two')));
 await assertFails(write(b,'meeting',envelope(proposalResponse(second),'user-two')));
});

test('proposal cannot spoof its author, change access, reuse id or bypass confirmation',async()=>{
 const {a,b,accepted}=await acceptedMeeting();
 await assertFails(write(b,'meeting',envelope({...accepted,proposal:proposal('spoof','USER_1')},'user-two')));
 await assertFails(write(b,'meeting',envelope({...accepted,editable:true,proposal:proposal()},'user-two')));
 await assertFails(write(a,'meeting',envelope({...accepted,start:'18:00',proposal:proposal('own','USER_1')})));
 const offered={...accepted,proposal:proposal()};await write(b,'meeting',envelope(offered,'user-two'));
 await assertFails(write(b,'meeting',envelope({...offered,proposal:proposal('offer-one','USER_2',{start:'19:30'})},'user-two')));
 await assertFails(write(a,'meeting',envelope({...proposalResponse(offered),start:'18:00'})));
 await assertFails(write(a,'meeting',envelope({...proposalResponse(offered),owner:'USER_2'})));
});

test('pending invitation counterproposal is answered by original author; rejecting does not decline invitation',async()=>{
 const a=db('user-one'),b=db('user-two'),pending=entry('pending',{invite:'PENDING'});
 await write(a,'pending',envelope(pending));
 const offered={...pending,proposal:proposal()};await write(b,'pending',envelope(offered,'user-two'));
 await assertFails(write(b,'pending',envelope({...pending,invite:'ACCEPTED'},'user-two')));
 await assertSucceeds(write(a,'pending',envelope(proposalResponse(offered,false))));
 const rejected=proposalResponse(offered,false);
 assert.equal((await getDoc(ref(b,'pending'))).data().data.invite,'PENDING');
 await assertSucceeds(write(b,'pending',envelope({...rejected,invite:'ACCEPTED',resolvedDay:'2026-11-18'},'user-two')));
});

test('invalid proposal fields and pre-answered creation are denied',async()=>{
 const {b,accepted}=await acceptedMeeting();
 for(const invalid of [proposal('bad','BOTH'),proposal('bad','USER_2',{start:'29:00'}),proposal('bad','USER_2',{end:'19:00'}),proposal('bad','USER_2',{date:''}),proposal('bad','USER_2',{title:''})]){
  await assertFails(write(b,'meeting',envelope({...accepted,proposal:invalid},'user-two')));
 }
 await assertFails(write(db('user-one'),'new',envelope(entry('new',{invite:'PENDING',proposal:proposal('new','USER_1')}))));
 await assertFails(write(db('user-one'),'new2',envelope(entry('new2',{invite:'PENDING',proposalResponseId:'forged'}))));
});

async function entryChat(client,itemId){
 return setDoc(doc(client,root+'/chats/entry_'+itemId),{type:'entry',collectionId:'',itemId,createdBy:'user-one',createdAt:serverTimestamp()});
}

test('entry chat allows both members only while matching parent is active and shared',async()=>{
 const a=db('user-one'),b=db('user-two');const data=entry('talk');
 await write(a,'entry_talk',envelope(data));
 await assertSucceeds(entryChat(a,'talk'));
 await setDoc(msgRef(a,'message','entry_talk'),message());
 await assertSucceeds(getDoc(doc(b,root+'/chats/entry_talk')));
 await assertSucceeds(getDoc(msgRef(b,'message','entry_talk')));
 await assertSucceeds(setDoc(msgRef(b,'reply','entry_talk'),message('user-two')));
 await assertFails(entryChat(a,'missing'));
 await assertFails(getDoc(msgRef(db('outsider'),'message','entry_talk')));
});

test('entry chat remains shared when an attempted visibility change is rejected',async()=>{
 const a=db('user-one'),b=db('user-two');const data=entry('fixed-chat');
 await write(a,'entry_fixed-chat',envelope(data));await entryChat(a,'fixed-chat');
 await setDoc(msgRef(a,'message','entry_fixed-chat'),message());
 await assertFails(write(a,'entry_fixed-chat',envelope({...data,visibility:'PRIVATE'})));
 await assertSucceeds(getDoc(msgRef(b,'message','entry_fixed-chat')));
});

test('deleted entry and busy-only entry cannot expose or accept chat messages',async()=>{
 const a=db('user-one'),b=db('user-two');const data=entry('deleted-chat');
 await write(a,'entry_deleted-chat',envelope(data));await entryChat(a,'deleted-chat');
 await setDoc(msgRef(a,'message','entry_deleted-chat'),message());
 await write(a,'entry_deleted-chat',envelope(data,'user-one',{deleted:true}));
 await assertFails(getDoc(doc(b,root+'/chats/entry_deleted-chat')));
 await assertFails(getDoc(msgRef(b,'message','entry_deleted-chat')));
 await assertFails(setDoc(msgRef(a,'later','entry_deleted-chat'),message()));
 await write(a,'entry_busy-chat',envelope(entry('busy-chat',{visibility:'BUSY'})));
 await assertFails(entryChat(a,'busy-chat'));
});

test('entry-chat metadata cannot alias another document type or use a nonempty collection',async()=>{
 const a=db('user-one');
 const data={id:'fake',name:'List',shared:true,owner:'USER_1',updatedAt:1,items:[]};
 await seedLegacy('entry_fake',envelope(data,'user-one',{type:'list',shared:true,readers:uids}));
 await assertFails(entryChat(a,'fake'));
 await write(a,'entry_real',envelope(entry('real')));
 await assertFails(setDoc(doc(a,root+'/chats/entry_real'),{type:'entry',collectionId:'wrong',itemId:'real',createdBy:'user-one',createdAt:serverTimestamp()}));
 await assertFails(setDoc(doc(a,root+'/chats/entry_alias'),{type:'entry',collectionId:'',itemId:'real',createdBy:'user-one',createdAt:serverTimestamp()}));
});

test('list visibility cannot change either direction even for its owner',async()=>{
 const a=db('user-one');
 for(const shared of [true,false]){
  const id=shared?'fixed-shared':'fixed-private';
  const data={id,name:'Fixed visibility',shared,owner:'USER_1',updatedAt:1,items:[],itemIds:[]};
  await seedLegacy(id,envelope(data,'user-one',{type:'list',shared,readers:shared?uids:['user-one']}));
  await assertFails(write(a,id,envelope({...data,shared:!shared},'user-one',{type:'list',shared:!shared,readers:shared?['user-one']:uids})));
  await assertFails(write(a,id,envelope({...data,name:'Renamed'},'user-one',{type:'list',shared,readers:shared?uids:['user-one']})));
 }
});

const deviceRef=(client,uid='user-one')=>doc(client,root+'/devices/'+uid+'_installation-123456');
const device=(uid='user-one',canonicalUid=uid)=>({authUid:uid,canonicalUid,token:'synthetic-test-token-1234567890',updatedAt:serverTimestamp()});
test('devices are writable and readable only by their actual authenticated owner',async()=>{
 const a=db('user-one'),b=db('user-two');
 await assertSucceeds(setDoc(deviceRef(a),device()));
 await assertSucceeds(getDoc(deviceRef(a)));
 await assertFails(getDoc(deviceRef(b)));
 await assertFails(setDoc(deviceRef(b),device('user-two')));
 await assertFails(deleteDoc(deviceRef(b)));
 await assertFails(setDoc(deviceRef(a),device('user-one','user-two')));
 await delegate('anon-one','user-one');
 const delegated=db('anon-one');
 await assertSucceeds(setDoc(deviceRef(delegated,'anon-one'),device('anon-one','user-one')));
 await assertFails(getDoc(deviceRef(a,'anon-one')));
 await assertFails(getDoc(deviceRef(delegated)));
 await assertSucceeds(deleteDoc(deviceRef(delegated,'anon-one')));
 await assertSucceeds(deleteDoc(deviceRef(a)));
});

const queueRef=(client,id)=>doc(client,root+'/pushQueue/'+id);
function recordQueue(id,e){return {kind:'record',recordId:id,revision:e.revision,chatId:'',messageId:'',actor:e.actor,createdAt:serverTimestamp()};}
function writeAndQueue(client,id,e,queueChanges={}){
 const batch=writeBatch(client);
 batch.set(ref(client,id),e).set(doc(client,root+'/records/'+id+'/revisions/'+e.revision),e)
  .set(doc(client,root+'/busy/'+id),projection(e)).set(queueRef(client,e.revision),{...recordQueue(id,e),...queueChanges});
 return batch.commit();
}
test('record pushQueue requires the actual atomically-written revision and cannot be read or rewritten',async()=>{
 const a=db('user-one'),b=db('user-two');const e=envelope(entry('queued'));
 await assertSucceeds(writeAndQueue(a,'queued',e));
 await assertFails(getDoc(queueRef(a,e.revision)));
 await assertFails(getDoc(queueRef(b,e.revision)));
 await assertFails(setDoc(queueRef(a,e.revision),recordQueue('queued',e)));
 await assertFails(deleteDoc(queueRef(a,e.revision)));
 const next=envelope(entry('queued',{title:'Updated'}));
 await assertFails(writeAndQueue(a,'queued',next,{actor:'user-two'}));
 await assertFails(writeAndQueue(a,'queued',next,{revision:e.revision}));
 await assertFails(writeAndQueue(a,'queued',next,{recordId:'unrelated'}));
 await assertSucceeds(writeAndQueue(a,'queued',next));
});

test('standalone and spoofed record pushQueue cannot manufacture changes',async()=>{
 const a=db('user-one'),b=db('user-two');const e=envelope(entry('unqueued'));
 await write(a,'unqueued',e);
 await assertFails(setDoc(queueRef(a,e.revision),recordQueue('unqueued',e)));
 await assertFails(setDoc(queueRef(b,e.revision),{...recordQueue('unqueued',e),actor:'user-two'}));
 await assertFails(setDoc(queueRef(a,'made-up'),{...recordQueue('unqueued',e),revision:'made-up'}));
});

test('chat pushQueue requires the actual message in the same atomic batch',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);
 const id='queued-message';const event={kind:'chat',recordId:'',revision:'',chatId:'general',messageId:id,actor:'user-one',createdAt:serverTimestamp()};
 await assertFails(setDoc(queueRef(a,id),event));
 const batch=writeBatch(a);batch.set(msgRef(a,id),message()).set(queueRef(a,id),event);
 await assertSucceeds(batch.commit());
 await assertFails(getDoc(queueRef(b,id)));
 const wrong=writeBatch(b);wrong.set(msgRef(b,'spoof-queue'),message('user-two'))
  .set(queueRef(b,'spoof-queue'),{...event,messageId:'spoof-queue',actor:'user-one'});
 await assertFails(wrong.commit());
 const legitimate=writeBatch(b);legitimate.set(msgRef(b,'reply-queue'),message('user-two'))
  .set(queueRef(b,'reply-queue'),{...event,messageId:'reply-queue',actor:'user-two'});
 await assertSucceeds(legitimate.commit());
});

test('collaborator cannot delete or restore a shared editable entry',async()=>{
 const a=db('user-one'),b=db('user-two'),data=entry('owner-delete');
 await write(a,'owner-delete',envelope(data));
 await assertFails(write(b,'owner-delete',envelope(data,'user-two',{deleted:true})));
 await assertSucceeds(write(a,'owner-delete',envelope(data,'user-one',{deleted:true})));
 await assertFails(write(b,'owner-delete',envelope(data,'user-two')));
 await assertSucceeds(write(a,'owner-delete',envelope(data)));
});

test('collaborator cannot delete or restore a shared plan',async()=>{
 const a=db('user-one'),b=db('user-two');
 const data={id:'owner-list',name:'Shared list',shared:true,owner:'USER_1',updatedAt:1,items:[],itemIds:[]};
 const wrap=(actor,deleted=false)=>envelope(data,actor,{type:'plan',shared:true,readers:uids,deleted});
 await write(a,'owner-list',wrap('user-one'));
 await assertFails(write(b,'owner-list',wrap('user-two',true)));
 await assertSucceeds(write(a,'owner-list',wrap('user-one',true)));
 await assertFails(write(b,'owner-list',wrap('user-two')));
 await assertSucceeds(write(a,'owner-list',wrap('user-one')));
});

test('logout is idempotent for missing own device but cannot delete another device path',async()=>{
 const a=db('user-one'),b=db('user-two');
 await assertSucceeds(deleteDoc(deviceRef(a)));
 await assertFails(deleteDoc(deviceRef(b)));
 await assertFails(deleteDoc(deviceRef(db('outsider'),'outsider')));
 await delegate('anon-one','user-one');
 const delegated=db('anon-one');
 await assertSucceeds(deleteDoc(deviceRef(delegated,'anon-one')));
 await assertFails(deleteDoc(deviceRef(a,'anon-one')));
 await assertFails(deleteDoc(deviceRef(delegated)));
});

test('participant can change own display name but not roles or partner name',async()=>{
 const a=db('user-one');
 await assertSucceeds(setDoc(doc(a,root),{...space,names:{...space.names,USER_1:'New display name'}}));
 await assertFails(setDoc(doc(a,root),{...space,names:{...space.names,USER_2:'Impersonated'}}));
 await assertFails(setDoc(doc(a,root),{...space,roles:{'user-one':'USER_2','user-two':'USER_1'}}));
});
function ng(id,extra={}){return {id,name:'Shared notes',shared:true,owner:'USER_1',editable:true,archived:false,updatedAt:1,...extra};}
function nt(id,groupId,extra={}){return {id,groupId,title:'Note',blocks:[{id:'b',kind:'CHECK',text:'Coffee',checked:false}],archived:false,updatedAt:1,revision:crypto.randomUUID(),...extra};}
test('notes only accept shared groups and reject outsiders and private payloads',async()=>{
 const a=db('user-one'),g=doc(a,root+'/noteGroups/g');
 await assertFails(setDoc(g,ng('g',{shared:false})));
 await assertSucceeds(setDoc(g,ng('g')));
 await assertSucceeds(setDoc(doc(a,root+'/notes/n'),nt('n','g')));
 await assertFails(getDoc(doc(db('outsider'),root+'/notes/n')));
 await assertFails(setDoc(doc(a,root+'/notes/bad'),nt('bad','missing')));
 await assertFails(setDoc(doc(a,root+'/notes/n/revisions/v'),{text:'history must not exist'}));
 await assertSucceeds(getDocs(collection(db('user-two'),root+'/notes')));
});
test('either participant may retire a shared note and stale offline creates cannot resurrect it',async()=>{
 const a=db('user-one'),b=db('user-two');await setDoc(doc(a,root+'/noteGroups/g'),ng('g'));
 const data=nt('n','g');await setDoc(doc(a,root+'/notes/n'),data);
 await assertFails(deleteDoc(doc(b,root+'/notes/n')));
 const batch=writeBatch(b);batch.set(doc(b,root+'/retiredNotes/n'),{retired:true});batch.delete(doc(b,root+'/notes/n'));await assertSucceeds(batch.commit());
 assert.equal((await getDoc(doc(a,root+'/notes/n'))).exists(),false);
 await assertFails(setDoc(doc(a,root+'/notes/n'),data));
 await assertFails(setDoc(doc(a,root+'/retiredNotes/another'),{retired:true}));
});
test('readonly note content is protected while moving is allowed to the partner',async()=>{
 const a=db('user-one'),b=db('user-two');await setDoc(doc(a,root+'/noteGroups/g'),ng('g',{editable:false}));await setDoc(doc(a,root+'/noteGroups/h'),ng('h'));
 const n=nt('n','g');await setDoc(doc(a,root+'/notes/n'),n);
 await assertFails(setDoc(doc(b,root+'/notes/n'),{...n,title:'No'}));
 await assertSucceeds(setDoc(doc(b,root+'/notes/n'),{...n,groupId:'h',revision:crypto.randomUUID()}));
 await assertSucceeds(setDoc(doc(b,root+'/notes/n'),{...n,groupId:'h',archived:true}));
});
test('messages may be edited only by their author and deleted messages cannot be revived',async()=>{
 const a=db('user-one'),b=db('user-two');const c=root+'/chats/general';
 await setDoc(doc(a,c),{type:'general',collectionId:'',itemId:'',createdBy:'user-one',createdAt:serverTimestamp()});
 const m={sender:'user-one',actor:'user-one',role:'USER_1',text:'Hello',createdAt:serverTimestamp(),clientTime:1,deleted:false};
 await setDoc(doc(a,c+'/messages/n'),m);const existing=(await getDoc(doc(a,c+'/messages/n'))).data();
 await assertSucceeds(setDoc(doc(a,c+'/messages/n'),{...existing,text:'Edited'}));
 await assertFails(setDoc(doc(b,c+'/messages/n'),{...existing,text:'Spoofed'}));
 await assertSucceeds(setDoc(doc(a,c+'/messages/n'),{...existing,text:'',deleted:true}));
 await assertFails(setDoc(doc(a,c+'/messages/n'),{...existing,text:'Revived'}));
});

async function seedLegacy(id,e){await env.withSecurityRulesDisabled(async c=>{await setDoc(ref(c.firestore(),id),e);});}

test('036 reads are per canonical user and cannot impersonate the other participant',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 const read=(c,uid)=>doc(c,root+'/chats/general/messages/m1/reads/'+uid);
 await assertSucceeds(setDoc(read(b,'user-two'),{readAt:serverTimestamp(),actor:'user-two'}));
 await assertSucceeds(getDoc(read(a,'user-two')));
 await assertFails(setDoc(read(a,'user-two'),{readAt:serverTimestamp(),actor:'user-one'}));
 await assertFails(setDoc(read(b,'user-two'),{readAt:Timestamp.fromMillis(1),actor:'user-two'}));
 await delegate('anon-two','user-two');await assertSucceeds(setDoc(read(db('anon-two'),'user-two'),{readAt:serverTimestamp(),actor:'anon-two'}));
 await assertFails(setDoc(read(db('anon-two'),'anon-two'),{readAt:serverTimestamp(),actor:'anon-two'}));
});
test('036 reactions can only be modified by their canonical author',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 const reaction=(c,uid)=>doc(c,root+'/chats/general/messages/m1/reactions/'+uid);
 await assertSucceeds(setDoc(reaction(b,'user-two'),{emoji:'🐾',updatedAt:serverTimestamp(),actor:'user-two'}));
 await assertFails(setDoc(reaction(a,'user-two'),{emoji:'💜',updatedAt:serverTimestamp(),actor:'user-one'}));
 await assertFails(setDoc(reaction(b,'user-two'),{emoji:'<script>',updatedAt:serverTimestamp(),actor:'user-two'}));
 await assertFails(deleteDoc(reaction(a,'user-two')));await assertSucceeds(deleteDoc(reaction(b,'user-two')));
});
test('036 rich messages replies and edited marker preserve backward compatibility',async()=>{
 const a=db('user-one');await chat(a);await setDoc(msgRef(a),message());
 await assertSucceeds(setDoc(msgRef(a,'reply'),{...message(),format:'markdown-v1',replyTo:'m1',attachmentIds:[]}));
 await assertFails(setDoc(msgRef(a,'missing'),{...message(),replyTo:'not-here'}));
 await assertFails(setDoc(msgRef(a,'html'),{...message(),format:'html'}));
 const saved=(await getDoc(msgRef(a,'reply'))).data();
 await assertSucceeds(setDoc(msgRef(a,'reply'),{...saved,text:'**Edited**',editedAt:serverTimestamp()}));
 await assertFails(setDoc(msgRef(a,'reply'),{...saved,text:'Spoof',editedAt:Timestamp.fromMillis(1)}));
 await assertFails(setDoc(msgRef(a,'reply'),{...saved,replyTo:'changed'}));
});
test('036 only backend may publish attachment manifests and nonempty attachment messages',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 const manifest={owner:'user-one',actor:'user-one',chatId:'general',messageId:'m1',state:'ready',name:'private.txt'};
 const attachment=(c)=>doc(c,root+'/attachments/file1');
 await assertFails(setDoc(attachment(a),manifest));
 await assertFails(setDoc(msgRef(a,'with-file'),{...message(),attachmentIds:['file1']}));
 await env.withSecurityRulesDisabled(c=>setDoc(attachment(c.firestore()),manifest));
 await assertSucceeds(getDoc(attachment(b)));await assertFails(getDoc(attachment(db('outsider'))));
 await setDoc(msgRef(a),{...(await getDoc(msgRef(a))).data(),deleted:true,text:''});
 await assertFails(getDoc(attachment(b)));
});
test('036 removed parent revokes receipts reactions and attachment metadata',async()=>{
 const a=db('user-one'),b=db('user-two');const d={id:'theme',name:'Theme',shared:true,owner:'USER_1',updatedAt:1,items:[{id:'item',title:'Plan'}],itemIds:['item']};
 await write(a,'plan_theme',envelope(d,'user-one',{type:'plan',shared:true,readers:uids}));await chat(a,'plan_theme_item','theme','item');await setDoc(msgRef(a,'m1','plan_theme_item'),message());
 const read=doc(b,root+'/chats/plan_theme_item/messages/m1/reads/user-two');await setDoc(read,{actor:'user-two',readAt:serverTimestamp()});
 await env.withSecurityRulesDisabled(c=>setDoc(doc(c.firestore(),root+'/attachments/file1'),{owner:'user-one',chatId:'plan_theme_item',messageId:'m1',state:'ready'}));
 await write(a,'plan_theme',envelope({...d,items:[],itemIds:[]},'user-one',{type:'plan',shared:true,readers:uids}));
 await assertFails(getDoc(read));await assertFails(getDoc(doc(b,root+'/attachments/file1')));
 await assertFails(setDoc(doc(b,root+'/chats/plan_theme_item/messages/m1/reactions/user-two'),{emoji:'👍',actor:'user-two',updatedAt:serverTimestamp()}));
});
test('036 notification history is own-only and clients cannot forge or reset it',async()=>{
 const history=(c,uid)=>doc(c,root+'/notifications/'+uid+'/items/event1');
 await env.withSecurityRulesDisabled(c=>setDoc(history(c.firestore(),'user-one'),{read:false,kind:'chat',body:'Neutral'}));
 await assertSucceeds(getDoc(history(db('user-one'),'user-one')));await assertFails(getDoc(history(db('user-two'),'user-one')));
 await assertSucceeds(setDoc(history(db('user-one'),'user-one'),{read:true,kind:'chat',body:'Neutral'}));
 await assertFails(setDoc(history(db('user-one'),'user-one'),{read:false,kind:'chat',body:'Neutral'}));
 await assertFails(setDoc(history(db('user-two'),'user-two'),{read:false,kind:'chat',body:'Forged'}));
 await assertFails(setDoc(doc(db('user-one'),'system/pushDelivery'),{enabled:true}));
});


test('036 seen entry markers belong to canonical user and require readable source',async()=>{
 const a=db('user-one'),b=db('user-two');await write(a,'entry_public',envelope(entry('public')));
 const seen=(c,u,id)=>doc(c,root+'/members/'+u+'/seenEntries/'+id);
 await assertSucceeds(setDoc(seen(b,'user-two','public'),{actor:'user-two',seenAt:serverTimestamp()}));
 await assertFails(setDoc(seen(a,'user-two','public'),{actor:'user-one',seenAt:serverTimestamp()}));
 await write(a,'entry_secret',envelope(entry('secret',{visibility:'PRIVATE'})));
 await assertFails(setDoc(seen(b,'user-two','secret'),{actor:'user-two',seenAt:serverTimestamp()}));
 await assertFails(getDoc(seen(a,'user-two','public')));
});
test('036 markdown entry descriptions support message conversion without truncation',async()=>{
 const a=db('user-one');
 await assertSucceeds(write(a,'entry_long',envelope(entry('long',{note:'x'.repeat(10000),descriptionFormat:'markdown-v1',titleFormat:'plain-v1'}))));
 await assertFails(write(a,'entry_long',envelope(entry('long',{note:'x'.repeat(10001),descriptionFormat:'markdown-v1'}))));
});


test('proposal acceptance preserves approved Markdown formats and rejects substituted formats',async()=>{
 const {a,b,accepted}=await acceptedMeeting('format-meeting');
 const p=proposal('format-offer','USER_2',{title:'**Moved meeting**',note:'A <u>formatted note</u>',titleFormat:'markdown-v1',descriptionFormat:'markdown-v1'});
 const offered={...accepted,proposal:p};
 await assertSucceeds(write(b,'format-meeting',envelope(offered,'user-two')));
 const answered={...proposalResponse(offered),titleFormat:p.titleFormat,descriptionFormat:p.descriptionFormat};
 await assertFails(write(a,'format-meeting',envelope({...answered,titleFormat:'plain-v1'})));
 await assertFails(write(a,'format-meeting',envelope({...answered,descriptionFormat:'plain-v1'})));
 await assertSucceeds(write(a,'format-meeting',envelope(answered)));
 const saved=(await getDoc(ref(a,'format-meeting'))).data().data;
 assert.equal(saved.titleFormat,'markdown-v1');assert.equal(saved.descriptionFormat,'markdown-v1');
});

test('proposal formats reject unknown values and cannot mutate base formats before acceptance',async()=>{
 const {a,b,accepted}=await acceptedMeeting('invalid-format-meeting');
 for(const field of ['titleFormat','descriptionFormat']){
  await assertFails(write(b,'invalid-format-meeting',envelope({...accepted,proposal:proposal('invalid-format','USER_2',{[field]:'html-v9'})},'user-two')));
 }
 await assertFails(write(b,'invalid-format-meeting',envelope({...accepted,titleFormat:'markdown-v1',proposal:proposal('valid-format','USER_2',{titleFormat:'markdown-v1'})},'user-two')));
 const offered={...accepted,proposal:proposal('legacy-format')};
 await assertSucceeds(write(b,'invalid-format-meeting',envelope(offered,'user-two')));
 await assertFails(write(a,'invalid-format-meeting',envelope({...proposalResponse(offered),titleFormat:'markdown-v1'})));
 await assertSucceeds(write(a,'invalid-format-meeting',envelope(proposalResponse(offered))));
});

test('proposal permits the entry description limit and rejects oversized text',async()=>{
 const {b,accepted}=await acceptedMeeting('long-description');
 await assertFails(write(b,'long-description',envelope({...accepted,proposal:proposal('too-long','USER_2',{note:'n'.repeat(10001)})},'user-two')));
 await assertSucceeds(write(b,'long-description',envelope({...accepted,proposal:proposal('long-enough','USER_2',{note:'n'.repeat(10000)})},'user-two')));
});

test('fix22 reactions reject own messages and cap unique selections at three',async()=>{
 const a=db('user-one'),b=db('user-two');await chat(a);await setDoc(msgRef(a),message());
 const ref=(c,id)=>doc(c,root+'/chats/general/messages/m1/reactions/'+id);
 const payload=(emojis,actor='user-two')=>({emoji:emojis[0],emojis,updatedAt:serverTimestamp(),actor});
 await assertFails(setDoc(ref(a,'user-one'),payload(['💜'],'user-one')));
 await assertSucceeds(setDoc(ref(b,'user-two'),payload(['💜','🐾','👍'])));
 await assertFails(setDoc(ref(b,'user-two'),payload(['💜','🐾','👍','😊'])));
 await assertFails(setDoc(ref(b,'user-two'),payload(['💜','💜'])));
 await assertFails(setDoc(ref(b,'user-two'),payload(['😻'])));
 await assertSucceeds(setDoc(ref(b,'user-two'),payload(['❤️','❄️','🥶'])));
 await assertSucceeds(setDoc(ref(b,'user-two'),payload(['❤️','👀'])));
 await assertFails(deleteDoc(ref(a,'user-two')));
 await assertSucceeds(deleteDoc(ref(b,'user-two')));
 // Older clients' single emoji payload remains compatible for incoming messages.
 await assertSucceeds(setDoc(ref(b,'user-two'),{emoji:'👌',updatedAt:serverTimestamp(),actor:'user-two'}));
});
