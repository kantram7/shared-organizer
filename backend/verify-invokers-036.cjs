// Repair only missing service-scoped Eventarc invocation grants. No public event endpoints.
// node backend/verify-invokers-036.cjs inspect|apply <private-config.json>
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const mode=process.argv[2],cfg=JSON.parse(fs.readFileSync(process.argv[3],'utf8').replace(/^\uFEFF/,''));
if(!['inspect','apply'].includes(mode)||!/^[a-z][a-z0-9-]+$/.test(cfg.projectId))throw Error('Invalid mode/project');
const runtime=`organizer-runtime@${cfg.projectId}.iam.gserviceaccount.com`,targets=['messageCreated','messageRead','recordRevisionCreated','attachmentMessageDeleted','guardAttachmentObject'];
const {hasInvoker,hasPublic,withPrivateInvoker}=require('./invocation-policy.cjs');
const auth=createRequire(path.join(cfg.firebaseToolsPath,'package.json'))('firebase-tools/lib/auth');
async function main(){const account=auth.getGlobalDefaultAccount();if(!account)throw Error('Owner CLI login required');const token=(await auth.getAccessToken(account.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 async function api(host,route,body){const r=await fetch(`https://${host}${route}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw Error(`Invocation IAM HTTP ${r.status}`);return d;}
 const push=await api('firestore.googleapis.com',`/v1/projects/${cfg.projectId}/databases/(default)/documents/system/pushDelivery`);if(mode==='apply'&&push.fields.enabled?.booleanValue!==false)throw Error('Repair requires disabled production delivery');
 const rows=[];
 for(const name of targets){
  const fn=await api('cloudfunctions.googleapis.com',`/v2/projects/${cfg.projectId}/locations/europe-west3/functions/${name}`);
  if(fn.eventTrigger?.serviceAccountEmail!==runtime||fn.labels?.['firebase-functions-codebase']!=='organizer')throw Error('Unexpected trigger identity/codebase');
  const service=fn.serviceConfig.service;if(service!==`projects/${cfg.projectId}/locations/europe-west3/services/${name.toLowerCase()}`)throw Error('Unexpected service target');
  const trigger=await api('eventarc.googleapis.com','/v1/'+fn.eventTrigger.trigger);if(trigger.serviceAccount!==runtime)throw Error('Eventarc account differs');
  const iam=await api('run.googleapis.com','/v2/'+service+':getIamPolicy');
  if(hasPublic(iam))throw Error('Public trigger binding requires separate review');
  const member='serviceAccount:'+runtime;let changed=false;
  if(!hasInvoker(iam,member)&&mode==='apply'){await api('run.googleapis.com','/v2/'+service+':setIamPolicy',{policy:withPrivateInvoker(iam,member)});changed=true;}
  const after=await api('run.googleapis.com','/v2/'+service+':getIamPolicy');const valid=after.bindings?.some(b=>b.role==='roles/run.invoker'&&!b.condition&&b.members.includes(member))===true;
  if(!valid)throw Error('Invocation grant readback failed');rows.push({function:name,changed,exactTriggerInvoker:valid,public:false});
 }
 // Callable transport accepts Firebase ID tokens; all application permissions remain inside handlers.
 for(const name of ['prepareAttachments','commitAttachmentMessage','attachmentDownload','cleanupAttachments']){
  const fn=await api('cloudfunctions.googleapis.com',`/v2/projects/${cfg.projectId}/locations/europe-west3/functions/${name}`);
  const iam=await api('run.googleapis.com','/v2/'+fn.serviceConfig.service+':getIamPolicy');
  const callable=name!=='cleanupAttachments';
  if(callable ? !hasInvoker(iam,'allUsers') : (hasPublic(iam)||!hasInvoker(iam,'serviceAccount:'+runtime)))throw Error('Unexpected callable/scheduler invocation policy');
  rows.push({function:name,changed:false,callableAuthInHandler:callable,public:callable,privateScheduler:!callable});
 }
 const jobs=await api('cloudscheduler.googleapis.com',`/v1/projects/${cfg.projectId}/locations/europe-west3/jobs`);
 const cleanup=jobs.jobs?.find(j=>j.name.endsWith('/firebase-schedule-cleanupAttachments-europe-west3'));
 if(!cleanup||cleanup.httpTarget?.oidcToken?.serviceAccountEmail!==runtime)throw Error('Unexpected cleanup scheduler identity');
 const afterPush=await api('firestore.googleapis.com',`/v1/projects/${cfg.projectId}/databases/(default)/documents/system/pushDelivery`);
 if(afterPush.updateTime!==push.updateTime)throw Error('Push config changed during repair; inspect concurrent operator');
 const report={at:new Date().toISOString(),mode,rows,projectIamChanged:false,callablesChanged:false,pushPolicyUnchanged:true,productionEnabled:push.fields.enabled?.booleanValue};fs.writeFileSync(path.join(path.dirname(cfg.reportPath),'invocation-iam-repair.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
