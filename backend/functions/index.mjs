import {initializeApp} from 'firebase-admin/app';
import {getFirestore, FieldValue, Timestamp} from 'firebase-admin/firestore';
import {getMessaging} from 'firebase-admin/messaging';
import {getStorage} from 'firebase-admin/storage';
import {onDocumentCreated, onDocumentUpdated, onDocumentWritten} from 'firebase-functions/v2/firestore';
import {onCall, HttpsError} from 'firebase-functions/v2/https';
import {onSchedule} from 'firebase-functions/v2/scheduler';
import {onObjectFinalized} from 'firebase-functions/v2/storage';
import {setGlobalOptions} from 'firebase-functions/v2';
import {defineString} from 'firebase-functions/params';
import {randomUUID} from 'node:crypto';
import {LIMITS,idOk,millis,stableId,principal,chatAllowed,validateFiles,validateMessage,recordNotice,enrichedRecordNotice,deliveryPolicy,claimable} from './core.mjs';

initializeApp();
const runtimeAccount=defineString('ORGANIZER_RUNTIME_SERVICE_ACCOUNT',{default:''});
setGlobalOptions({region:'europe-west3',maxInstances:3,memory:'256MiB',timeoutSeconds:120,serviceAccount:runtimeAccount});
const db=getFirestore(), bucketName=defineString('CHAT_ATTACHMENTS_BUCKET',{default:''});
const data=async path=>(await db.doc(path).get()).data();
const spacePath=s=>`spaces/${s}`;
const messagePath=(s,c,m)=>`${spacePath(s)}/chats/${c}/messages/${m}`;
const ts=ms=>Timestamp.fromMillis(ms);
function deny(message='Нет доступа к этому чату') { throw new HttpsError('permission-denied',message); }
function validId(id) { if (!idOk(id)) throw new HttpsError('invalid-argument','Некорректный идентификатор'); return id; }
async function memberContext(s,uid) {
  validId(s); validId(uid);
  const [member,team]=await Promise.all([data(`members/${uid}`),data(spacePath(s))]);
  const who=principal(member,team,uid,s); if (!who) deny();
  return {...who,team};
}
async function chatContext(s,c,who) {
  validId(c);
  const chat=await data(`${spacePath(s)}/chats/${c}`);
  let parent;
  if (chat?.type==='entry' && idOk(chat.itemId)) parent=await data(`${spacePath(s)}/records/entry_${chat.itemId}`);
  if (chat?.type==='plan' && idOk(chat.collectionId)) parent=await data(`${spacePath(s)}/records/plan_${chat.collectionId}`);
  if (!chatAllowed(c,chat,parent,who.uid)) deny();
  return chat;
}
async function caller(request) {
  if (!request.auth) throw new HttpsError('unauthenticated','Войдите в аккаунт');
  return memberContext(request.data?.spaceId,request.auth.uid);
}
async function transactionChat(tx,s,c,authUid) {
  const [member,team,chatSnap]=await Promise.all([tx.get(db.doc(`members/${authUid}`)),tx.get(db.doc(spacePath(s))),tx.get(db.doc(`${spacePath(s)}/chats/${c}`))]);
  const who=principal(member.data(),team.data(),authUid,s),chat=chatSnap.data();
  if(!who)deny();let parent;
  if(chat?.type==='entry'&&idOk(chat.itemId))parent=(await tx.get(db.doc(`${spacePath(s)}/records/entry_${chat.itemId}`))).data();
  if(chat?.type==='plan'&&idOk(chat.collectionId))parent=(await tx.get(db.doc(`${spacePath(s)}/records/plan_${chat.collectionId}`))).data();
  if(!chatAllowed(c,chat,parent,who.uid))deny();return who;
}
function attachmentsBucket() {
  const name=bucketName.value();
  if (!name) throw new HttpsError('failed-precondition','Хранилище файлов ещё не настроено');
  return getStorage().bucket(name);
}
function inputError(action) {
  try { return action(); } catch { throw new HttpsError('invalid-argument','Проверьте файлы: до 10, до 100 МиБ каждый и до 500 МиБ вместе'); }
}

