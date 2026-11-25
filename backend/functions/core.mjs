import {createHash} from 'node:crypto';

export const LIMITS = Object.freeze({files:10, fileBytes:100*1024*1024, messageBytes:500*1024*1024, retentionMs:30*86400000, abandonedMs:86400000});
export const idOk = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,200}$/.test(value);
export const millis = value => value?.toMillis?.() ?? (value instanceof Date ? value.getTime() : typeof value === 'number' ? value : Date.parse(value));
export const stableId = (...parts) => createHash('sha256').update(parts.join('\0')).digest('hex');
export function principal(member, team, authUid, spaceId, now=Date.now()) {
  if (!member || !team || member.spaceId !== spaceId) return null;
  const uid = member.canonicalUid || authUid;
  if (!team.uids?.includes(uid) || !['USER_1','USER_2'].includes(team.roles?.[uid])) return null;
  if (member.expiresAt && !(millis(member.expiresAt) > now)) return null;
  // Delegation is always bounded; an accidentally incomplete delegate never gains indefinite access.
  if (member.canonicalUid && member.canonicalUid !== authUid && !member.expiresAt) return null;
  return {uid,role:team.roles[uid],authUid};
}
export function chatAllowed(chatId, chat, parent, uid) {
  if (!chat || !uid) return false;
  if (chatId === 'general') return chat.type === 'general' && chat.collectionId === '' && chat.itemId === '';
  if (!parent || parent.deleted || !parent.shared || !parent.readers?.includes(uid)) return false;
  if (chat.type === 'entry') return chat.collectionId === '' && chatId === `entry_${chat.itemId}` && parent.type === 'entry' && parent.data?.id === chat.itemId && parent.data.visibility === 'SHARED';
  return chat.type === 'plan' && chatId === `plan_${chat.collectionId}_${chat.itemId}` && parent.type === 'plan' && parent.data?.itemIds?.includes(chat.itemId);
}
export function validateFiles(files) {
  if (!Array.isArray(files) || !files.length || files.length > LIMITS.files) throw new Error('attachment-count');
  const ids = new Set(); let total=0;
  for (const f of files) {
    if (!idOk(f.id) || ids.has(f.id)) throw new Error('attachment-id');
    ids.add(f.id);
    if (typeof f.name !== 'string' || !f.name.trim() || f.name.length > 255 || /[\u0000-\u001f/\\]/.test(f.name)) throw new Error('attachment-name');
    if (typeof f.mime !== 'string' || !/^[a-zA-Z0-9!#$&^_.+-]+\/[a-zA-Z0-9!#$&^_.+-]+$/.test(f.mime)) throw new Error('attachment-mime');
    if (!Number.isSafeInteger(f.size) || f.size <= 0 || f.size > LIMITS.fileBytes) throw new Error('attachment-size');
    total += f.size;
    if (f.thumbnailSize != null && (!Number.isInteger(f.thumbnailSize) || f.thumbnailSize < 1 || f.thumbnailSize > 512*1024 || !['image/jpeg','image/png','image/webp'].includes(f.thumbnailMime))) throw new Error('thumbnail');
  }
  if (total > LIMITS.messageBytes) throw new Error('message-size');
  return files.map(({id,name,mime,size,thumbnailSize,thumbnailMime})=>({id,name,mime,size,...(thumbnailSize?{thumbnailSize,thumbnailMime}:{})}));
}
export function validateMessage(input) {
  if (!idOk(input.messageId) || !idOk(input.chatId) || !idOk(input.spaceId)) throw new Error('invalid-id');
  if (typeof input.text !== 'string' || input.text.length > 4000) throw new Error('message-text');
  if (input.replyTo && !idOk(input.replyTo)) throw new Error('reply-id');
  if (input.format && !['plain-v1','markdown-v1'].includes(input.format)) throw new Error('text-format');
}
export function recordNotice(record, recipientRole) {
  if (!record || record.type !== 'entry' || !record.shared || record.data?.visibility !== 'SHARED') return null;
  const d=record.data;
  if (d.who !== 'BOTH' && d.who !== recipientRole) return {kind:'refresh',title:'',body:''};
  if (record.deleted) return {kind:'cancel',title:'Планы изменились',body:'Совместная запись отменена'};
  if (d.proposal) return {kind:'invite',title:'Предложение котикам',body:'Предложено другое время. Нужен ответ'};
  if (d.invite === 'PENDING') return {kind:'invite',title:d.isGathering?'Собрание котиков':'Тебя зовут',body:'Открой приглашение и ответь'};
  if (d.invite === 'DECLINED') return {kind:'invite',title:'Ответ на приглашение',body:'Участник отказался от приглашения'};
  if (d.invite === 'ACCEPTED') return {kind:'event',title:'Ответ на приглашение',body:'Встреча подтверждена'};
  return {kind:'event',title:d.kind==='TASK'?'Дела котиков':'Планы изменились',body:'Обновлена запись с твоим участием'};
}
export function plainNotificationText(value,format='plain-v1',limit=160) {
  let text=String(value||'');
  if(format==='markdown-v1')text=text.replace(/!?\[([^\]]*)\]\([^)]*\)/g,'$1').replace(/(\*\*|__|~~|`)/g,'').replace(/(^|\s)[*_]([^*_]+)[*_](?=\s|$)/g,'$1$2').replace(/^\s*#{1,6}\s+/gm,'');
  return text.replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g,' ').replace(/\s+/g,' ').trim().slice(0,limit);
}
export function enrichedRecordNotice(notice,record,authorName) {
  if(!notice||notice.kind==='refresh')return notice;
  const author=plainNotificationText(authorName,'plain-v1',40)||'Котик';
  const title=plainNotificationText(record.data.title,record.data.titleFormat)||'Запись';
  return {...notice,historyTitle:notice.title,title:`${author}: ${title}`};
}
export function eligibleTime(createdAt, config, now=Date.now()) {
  const when=millis(createdAt),cutover=millis(config?.cutoverAt);
  return config?.enabled === true && Number.isFinite(when) && Number.isFinite(cutover) && when >= cutover && when <= now+60000 && now-when < 86400000;
}
export function deliveryPolicy(source,config,now=Date.now()) {
  const test=Array.isArray(config?.testActorUids)&&config.testActorUids.includes(source.actor);
  if(test){
    const devices=config.testDeviceAuthUids;
    if(!Array.isArray(devices)||!devices.length||devices.some(id=>!idOk(id)))return null;
    return eligibleTime(source.createdAt,{enabled:true,cutoverAt:config.testCutoverAt},now)?{test:true,devices}:null;
  }
  return eligibleTime(source.createdAt,config,now)?{test:false,devices:null}:null;
}
export function claimable(ledger, now=Date.now()) {
  return !['sent','suppressed','expired'].includes(ledger?.state) && !(ledger?.state === 'sending' && millis(ledger.leaseUntil)>now);
}
