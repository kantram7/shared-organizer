// Explicit operator-run provisioning. No credentials are printed or written.
// Usage: node backend/deploy-036.cjs plan|provision|deploy <ignored-config.json>
// Config: {projectId, bucketName?, repoPath, firebaseToolsPath, reportPath}
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module'),{spawnSync}=require('node:child_process');
const mode=process.argv[2],cfg=JSON.parse(fs.readFileSync(process.argv[3],'utf8').replace(/^\uFEFF/,''));
if(!['plan','provision','deploy'].includes(mode)||!/^[a-z][a-z0-9-]+$/.test(cfg.projectId))throw Error('Invalid deployment mode/project');
const project=cfg.projectId,bucket=cfg.bucketName||`${project}.firebasestorage.app`,runtime=`organizer-runtime@${project}.iam.gserviceaccount.com`;
if(bucket!==`${project}.firebasestorage.app`)throw Error('Review required for a non-default bucket');
const services=['cloudfunctions.googleapis.com','run.googleapis.com','cloudbuild.googleapis.com','artifactregistry.googleapis.com','eventarc.googleapis.com','pubsub.googleapis.com','cloudscheduler.googleapis.com','firebasestorage.googleapis.com','storage.googleapis.com','iam.googleapis.com','iamcredentials.googleapis.com','firebaserules.googleapis.com','fcm.googleapis.com'];
const plan={project,region:'europe-west3',bucket,runtime,productionPushEnabled:false,changes:[
 {type:'enableAPIs',services},
 {type:'createRuntimeServiceAccount',accountId:'organizer-runtime'},
 {type:'runtimeProjectRoles',roles:['roles/datastore.user','roles/firebasecloudmessaging.admin','roles/eventarc.eventReceiver']},
 {type:'runtimeSelfSigning',role:'roles/iam.serviceAccountTokenCreator',resource:runtime},
 {type:'createDefaultFirebaseBucket',location:'europe-west3',publicAccessPrevention:'enforced',uniformBucketLevelAccess:true,softDeleteSeconds:0,versioning:false,lifecycleDeleteAgeDays:31,lifecyclePrefix:'spaces/'},
 {type:'runtimeBucketRole',role:'roles/storage.objectAdmin',bucket},
 {type:'initializePushConfigIfMissing',path:'system/pushDelivery',enabled:false,testActorUids:[],testDeviceAuthUids:[]},
 {type:'deploy',targets:['functions:organizer','firestore:rules','firestore:indexes','storage'],functions:9},
 {type:'keepExisting',resources:['Cloudflare worker and schedule','production push disabled','all existing accounts memberships and organizer records']}
]};
if(mode==='plan'){console.log(JSON.stringify(plan,null,2));process.exit(0);}
const dep=createRequire(path.join(cfg.firebaseToolsPath,'package.json')),auth=dep('firebase-tools/lib/auth');
let token,report={at:new Date().toISOString(),mode,project,steps:[]};
async function request(host,route,method='GET',body,allow404=false){
 const res=await fetch(`https://${host}${route}`,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
 const result=await res.json().catch(()=>({}));if(allow404&&res.status===404)return null;
 if(!res.ok)throw Object.assign(Error(`${host} ${method} failed: HTTP ${res.status} ${result.error?.status||''}`),{status:res.status});return result;
}
async function operation(host,result){
 if(!result?.name||result.done||!(result.name.includes('/operations/')||result.name.startsWith('operations/')))return result;
 for(let i=0;i<120;i++){await new Promise(r=>setTimeout(r,2000));const next=await request(host,'/v1/'+result.name);if(next.done){if(next.error)throw Error('Cloud operation failed code '+next.error.code);return next;}}
 throw Error('Cloud operation is still pending; inspect before retry');
}
function addBinding(policy,role,member){const bindings=policy.bindings||(policy.bindings=[]);let b=bindings.find(x=>x.role===role&&!x.condition);if(!b){b={role,members:[]};bindings.push(b);}if(!b.members.includes(member))b.members.push(member);return policy;}
async function saveReport(){if(cfg.reportPath)fs.writeFileSync(cfg.reportPath,JSON.stringify(report,null,2));}
async function main(){
 const account=auth.getGlobalDefaultAccount();if(!account)throw Error('Firebase CLI login required');
 token=(await auth.getAccessToken(account.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 if(mode==='deploy'){
  const config=await request('firestore.googleapis.com',`/v1/projects/${project}/databases/(default)/documents/system/pushDelivery`,'GET',undefined,true);
  if(config?.fields?.enabled?.booleanValue!==false)throw Error('Production delivery must be explicitly disabled for initial deployment');
  const envPath=path.join(cfg.repoPath,'backend/functions',`.env.${project}`);
  fs.writeFileSync(envPath,`CHAT_ATTACHMENTS_BUCKET=${bucket}\nORGANIZER_RUNTIME_SERVICE_ACCOUNT=${runtime}\n`);
  const cli=path.join(cfg.firebaseToolsPath,'node_modules/firebase-tools/lib/bin/firebase.js');
  const args=[cli,'deploy','--only','functions:organizer,firestore:rules,firestore:indexes,storage','--project',project,'--non-interactive'];
  if(cfg.approveInitialRetry===true){
    const inventory=await request('cloudfunctions.googleapis.com',`/v2/projects/${project}/locations/-/functions`);
    const approved=new Set(['attachmentDownload','attachmentMessageDeleted','cleanupAttachments','commitAttachmentMessage','guardAttachmentObject','messageCreated','messageRead','prepareAttachments','recordRevisionCreated']);
    if(inventory.functions?.some(f=>!approved.has(f.name.split('/').pop())||!f.name.includes('/locations/europe-west3/')||f.labels?.['firebase-functions-codebase']!=='organizer'))throw Error('Retry approval would affect an unreviewed function inventory');
    args.push('--force');
  }
  const cliEnv={...process.env,FIREBASE_FUNCTIONS_DISCOVERY_OUTPUT_PATH:'true'};
  const currentPath=process.env.PATH||process.env.Path||'';
  for(const key of Object.keys(cliEnv))if(key.toLowerCase()==='path')delete cliEnv[key];
  cliEnv.PATH=path.dirname(process.execPath)+path.delimiter+currentPath;
  const result=spawnSync(process.execPath,args,{cwd:cfg.repoPath,stdio:'inherit',env:cliEnv});
  if(result.status!==0)throw Error('Firebase deploy did not complete');
  const invocation=spawnSync(process.execPath,[path.join(cfg.repoPath,'backend/verify-invokers-036.cjs'),'apply',process.argv[3]],{cwd:cfg.repoPath,stdio:'inherit'});
  if(invocation.status!==0)throw Error('Deployment invocation verification failed');report.steps.push('exact-service-invokers-verified');report.steps.push('deployed-disabled-functions-and-rules');await saveReport();return;
 }
 const billing=await request('cloudbilling.googleapis.com',`/v1/projects/${project}/billingInfo`);if(!billing.billingEnabled)throw Error('Blaze billing must already be enabled by owner');
 const enabled=await request('serviceusage.googleapis.com',`/v1/projects/${project}/services:batchEnable`,'POST',{serviceIds:services});await operation('serviceusage.googleapis.com',enabled);report.steps.push('required-apis-enabled');
 const saRoute=`/v1/projects/${project}/serviceAccounts/${runtime}`;
 let sa=await request('iam.googleapis.com',saRoute,'GET',undefined,true);
 if(!sa)sa=await request('iam.googleapis.com',`/v1/projects/${project}/serviceAccounts`,'POST',{accountId:'organizer-runtime',serviceAccount:{displayName:'Organizer runtime'}});
 const member=`serviceAccount:${runtime}`;
 const pp=await request('cloudresourcemanager.googleapis.com',`/v1/projects/${project}:getIamPolicy`,'POST',{options:{requestedPolicyVersion:3}});
 for(const role of ['roles/datastore.user','roles/firebasecloudmessaging.admin','roles/eventarc.eventReceiver'])addBinding(pp,role,member);
 await request('cloudresourcemanager.googleapis.com',`/v1/projects/${project}:setIamPolicy`,'POST',{policy:pp});
 const sp=await request('iam.googleapis.com',saRoute+':getIamPolicy?options.requestedPolicyVersion=3','POST');addBinding(sp,'roles/iam.serviceAccountTokenCreator',member);
 await request('iam.googleapis.com',saRoute+':setIamPolicy','POST',{policy:sp});report.steps.push('dedicated-runtime-and-scoped-signing-ready');
 let b=await request('storage.googleapis.com',`/storage/v1/b/${bucket}`,'GET',undefined,true);
 if(!b){await request('firebasestorage.googleapis.com',`/v1alpha/projects/${project}/defaultBucket`,'POST',{location:'europe-west3'});b=await request('storage.googleapis.com',`/storage/v1/b/${bucket}`);}
 if(b.location?.toLowerCase()!=='europe-west3'||b.retentionPolicy?.isLocked)throw Error('Existing bucket location/retention conflicts with approved design');
 const objectCheck=await request('storage.googleapis.com',`/storage/v1/b/${bucket}/o?maxResults=1`);
 if(objectCheck.items?.length)throw Error('Bucket is not empty; review its lifecycle before changing retention');
 await request('storage.googleapis.com',`/storage/v1/b/${bucket}`,'PATCH',{iamConfiguration:{uniformBucketLevelAccess:{enabled:true},publicAccessPrevention:'enforced'},softDeletePolicy:{retentionDurationSeconds:'0'},versioning:{enabled:false},lifecycle:{rule:[{action:{type:'Delete'},condition:{age:31,matchesPrefix:['spaces/']}}]}});
 const bp=await request('storage.googleapis.com',`/storage/v1/b/${bucket}/iam?optionsRequestedPolicyVersion=3`);addBinding(bp,'roles/storage.objectAdmin',member);
 await request('storage.googleapis.com',`/storage/v1/b/${bucket}/iam`,'PUT',bp);
 b=await request('storage.googleapis.com',`/storage/v1/b/${bucket}`);report.bucket={name:b.name,location:b.location,softDeletePolicy:b.softDeletePolicy,versioning:b.versioning,lifecycle:b.lifecycle,iamConfiguration:b.iamConfiguration};report.steps.push('private-bucket-expiry-settings-verified');
 const doc=`projects/${project}/databases/(default)/documents/system/pushDelivery`;
 const old=await request('firestore.googleapis.com','/v1/'+doc,'GET',undefined,true);
 if(old?.fields?.enabled?.booleanValue===true)throw Error('Existing active delivery config will not be overwritten');
 if(!old)await request('firestore.googleapis.com',`/v1/projects/${project}/databases/(default)/documents:commit`,'POST',{writes:[{update:{name:doc,fields:{enabled:{booleanValue:false},testActorUids:{arrayValue:{values:[]}},testDeviceAuthUids:{arrayValue:{values:[]}}}},currentDocument:{exists:false}}]});
 report.steps.push('production-delivery-remains-disabled');await saveReport();console.log('Provisioning verified; no Cloudflare change and no production delivery enabled.');
}
main().catch(async error=>{report.error=error.message;await saveReport();console.error(error.message);process.exitCode=1;});
