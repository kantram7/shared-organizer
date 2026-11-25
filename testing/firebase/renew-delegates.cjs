// Renew only existing self-canonical anonymous identities in an isolated test-only space.
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const cfg=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
const auth=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'))('firebase-tools/lib/auth');
async function main(){
 if(cfg.delegates?.length!==2||new Set(cfg.delegates.map(d=>d.uid)).size!==2||cfg.delegates.map(d=>d.role).sort().join(',')!=='USER_1,USER_2')throw Error('Exactly the two approved delegates required');
 if(cfg.delegates.some(d=>d.canonicalUid!==d.uid))throw Error('Test identities must be self-canonical; real-user delegation is forbidden');
 const account=auth.getGlobalDefaultAccount();if(!account)throw Error('CLI login required');const token=(await auth.getAccessToken(account.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 async function request(host,route,method='GET',body){const r=await fetch(`https://${host}${route}`,{method,headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw Error(`Delegate operation HTTP ${r.status}`);return d;}
 const users=await request('identitytoolkit.googleapis.com',`/v1/projects/${cfg.projectId}/accounts:lookup`,'POST',{localId:cfg.delegates.map(d=>d.uid)});
 if(users.users?.length!==2||users.users.some(u=>u.disabled||u.email||u.phoneNumber||u.providerUserInfo?.length))throw Error('Both accounts must remain enabled anonymous test identities');
 const base=`projects/${cfg.projectId}/databases/(default)/documents`,space=await request('firestore.googleapis.com',`/v1/${base}/spaces/${cfg.spaceId}`);
 if(space.fields.testOnly?.booleanValue!==true)throw Error('Only an isolated test-only space can be renewed');
 const actors={...(space.fields.actors?.mapValue?.fields||{})},writes=[],expiresAt=new Date(Date.now()+86400000).toISOString();
 for(const d of cfg.delegates){
  if(space.fields.roles?.mapValue?.fields?.[d.canonicalUid]?.stringValue!==d.role)throw Error('Canonical role changed; review required');
  if(actors[d.uid]&&actors[d.uid].stringValue!==d.role)throw Error('Existing actor binding differs');
  const m=await request('firestore.googleapis.com',`/v1/${base}/members/${d.uid}`);
  if(m.fields.testOnly?.booleanValue!==true)throw Error('Only a test-only membership can be renewed');
  if(m.fields.spaceId?.stringValue!==cfg.spaceId||m.fields.canonicalUid?.stringValue!==d.canonicalUid)throw Error('Existing membership binding differs');
  actors[d.uid]={stringValue:d.role};writes.push({update:{name:m.name,fields:{expiresAt:{timestampValue:expiresAt}}},updateMask:{fieldPaths:['expiresAt']},currentDocument:{updateTime:m.updateTime}});
 }
 writes.push({update:{name:space.name,fields:{actors:{mapValue:{fields:actors}}}},updateMask:{fieldPaths:['actors']},currentDocument:{updateTime:space.updateTime}});
 await request('firestore.googleapis.com',`/v1/projects/${cfg.projectId}/databases/(default)/documents:commit`,'POST',{writes});
 if(process.argv[3])fs.writeFileSync(process.argv[3],JSON.stringify({enabled:false,testActorUids:cfg.delegates.map(d=>d.uid),testDeviceAuthUids:cfg.delegates.map(d=>d.uid),testCutoverAt:new Date().toISOString()},null,2));
 console.log('Renewed the two existing anonymous delegates until '+expiresAt+'. Test delivery configuration prepared locally, not enabled.');
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