// Only server original-document triggers send notifications. Legacy pushQueue writes are inert.
async function resolveSource(source, recipient) {
  const who=await memberContext(source.spaceId,recipient.authUid);
  if (recipient.canonicalUid!==who.uid) return null;
  const base=spacePath(source.spaceId), actor=await memberContext(source.spaceId,source.actor);
  if (source.kind==='chat') {
    const chat=await chatContext(source.spaceId,source.chatId,who);
    await chatContext(source.spaceId,source.chatId,actor);
    const message=await data(messagePath(source.spaceId,source.chatId,source.messageId));
    if (!message || message.deleted || message.actor!==source.actor || message.sender!==actor.uid || message.sender===who.uid) return null;
    const read=await data(`${messagePath(source.spaceId,source.chatId,source.messageId)}/reads/${who.uid}`);
    if (read?.readAt) return null;
    return {kind:'chat',title:'Сообщение котика',body:'Новое сообщение. Открой чат, чтобы прочитать',chat,authorUid:actor.uid,authorRole:actor.role,
      recordId:chat.type==='entry'?`entry_${chat.itemId}`:chat.type==='plan'?`plan_${chat.collectionId}`:''};
  }
  const record=await data(`${base}/records/${source.recordId}`);
  const revision=await data(`${base}/records/${source.recordId}/revisions/${source.revision}`);
  if (!record || !revision || record.revision!==source.revision || revision.actor!==source.actor || record.actor!==source.actor || !record.readers?.includes(who.uid) || !record.readers?.includes(actor.uid)) return null;
  const notice=enrichedRecordNotice(recordNotice(record,who.role),record,actor.team.names?.[actor.role]);
  if (!notice) return null;
  return {...notice,...(actor.uid===who.uid?{kind:'refresh',title:'',body:''}:{}),authorUid:actor.uid,authorRole:actor.role,recordId:source.recordId,gathering:record.data.isGathering===true};
}
async function safeResolve(source,recipient) {
  try { return await resolveSource(source,recipient); } catch(error) { if(error instanceof HttpsError && error.code==='permission-denied') return null; throw error; }
}
async function deliver(source) {
  if(!idOk(source.actor))return;
  const config=await data('system/pushDelivery');
  const policy=deliveryPolicy(source,config);if(!policy)return;
  const eventId=stableId(source.spaceId,source.kind,source.chatId||source.recordId,source.messageId||source.revision);
  const team=await data(spacePath(source.spaceId));
  // Keep notification history available even if the recipient has no registered phone yet.
  for(const uid of team?.uids||[]){
    const notice=await safeResolve(source,{authUid:uid,canonicalUid:uid});
    if(!notice||notice.kind==='refresh')continue;
    const history=db.doc(`${spacePath(source.spaceId)}/notifications/${uid}/items/${eventId}`);
    await db.runTransaction(async tx=>{
      const existing=await tx.get(history);
      const receipt=source.kind==='chat'?await tx.get(db.doc(`${messagePath(source.spaceId,source.chatId,source.messageId)}/reads/${uid}`)):null;
      if(receipt?.data()?.readAt){if(existing.exists&&!existing.data().read)tx.update(history,{read:true});return;}
      if(!existing.exists)tx.create(history,{
        kind:notice.kind,title:notice.historyTitle||notice.title,body:notice.body,eventId,spaceId:source.spaceId,
        authorUid:notice.authorUid||'',authorRole:notice.authorRole||'',targetRevision:source.revision||'',
        recordId:notice.recordId||'',chatId:source.chatId||'',messageId:source.messageId||'',
        collectionId:notice.chat?.collectionId||'',itemId:notice.chat?.itemId||'',gathering:String(notice.gathering===true),
        createdAt:ts(millis(source.createdAt)),read:false,expiresAt:ts(Date.now()+LIMITS.retentionMs)
      });
    });
  }
  const devices=await db.collection(`${spacePath(source.spaceId)}/devices`).get();
  let failed=false;
  for (const snapshot of devices.docs) {
    const device=snapshot.data();
    if(policy.test&&!policy.devices.includes(device.authUid))continue;
    if (!idOk(device.authUid) || !idOk(device.canonicalUid) || typeof device.token!=='string') continue;
    const ledger=db.doc(`${spacePath(source.spaceId)}/deliveryEvents/${eventId}/devices/${stableId(snapshot.id)}`);
    const nonce=randomUUID(), now=Date.now();
    const claim=await db.runTransaction(async tx=>{
      const prior=(await tx.get(ledger)).data();
      if (!claimable(prior,now)) return prior?.state==='sending'?'busy':'done';
      tx.set(ledger,{state:'sending',leaseUntil:ts(now+180000),nonce,attempts:(prior?.attempts||0)+1,createdAt:prior?.createdAt||ts(now),expiresAt:ts(now+LIMITS.retentionMs)},{merge:true});return 'claimed';
    });
    if (claim==='busy') {failed=true;continue;} if(claim!=='claimed') continue;
    try {
      // Re-check membership, source privacy and exact-message receipt immediately before FCM.
      const notice=await safeResolve(source,device);
      if(!notice){await ledger.set({state:'suppressed',finishedAt:FieldValue.serverTimestamp()},{merge:true});continue;}
      const fields={kind:notice.kind,title:notice.title,body:notice.body,eventId,createdAt:new Date(millis(source.createdAt)).toISOString(),
        spaceId:source.spaceId,recordId:notice.recordId||'',chatId:source.chatId||'',messageId:source.messageId||'',
        collectionId:notice.chat?.collectionId||'',itemId:notice.chat?.itemId||'',authUid:device.authUid,gathering:String(notice.gathering===true)};
      // Data-only avoids Android displaying a stale message before the client can suppress it.
      await getMessaging().send({token:device.token,data:fields,android:{priority:notice.kind==='refresh'?'normal':'high',ttl:86400000,collapseKey:eventId}});
      await ledger.set({state:'sent',finishedAt:FieldValue.serverTimestamp()},{merge:true});
    } catch(error) {
      if(['messaging/registration-token-not-registered','messaging/invalid-registration-token'].includes(error.code)) {
        // Do not delete a freshly rotated token that replaced the one used by this attempt.
        await db.runTransaction(async tx=>{const current=await tx.get(snapshot.ref);if(current.data()?.token===device.token)tx.delete(snapshot.ref);});
        await ledger.set({state:'suppressed',reason:'unregistered'},{merge:true});
      } else { failed=true;await ledger.set({state:'retry',lastError:String(error.code||'temporary').slice(0,80),nonce},{merge:true}); }
    }
  }
  if(failed) throw new Error('Some device deliveries require retry');
}
export const messageCreated=onDocumentCreated({document:'spaces/{spaceId}/chats/{chatId}/messages/{messageId}',retry:true},event=>{
  const m=event.data?.data();if(!m)return;
  return deliver({...event.params,kind:'chat',actor:m.actor,createdAt:m.createdAt});
});
// Keeps the separate notification history consistent with canonical message reads.
// The source history transaction also checks receipts, so either transaction order is safe.
export const messageRead=onDocumentWritten({document:'spaces/{spaceId}/chats/{chatId}/messages/{messageId}/reads/{uid}',retry:true},async event=>{
  if(event.time&&Date.now()-Date.parse(event.time)>86400000)return;
  const {spaceId,chatId,messageId,uid}=event.params;
  if(![spaceId,chatId,messageId,uid].every(idOk)||!event.data?.after.data()?.readAt)return;
  const history=db.doc(`${spacePath(spaceId)}/notifications/${uid}/items/${stableId(spaceId,'chat',chatId,messageId)}`);
  const receipt=db.doc(`${messagePath(spaceId,chatId,messageId)}/reads/${uid}`);
  await db.runTransaction(async tx=>{
    const [h,r,team]=await Promise.all([tx.get(history),tx.get(receipt),tx.get(db.doc(spacePath(spaceId)))]);
    if(!team.data()?.uids?.includes(uid)||!r.data()?.readAt||!h.exists||h.data().read)return;
    if(h.data().kind!=='chat'||h.data().chatId!==chatId||h.data().messageId!==messageId)return;
    tx.update(history,{read:true});
  });
});
export const recordRevisionCreated=onDocumentCreated({document:'spaces/{spaceId}/records/{recordId}/revisions/{revision}',retry:true},event=>{
  const r=event.data?.data();if(!r)return;
  return deliver({...event.params,kind:'record',actor:r.actor,createdAt:r.updatedAt});
});

