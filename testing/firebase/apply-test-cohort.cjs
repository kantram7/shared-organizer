// Apply only the two previously approved and currently active delegate identities.
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const cfg=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,'')),cohort=JSON.parse(fs.readFileSync(process.argv[3],'utf8').replace(/^\uFEFF/,'')),delegation=JSON.parse(fs.readFileSync(process.argv[4],'utf8').replace(/^\uFEFF/,''));
const ids=delegation.delegates.map(d=>d.uid).sort();
if(ids.length!==2||new Set(ids).size!==2||cohort.enabled!==false||JSON.stringify([...cohort.testActorUids].sort())!==JSON.stringify(ids)||JSON.stringify([...cohort.testDeviceAuthUids].sort())!==JSON.stringify(ids)||delegation.projectId!==cfg.projectId)throw Error('Cohort differs from the two approved delegates');
const auth=createRequire(path.join(cfg.firebaseToolsPath,'package.json'))('firebase-tools/lib/auth');
async function main(){
 const a=auth.getGlobalDefaultAccount();if(!a)throw Error('CLI login required');const token=(await auth.getAccessToken(a.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 async function request(route,method='GET',body){const r=await fetch('https://firestore.googleapis.com/v1/'+route,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw Error('Cohort API HTTP '+r.status);return d;}
 const base=`projects/${cfg.projectId}/databases/(default)/documents`;
 for(const d of delegation.delegates){const m=await request(`${base}/members/${d.uid}`);if(m.fields.spaceId?.stringValue!==delegation.spaceId||m.fields.canonicalUid?.stringValue!==d.canonicalUid||Date.parse(m.fields.expiresAt?.timestampValue)<=Date.now())throw Error('Delegate is missing, expired or rebound');}
 const ref=`${base}/system/pushDelivery`,before=await request(ref);if(before.fields.enabled?.booleanValue!==false)throw Error('Production delivery must remain explicitly false');
 const testCutoverAt=new Date().toISOString(),fields={enabled:{booleanValue:false},testActorUids:{arrayValue:{values:ids.map(stringValue=>({stringValue}))}},testDeviceAuthUids:{arrayValue:{values:ids.map(stringValue=>({stringValue}))}},testCutoverAt:{timestampValue:testCutoverAt}};
 await request(`projects/${cfg.projectId}/databases/(default)/documents:commit`,'POST',{writes:[{update:{name:ref,fields},updateMask:{fieldPaths:Object.keys(fields)},currentDocument:{updateTime:before.updateTime}}]});
 const after=await request(ref),read=k=>(after.fields[k]?.arrayValue?.values||[]).map(x=>x.stringValue).sort();
 if(after.fields.enabled?.booleanValue!==false||JSON.stringify(read('testActorUids'))!==JSON.stringify(ids)||JSON.stringify(read('testDeviceAuthUids'))!==JSON.stringify(ids))throw Error('Cohort verification failed');
 const report={at:new Date().toISOString(),productionPushEnabled:false,testActorCount:2,testDeviceCount:2,exactApprovedIdentitiesVerified:true,testCutoverAt};fs.writeFileSync(path.join(path.dirname(cfg.reportPath),'test-cohort-applied.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
