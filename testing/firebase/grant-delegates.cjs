// Owner-only temporary access for two explicitly selected anonymous test accounts.
// Configuration and results belong in ignored testing/local/, never in source control.
const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const req=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'));
const auth=req('firebase-tools/lib/auth'), api=req('firebase-tools/lib/apiv2');
const {requireAuth}=req('firebase-tools/lib/requireAuth');
const cfg=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
async function request(host,route,method='GET',body){
 const r=await fetch('https://'+host+route,{method,headers:{Authorization:'Bearer '+await api.getAccessToken(),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
 const result=await r.json(); if(!r.ok)throw Error('Firebase API '+r.status+': '+result.error?.message);return result;
}
async function main(){
 if(!/^[a-z][a-z0-9-]+$/.test(cfg.projectId)||!/^[-\w]+$/.test(cfg.spaceId))throw Error('Invalid target');
 if(cfg.delegates?.length!==2||new Set(cfg.delegates.map(d=>d.uid)).size!==2||cfg.delegates.map(d=>d.role).sort().join(',')!=='USER_1,USER_2')throw Error('Exactly two distinct delegates required');
 const account=auth.getGlobalDefaultAccount();if(!account)throw Error('Owner CLI login required');
 const options={project:cfg.projectId};auth.setActiveAccount(options,account);await requireAuth(options,true);
 const users=await request('identitytoolkit.googleapis.com',`/v1/projects/${cfg.projectId}/accounts:lookup`,'POST',{localId:cfg.delegates.map(d=>d.uid)});
 if(users.users?.length!==2||users.users.some(u=>u.disabled||u.email||u.phoneNumber||u.providerUserInfo?.length))throw Error('Expected two enabled anonymous accounts');
 const base=`projects/${cfg.projectId}/databases/(default)/documents`;
 const space=await request('firestore.googleapis.com','/v1/'+base+'/spaces/'+cfg.spaceId);
 const roles=space.fields.roles.mapValue.fields;
 const expiresAt=new Date(Date.now()+24*60*60*1000).toISOString();
 const actors={...(space.fields.actors?.mapValue?.fields||{})};
 const writes=cfg.delegates.map(d=>{
  if(!/^[-\w]{1,128}$/.test(d.uid)||roles[d.canonicalUid]?.stringValue!==d.role||roles[d.uid])throw Error('Invalid canonical role binding');
  actors[d.uid]={stringValue:d.role};
  return {update:{name:base+'/members/'+d.uid,fields:{spaceId:{stringValue:cfg.spaceId},canonicalUid:{stringValue:d.canonicalUid},expiresAt:{timestampValue:expiresAt}}},currentDocument:{exists:false}};
 });
 writes.push({update:{name:space.name,fields:{actors:{mapValue:{fields:actors}}}},updateMask:{fieldPaths:['actors']},currentDocument:{updateTime:space.updateTime}});
 await request('firestore.googleapis.com','/v1/'+base+':commit','POST',{writes});
 console.log('Two anonymous delegates enabled for 24 hours. Canonical membership unchanged.');
}
main().catch(e=>{console.error(e.message);process.exitCode=1});