export const prepareAttachments=onCall(async request=>{
  const who=await caller(request), p=request.data;
  validId(p.chatId);validId(p.messageId);
  const files=inputError(()=>validateFiles(p.files));await chatContext(p.spaceId,p.chatId,who);
  const base=spacePath(p.spaceId), session=db.doc(`${base}/uploadSessions/${stableId(p.chatId,p.messageId)}`), now=Date.now();
  const manifests=files.map(f=>({...f,owner:who.uid,actor:who.authUid,spaceId:p.spaceId,chatId:p.chatId,messageId:p.messageId,
    storagePath:`${base}/chats/${p.chatId}/attachments/${f.id}/original`,state:'pending',createdAt:ts(now),expiresAt:ts(now+LIMITS.retentionMs),cleanupAt:ts(now+LIMITS.abandonedMs)}));
  await db.runTransaction(async tx=>{
    await transactionChat(tx,p.spaceId,p.chatId,who.authUid);
    const [existing,message,...current]=await Promise.all([tx.get(session),tx.get(db.doc(messagePath(p.spaceId,p.chatId,p.messageId))),...files.map(f=>tx.get(db.doc(`${base}/attachments/${f.id}`)))]);
    if(message.exists)throw new HttpsError('already-exists','Сообщение уже отправлено');
    if(existing.exists&&(existing.data().owner!==who.uid||JSON.stringify(validateFiles(existing.data().files))!==JSON.stringify(files)))deny('Нельзя заменить файлы уже созданной отправки');
    if(existing.exists&&millis(existing.data().expiresAt)<=now)throw new HttpsError('failed-precondition','Создайте новую отправку');
    current.forEach((s,i)=>{if(s.exists&&(s.data().messageId!==p.messageId||s.data().chatId!==p.chatId||s.data().owner!==who.uid))deny();});
    if(!existing.exists)tx.create(session,{owner:who.uid,actor:who.authUid,chatId:p.chatId,messageId:p.messageId,files,createdAt:ts(now),expiresAt:ts(now+LIMITS.abandonedMs)});
    current.forEach((s,i)=>{if(!s.exists)tx.create(s.ref,manifests[i]);});
  });
  const bucket=attachmentsBucket();
  const attachments=await Promise.all(manifests.map(async m=>{
    const headers={'Content-Type':m.mime,'x-goog-if-generation-match':'0','Content-Length':String(m.size)};
    const [uploadUrl]=await bucket.file(m.storagePath).getSignedUrl({version:'v4',action:'write',expires:now+900000,contentType:m.mime,extensionHeaders:{'x-goog-if-generation-match':'0','content-length':String(m.size)}});
    let thumbnail={};
    if(m.thumbnailSize){
      const thumbnailPath=m.storagePath.replace(/\/original$/,'/thumbnail');
      const [thumbnailUploadUrl]=await bucket.file(thumbnailPath).getSignedUrl({version:'v4',action:'write',expires:now+900000,contentType:m.thumbnailMime,extensionHeaders:{'x-goog-if-generation-match':'0','content-length':String(m.thumbnailSize)}});
      thumbnail={thumbnailUploadUrl,thumbnailHeaders:{'Content-Type':m.thumbnailMime,'x-goog-if-generation-match':'0','Content-Length':String(m.thumbnailSize)}};
    }
    return {id:m.id,storagePath:m.storagePath,uploadUrl,headers,...thumbnail,uploadExpiresAtMillis:now+900000,expiresAtMillis:millis((await data(`${base}/attachments/${m.id}`)).expiresAt)};
  }));
  return {attachments,maxFileBytes:LIMITS.fileBytes,maxMessageBytes:LIMITS.messageBytes};
});

