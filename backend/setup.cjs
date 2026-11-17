// Run with the Firebase CLI installed outside the repository and an ignored local config.
// Uses the owner's existing CLI login; never creates or exports service-account keys.
const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const req=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'));
const auth=req('firebase-tools/lib/auth');
const api=req('firebase-tools/lib/apiv2');
const {requireAuth}=req('firebase-tools/lib/requireAuth');
const {ensure}=req('firebase-tools/lib/ensureApiEnabled');
const cfg=JSON.parse(fs.readFileSync(process.argv[3],'utf8').replace(/^\uFEFF/,''));
if(!/^[a-z][a-z0-9-]+$/.test(cfg.projectId)||!/^[-\w]+$/.test(cfg.spaceId))throw Error('Invalid project or space ID');
if(cfg.participants.length!==2 || new Set(cfg.participants.map(p=>p.uid)).size!==2 ||
  cfg.participants.map(p=>p.role).sort().join(',')!=='USER_1,USER_2' ||
  cfg.participants.some(p=>!/^[-\w]{1,128}$/.test(p.uid)||typeof p.name!=='string'||p.name.length>80))throw Error('Expected exactly two distinct participants and roles');
async function request(host,route,method='GET',body) {
  const response=await fetch('https://'+host+route,{method,headers:{Authorization:'Bearer '+await api.getAccessToken(),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
  const result=await response.json();
  if(!response.ok){const e=new Error('Firebase API '+response.status+': '+(result.error?.message || response.statusText));e.status=response.status;throw e;}
  return result;
}
function value(v){if(typeof v==='string')return {stringValue:v};if(Array.isArray(v))return {arrayValue:{values:v.map(value)}};return {mapValue:{fields:Object.fromEntries(Object.entries(v).map(([k,v])=>[k,value(v)]))}};}
async function main(){
  const account=auth.getGlobalDefaultAccount();if(!account)throw Error('Run firebase login first');
  const options={project:cfg.projectId};auth.setActiveAccount(options,account);await requireAuth(options,true);
  if(process.argv[2]==='enable') {await ensure(cfg.projectId,'firestore.googleapis.com','firestore');console.log('Firestore API enabled.');return;}
  if(process.argv[2]!=='bootstrap')throw Error('Use enable or bootstrap');
  const users=await request('identitytoolkit.googleapis.com',`/v1/projects/${cfg.projectId}/accounts:lookup`,'POST',{localId:cfg.participants.map(p=>p.uid)});
  if(users.users?.length!==2 || users.users.some(u=>u.disabled))throw Error('Both enabled Firebase Authentication users must already exist');
  const base=`projects/${cfg.projectId}/databases/(default)/documents`;
  const docs=[{path:`spaces/${cfg.spaceId}`,data:{uids:cfg.participants.map(p=>p.uid),roles:Object.fromEntries(cfg.participants.map(p=>[p.uid,p.role])),names:Object.fromEntries(cfg.participants.map(p=>[p.role,p.name]))}},
    ...cfg.participants.map(p=>({path:`members/${p.uid}`,data:{spaceId:cfg.spaceId}}))];
  const writes=[];
  for(const d of docs){
    let existing;try{existing=await request('firestore.googleapis.com','/v1/'+base+'/'+d.path);}catch(e){if(e.status!==404)throw e;}
    const fields=value(d.data).mapValue.fields;
    if(existing){
      const canonical=x=>Array.isArray(x)?x.map(canonical):x && typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])])):x;
      // Do not overwrite any existing membership or space; stop for review instead.
      if(JSON.stringify(canonical(existing.fields))!==JSON.stringify(canonical(fields)))throw Error('A target document already exists; review before changing access.');
      continue;
    }
    writes.push({update:{name:base+'/'+d.path,fields},currentDocument:{exists:false}});
  }
  if(writes.length)await request('firestore.googleapis.com','/v1/'+base+':commit','POST',{writes});
  // Verify counts and bindings without printing account identifiers or names.
  for(const d of docs)await request('firestore.googleapis.com','/v1/'+base+'/'+d.path);
  console.log('Verified one space and two participant bindings. No demo data uploaded.');
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
