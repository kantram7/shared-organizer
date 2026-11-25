// Read-only deployment audit. Access token remains in memory.
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const cfg=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
const auth=createRequire(path.join(cfg.firebaseToolsPath,'package.json'))('firebase-tools/lib/auth');
const project=cfg.projectId,bucket=cfg.bucketName||`${project}.firebasestorage.app`;
async function main(){
 const invocation=require('node:child_process').spawnSync(process.execPath,[path.join(cfg.repoPath,'backend/verify-invokers-036.cjs'),'inspect',process.argv[2]],{stdio:'inherit'});
 if(invocation.status!==0)throw Error('Invocation verification failed');
 const a=auth.getGlobalDefaultAccount();if(!a)throw Error('CLI login required');const token=(await auth.getAccessToken(a.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 async function get(host,route){const r=await fetch(`https://${host}${route}`,{headers:{Authorization:`Bearer ${token}`}});const d=await r.json();if(!r.ok)throw Error(`Audit ${host} ${r.status}`);return d;}
 const [functions,storage,push,releases,scheduler,...ttl]=await Promise.all([
  get('cloudfunctions.googleapis.com',`/v2/projects/${project}/locations/europe-west3/functions`),
  get('storage.googleapis.com',`/storage/v1/b/${bucket}`),
  get('firestore.googleapis.com',`/v1/projects/${project}/databases/(default)/documents/system/pushDelivery`),
  get('firebaserules.googleapis.com',`/v1/projects/${project}/releases`),
  get('cloudscheduler.googleapis.com',`/v1/projects/${project}/locations/europe-west3/jobs`),
  ...['devices/expiresAt','items/expiresAt','uploadSessions/expiresAt','pushQueue/createdAt'].map(pair=>{const [group,field]=pair.split('/');return get('firestore.googleapis.com',`/v1/projects/${project}/databases/(default)/collectionGroups/${group}/fields/${field}`);})
 ]);
 const report={at:new Date().toISOString(),readOnly:true,functions:(functions.functions||[]).map(f=>({name:f.name.split('/').pop(),state:f.state,runtime:f.buildConfig?.runtime,serviceAccount:f.serviceConfig?.serviceAccountEmail,maxInstances:f.serviceConfig?.maxInstanceCount,region:f.name.split('/')[3]})),
  bucket:{name:storage.name,location:storage.location,softDeletePolicy:storage.softDeletePolicy,versioning:storage.versioning,lifecycle:storage.lifecycle,uniformBucketLevelAccess:storage.iamConfiguration?.uniformBucketLevelAccess?.enabled,publicAccessPrevention:storage.iamConfiguration?.publicAccessPrevention},
  productionPushEnabled:push.fields?.enabled?.booleanValue,productionCutoverAt:push.fields?.cutoverAt?.timestampValue,testActors:push.fields?.testActorUids?.arrayValue?.values?.length||0,testDevices:push.fields?.testDeviceAuthUids?.arrayValue?.values?.length||0,
  rules:(releases.releases||[]).map(r=>({name:r.name,rulesetName:r.rulesetName,updateTime:r.updateTime})),scheduler:(scheduler.jobs||[]).map(j=>({name:j.name,state:j.state,schedule:j.schedule,serviceAccount:j.httpTarget?.oidcToken?.serviceAccountEmail})),ttl:ttl.map(x=>({name:x.name,ttlConfig:x.ttlConfig}))};
 const out=path.join(path.dirname(cfg.reportPath),'deployment-audit.json');fs.writeFileSync(out,JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