export const commitAttachmentMessage=onCall(async request=>{
  const who=await caller(request),p=request.data;inputError(()=>validateMessage(p));
  await chatContext(p.spaceId,p.chatId,who);
  if(!Array.isArray(p.attachmentIds)||!p.attachmentIds.length||p.attachmentIds.length>10||new Set(p.attachmentIds).size!==p.attachmentIds.length||p.attachmentIds.some(id=>!idOk(id)))throw new HttpsError('invalid-argument','Некорректные вложения');
  const base=spacePath(p.spaceId), target=db.doc(messagePath(p.spaceId,p.chatId,p.messageId)),existing=await target.get();
  if(existing.exists){if(existing.data().sender!==who.uid)deny();return {messageId:p.messageId,alreadySent:true,expiresAtMillis:millis(existing.data().createdAt)+LIMITS.retentionMs};}
  if(p.replyTo){const reply=await data(messagePath(p.spaceId,p.chatId,p.replyTo));if(!reply||reply.deleted)throw new HttpsError('failed-precondition','Исходное сообщение недоступно');}
  const bucket=attachmentsBucket(), manifests=await Promise.all(p.attachmentIds.map(id=>data(`${base}/attachments/${id}`)));
  let total=0; const verified=[];
  for(let i=0;i<manifests.length;i++){
    const m=manifests[i];
    if(!m||m.owner!==who.uid||m.chatId!==p.chatId||m.messageId!==p.messageId||m.state!=='pending'||millis(m.cleanupAt)<=Date.now()||millis(m.expiresAt)<=Date.now())deny();
    let metadata;try{[metadata]=await bucket.file(m.storagePath).getMetadata();}catch{throw new HttpsError('failed-precondition','Дождитесь загрузки всех файлов');}
    if(Number(metadata.size)!==m.size||metadata.contentType!==m.mime||m.size>LIMITS.fileBytes)throw new HttpsError('failed-precondition','Файл не совпадает с объявленным размером или типом');
    let thumbnail={};
    if(m.thumbnailSize){
      let thumb;try{[thumb]=await bucket.file(m.storagePath.replace(/\/original$/,'/thumbnail')).getMetadata();}catch{throw new HttpsError('failed-precondition','Дождитесь загрузки миниатюры');}
      if(Number(thumb.size)!==m.thumbnailSize||thumb.contentType!==m.thumbnailMime)throw new HttpsError('failed-precondition','Миниатюра не совпадает с объявленными параметрами');
      thumbnail={thumbnailGeneration:thumb.generation};
    }
    total+=m.size;verified.push({generation:metadata.generation,md5Hash:metadata.md5Hash||'',...thumbnail});
  }
  if(total>LIMITS.messageBytes)throw new HttpsError('invalid-argument','Общий размер файлов превышает 500 МиБ');
  // Revalidate the live parent after uploads; private/deleted parents cannot be used for publication.
  await chatContext(p.spaceId,p.chatId,await memberContext(p.spaceId,who.authUid));
  const commitResult=await db.runTransaction(async tx=>{
    await transactionChat(tx,p.spaceId,p.chatId,who.authUid);
    const existing=await tx.get(target);if(existing.exists){if(existing.data().sender!==who.uid)deny();return {messageId:p.messageId,alreadySent:true,expiresAtMillis:millis(existing.data().createdAt)+LIMITS.retentionMs};}
    if(p.replyTo){const reply=await tx.get(db.doc(messagePath(p.spaceId,p.chatId,p.replyTo)));if(!reply.exists||reply.data().deleted)throw new HttpsError('failed-precondition','Исходное сообщение недоступно');}
    const current=await Promise.all(p.attachmentIds.map(id=>tx.get(db.doc(`${base}/attachments/${id}`))));
    if(current.some(s=>s.data()?.state!=='pending'||millis(s.data()?.cleanupAt)<=Date.now()))throw new HttpsError('failed-precondition','Загрузка уже истекла');
    // One trusted server send timestamp for message and manifests, recomputed on transaction retry.
    // Never extend retention on a later idempotent client retry.
    const sentAt=Timestamp.now(),expiresAt=ts(sentAt.toMillis()+LIMITS.retentionMs);
    tx.create(target,{sender:who.uid,actor:who.authUid,role:who.role,text:p.text,format:p.format||'plain-v1',replyTo:p.replyTo||'',attachmentIds:p.attachmentIds,createdAt:sentAt,clientTime:sentAt.toMillis(),deleted:false});
    current.forEach((s,i)=>tx.update(s.ref,{state:'ready',sentAt,expiresAt,cleanupAt:expiresAt,...verified[i]}));
    return {messageId:p.messageId,alreadySent:false,expiresAtMillis:expiresAt.toMillis()};
  });
  return commitResult;
});

