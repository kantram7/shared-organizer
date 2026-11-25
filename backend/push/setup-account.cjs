throw new Error('Cloudflare push setup retired; Firebase Functions now own delivery. Do not recreate legacy keys.');
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const req=createRequire(path.join(process.env.KOTIKI_FIREBASE_TOOLS,'package.json'));
const auth=req('firebase-tools/lib/auth'),api=req('firebase-tools/lib/apiv2'),{requireAuth}=req('firebase-tools/lib/requireAuth'),{ensure}=req('firebase-tools/lib/ensureApiEnabled');
const local=path.resolve(process.argv[2]);
const cfg=JSON.parse(fs.readFileSync(path.join(local,'workspace.json'),'utf8').replace(/^\uFEFF/,''));
const project=cfg.projectId;
if(!/^[a-z][a-z0-9-]+$/.test(project))throw Error('Invalid project');
async function call(host,route,method='GET',body){
 const r=await fetch('https://'+host+route,{method,headers:{Authorization:'Bearer '+await api.getAccessToken(),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
 const result=await r.json();if(!r.ok){const e=Error('Setup API '+r.status+': '+(result.error?.message||''));e.status=r.status;throw e;}return result;
}
async function main(){
 const options={project};auth.setActiveAccount(options,auth.getGlobalDefaultAccount());await requireAuth(options,true);
 for(const service of ['iam.googleapis.com','fcm.googleapis.com'])await ensure(project,service,'push bridge');
 const accountId='shared-organizer-push',email=`${accountId}@${project}.iam.gserviceaccount.com`,saPath=`/v1/projects/${project}/serviceAccounts/${email}`;
 try{await call('iam.googleapis.com',saPath);}catch(e){if(e.status!==404)throw e;await call('iam.googleapis.com',`/v1/projects/${project}/serviceAccounts`,'POST',{accountId,serviceAccount:{displayName:'Organizer push delivery'}});}
 const roleName=`projects/${project}/roles/organizerPushDelivery`;
 const permissions=['cloudmessaging.messages.create','datastore.entities.get','datastore.entities.list','datastore.entities.delete'];
 let role;try{role=await call('iam.googleapis.com','/v1/'+roleName);}catch(e){if(e.status!==404)throw e;role=await call('iam.googleapis.com',`/v1/projects/${project}/roles`,'POST',{roleId:'organizerPushDelivery',role:{title:'Organizer push delivery',description:'Read delivery queue and targets, send FCM, remove processed queue items.',includedPermissions:permissions,stage:'GA'}});}
 if(JSON.stringify(role.includedPermissions.slice().sort())!==JSON.stringify(permissions.slice().sort()))throw Error('Existing delivery role differs; review before granting');
 const policy=await call('cloudresourcemanager.googleapis.com',`/v1/projects/${project}:getIamPolicy`,'POST',{options:{requestedPolicyVersion:3}});
 const member='serviceAccount:'+email;policy.bindings||=[];
 let binding=policy.bindings.find(b=>b.role===roleName&&!b.condition);
 if(!binding){binding={role:roleName,members:[]};policy.bindings.push(binding);}
 if(!binding.members.includes(member)){binding.members.push(member);await call('cloudresourcemanager.googleapis.com',`/v1/projects/${project}:setIamPolicy`,'POST',{policy});}
 const keyFile=path.join(local,'service-account-push.json');
 if(!fs.existsSync(keyFile)){
   const key=await call('iam.googleapis.com',saPath+'/keys','POST',{privateKeyType:'TYPE_GOOGLE_CREDENTIALS_FILE',keyAlgorithm:'KEY_ALG_RSA_2048'});
   fs.writeFileSync(keyFile,Buffer.from(key.privateKeyData,'base64'),{flag:'wx',mode:0o600});
 }
 const credentials=JSON.parse(fs.readFileSync(keyFile,'utf8'));
 if(credentials.project_id!==project||credentials.client_email!==email)throw Error('Local key does not match dedicated push account');
 fs.writeFileSync(path.join(local,'worker-secrets.json'),JSON.stringify({GOOGLE_SERVICE_ACCOUNT:JSON.stringify(credentials),FIREBASE_PROJECT:project,SPACE_ID:cfg.spaceId}),{mode:0o600});
 console.log('Dedicated push account and four-permission role verified. Worker secrets saved only in ignored local Firebase folder.');
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
