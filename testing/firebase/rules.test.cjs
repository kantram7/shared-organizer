const {createRequire}=require('node:module');
const path=require('node:path');
const fs=require('node:fs');
const {test,before,after,beforeEach}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const dep=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'));
const {initializeTestEnvironment,assertSucceeds,assertFails}=dep('@firebase/rules-unit-testing');
const {doc,setDoc,getDoc,getDocs,collection,query,where,writeBatch,serverTimestamp,disableNetwork,enableNetwork}=dep('firebase/firestore');
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
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-shared-organizer',firestore:{host:'127.0.0.1',port:8080,rules:fs.readFileSync(path.resolve('backend/firestore.rules'),'utf8')}});});
beforeEach(async()=>{await env.clearFirestore();await env.withSecurityRulesDisabled(async c=>{await setDoc(doc(c.firestore(),root),space);for(const uid of uids)await setDoc(doc(c.firestore(),'members/'+uid),{spaceId:'home'});});});
after(async()=>{await env?.cleanup();});
test('non-members and anonymous clients cannot read or write organizer data',async()=>{const a=db('user-one');await write(a,'one',envelope(entry('one')));for(const c of [db('outsider'),env.unauthenticatedContext().firestore()]){await assertFails(getDoc(ref(c,'one')));await assertFails(write(c,'other',envelope(entry('other'),'outsider')));await assertFails(getDoc(doc(c,root)));}});
test('users cannot grant themselves membership or impersonate the other owner',async()=>{await assertFails(setDoc(doc(db('outsider'),'members/outsider'),{spaceId:'home'}));await assertFails(setDoc(doc(db('user-one'),root),{...space,uids:[...uids,'outsider']}));await assertFails(write(db('user-two'),'one',envelope(entry('one'),'user-two')));});
test('shared records synchronize to second authorized client',async()=>{await assertSucceeds(write(db('user-one'),'one',envelope(entry('one'))));const r=await assertSucceeds(getDoc(ref(db('user-two'),'one')));assert.equal(r.data().data.title,'Plan');const q=query(collection(db('user-two'),root+'/records'),where('readers','array-contains','user-two'));assert.equal((await assertSucceeds(getDocs(q))).size,1);});
test('private record and its history are unreadable by other participant',async()=>{const e=envelope(entry('secret',{visibility:'PRIVATE'}));await write(db('user-one'),'secret',e);await assertFails(getDoc(ref(db('user-two'),'secret')));await assertFails(getDoc(doc(db('user-two'),root+'/records/secret/revisions/'+e.revision)));await assertFails(getDoc(doc(db('user-two'),root+'/busy/secret')));});
test('busy projection contains schedule only, not title or comments',async()=>{const e=envelope(entry('busy',{visibility:'BUSY'}));await write(db('user-one'),'busy',e);const p=(await assertSucceeds(getDoc(doc(db('user-two'),root+'/busy/busy')))).data();assert.equal(p.active,true);assert.equal(p.start,'19:00');assert.equal(p.title,undefined);assert.equal(p.note,undefined);await assertFails(getDoc(ref(db('user-two'),'busy')));});
test('owner can revoke shared visibility and old history access',async()=>{const a=db('user-one'),b=db('user-two'),e=envelope(entry('one'));await write(a,'one',e);await write(a,'one',envelope(entry('one',{visibility:'PRIVATE'})));await assertFails(getDoc(ref(b,'one')));await assertFails(getDoc(doc(b,root+'/records/one/revisions/'+e.revision)));});
test('read-only shared records reject edits by other participant',async()=>{await write(db('user-one'),'one',envelope(entry('one',{editable:false})));await assertFails(write(db('user-two'),'one',envelope(entry('one',{editable:false,title:'Changed'}),'user-two')));});
test('collaborator can edit content but not access flags or owner',async()=>{const a=db('user-one'),b=db('user-two');await write(a,'one',envelope(entry('one')));await assertSucceeds(write(b,'one',envelope(entry('one',{title:'Changed'}),'user-two')));await assertFails(write(b,'one',envelope(entry('one',{editable:false}),'user-two')));await assertFails(write(b,'one',envelope(entry('one',{visibility:'PRIVATE'}),'user-two')));});
test('recipient can accept a read-only invitation, without modifying its time',async()=>{const a=db('user-one'),b=db('user-two'),e=entry('invite',{invite:'PENDING',editable:false});await write(a,'invite',envelope(e));await assertFails(write(b,'invite',envelope({...e,invite:'ACCEPTED',start:'18:00'},'user-two')));await assertSucceeds(write(b,'invite',envelope({...e,invite:'ACCEPTED',resolvedDay:'2026-11-17'},'user-two')));await assertFails(write(b,'invite',envelope({...e,invite:'PENDING'},'user-two')));});
test('author cannot accept own invitation or silently move an accepted one',async()=>{const a=db('user-one'),b=db('user-two'),e=entry('invite',{invite:'PENDING'});await write(a,'invite',envelope(e));await assertFails(write(a,'invite',envelope({...e,invite:'ACCEPTED'})));await write(b,'invite',envelope({...e,invite:'ACCEPTED'},'user-two'));await assertFails(write(a,'invite',envelope({...e,invite:'ACCEPTED',start:'18:00'})));});
test('record writes require matching history and busy projection atomically',async()=>{const a=db('user-one');await assertFails(write(a,'one',envelope(entry('one')),{history:false}));await assertFails(write(a,'one',envelope(entry('one')),{busy:false}));});
test('history cannot be edited, deleted or forged without corresponding record',async()=>{const a=db('user-one'),e=envelope(entry('one'));await write(a,'one',e);const h=doc(a,root+'/records/one/revisions/'+e.revision);await assertFails(setDoc(h,{...e,data:entry('one',{title:'Forgery'})}));await assertFails(setDoc(doc(a,root+'/records/one/revisions/fake-long-revision'),{...e,revision:'fake-long-revision'}));});
test('deletion is a tombstone with history; restore retains both versions',async()=>{const a=db('user-one'),e=entry('one');await write(a,'one',envelope(e));await write(a,'one',envelope(e,'user-one',{deleted:true}));assert.equal((await getDoc(ref(a,'one'))).data().deleted,true);await write(a,'one',envelope(e));const versions=await getDocs(query(collection(a,root+'/records/one/revisions'),where('readers','array-contains','user-one')));assert.equal(versions.size,3);});
test('both conflicting revisions survive; last server commit becomes current',async()=>{const a=db('user-one'),b=db('user-two');await write(a,'one',envelope(entry('one')));await disableNetwork(a);const pending=write(a,'one',envelope(entry('one',{title:'Offline edit'})));await write(b,'one',envelope(entry('one',{title:'Online edit'}),'user-two'));await enableNetwork(a);await pending;assert.equal((await getDoc(ref(b,'one'))).data().data.title,'Offline edit');const history=await getDocs(query(collection(b,root+'/records/one/revisions'),where('readers','array-contains','user-two')));assert.equal(history.size,3);});
test('plans are shared only; private lists remain owner-only',async()=>{const a=db('user-one'),b=db('user-two');const c={id:'list1',name:'List',shared:false,owner:'USER_1',updatedAt:1,items:[]};const e=envelope(c,'user-one',{type:'list',shared:false,readers:['user-one']});await assertSucceeds(write(a,'list1',e));await assertFails(getDoc(ref(b,'list1')));await assertFails(write(a,'plan1',{...e,type:'plan',revision:crypto.randomUUID()}));});
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
 await assertFails(setDoc(msgRef(a),{...saved,text:'Edited'}));
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