export const attachmentDownload=onCall(async request=>{
  const who=await caller(request),p=request.data;validId(p.attachmentId);
  const m=await data(`${spacePath(p.spaceId)}/attachments/${p.attachmentId}`);
  if(!m||m.state!=='ready'||millis(m.expiresAt)<=Date.now())throw new HttpsError('not-found','Файл больше не хранится на сервере');
  await chatContext(p.spaceId,m.chatId,who);
  const message=await data(messagePath(p.spaceId,m.chatId,m.messageId));
  if(!message||message.deleted||!message.attachmentIds?.includes(p.attachmentId))deny();
  const expiresAtMillis=Date.now()+60000;
  // Response disposition prevents active uploaded documents from being interpreted as app HTML.
  const thumbnail=p.thumbnail===true;
  if(thumbnail&&!m.thumbnailGeneration)throw new HttpsError('not-found','Миниатюра недоступна');
  const [url]=await attachmentsBucket().file(thumbnail?m.storagePath.replace(/\/original$/,'/thumbnail'):m.storagePath,{generation:thumbnail?m.thumbnailGeneration:m.generation}).getSignedUrl({version:'v4',action:'read',expires:expiresAtMillis,responseDisposition:`attachment; filename*=UTF-8''${encodeURIComponent(m.name)}`});
  return {url,expiresAtMillis,size:thumbnail?m.thumbnailSize:m.size,mime:thumbnail?m.thumbnailMime:m.mime,md5Hash:thumbnail?'':m.md5Hash||''};
});

async function purgeManifest(ref,m){
  // Generation preconditions protect a newer object if a storage administrator restored/replaced one.
  const bucket=attachmentsBucket();
  for(const suffix of ['original','thumbnail']){
    const path=m.storagePath.replace(/\/original$/,`/${suffix}`);
    try{await bucket.file(path).delete({ignoreNotFound:true,...((suffix==='original'?m.generation:m.thumbnailGeneration)?{ifGenerationMatch:suffix==='original'?m.generation:m.thumbnailGeneration}:{})});}catch(error){if(error.code!==404)throw error;}
  }
  await ref.update({state:'expired',cleanupAt:FieldValue.delete(),removedAt:FieldValue.serverTimestamp()});
}
export const attachmentMessageDeleted=onDocumentUpdated({document:'spaces/{spaceId}/chats/{chatId}/messages/{messageId}',retry:true},async event=>{
  if(event.time&&Date.now()-Date.parse(event.time)>86400000)return;
  if(!event.data?.after.data().deleted||event.data.before.data().deleted)return;
  for(const id of event.data.after.data().attachmentIds||[]){
    if(!idOk(id))continue;const ref=db.doc(`${spacePath(event.params.spaceId)}/attachments/${id}`),m=(await ref.get()).data();
    if(m?.chatId===event.params.chatId&&m.messageId===event.params.messageId){await ref.update({cleanupAt:Timestamp.now()});await purgeManifest(ref,m);}
  }
});
export const cleanupAttachments=onSchedule({schedule:'every 60 minutes',timeZone:'UTC',retryCount:3},async()=>{
  const due=await db.collectionGroup('attachments').where('cleanupAt','<=',Timestamp.now()).orderBy('cleanupAt').limit(100).get();
  let failures=0;
  for(const doc of due.docs){
    try{await purgeManifest(doc.ref,doc.data());}
    catch(error){failures++;console.error(JSON.stringify({event:'attachment_cleanup_failed',code:Number.isInteger(error.code)?error.code:'temporary'}));}
  }
  // Finish independent documents before requesting the bounded Scheduler retry.
  if(failures)throw new Error('Some attachment cleanups require retry');
});
export const guardAttachmentObject=onObjectFinalized({bucket:bucketName,retry:true},async event=>{
  if(event.time&&Date.now()-Date.parse(event.time)>86400000)return;
  const object=event.data, match=/^spaces\/([A-Za-z0-9_-]+)\/chats\/([A-Za-z0-9_-]+)\/attachments\/([A-Za-z0-9_-]+)\/(original|thumbnail)$/.exec(object.name||'');
  if(!match)return;const [,spaceId,chatId,id,part]=match;
  const manifest=await data(`${spacePath(spaceId)}/attachments/${id}`);
  const expectedSize=part==='original'?manifest?.size:manifest?.thumbnailSize,expectedMime=part==='original'?manifest?.mime:manifest?.thumbnailMime;
  if(!manifest||manifest.chatId!==chatId||manifest.state==='expired'||millis(manifest.expiresAt)<=Date.now()||Number(object.size)!==expectedSize||object.contentType!==expectedMime){
    await getStorage().bucket(object.bucket).file(object.name).delete({ifGenerationMatch:object.generation,ignoreNotFound:true});
  }
});
