// Appearance-only test switch. Never modifies the production winter parameter.
// node testing/firebase/set-agent-winter.cjs inspect|enable|disable <ignored-deploy-config.json>
const fs=require('node:fs'),path=require('node:path'),{createRequire}=require('node:module');
const mode=process.argv[2],cfg=JSON.parse(fs.readFileSync(process.argv[3],'utf8').replace(/^\uFEFF/,''));
if(!['inspect','enable','disable'].includes(mode))throw Error('Use inspect, enable or disable');
const key='winter_together_agents_enabled',globalKey='winter_together_enabled';
const auth=createRequire(path.join(cfg.firebaseToolsPath,'package.json'))('firebase-tools/lib/auth');
const canonical=x=>Array.isArray(x)?x.map(canonical):x&&typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])])):x;
const equal=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b));
function globalDisabled(template){const p=template.parameters?.[globalKey];return p?.defaultValue?.value==='false'&&Object.values(p.conditionalValues||{}).every(v=>v.value==='false');}
async function main(){
 const account=auth.getGlobalDefaultAccount();if(!account)throw Error('CLI owner login required');
 const token=(await auth.getAccessToken(account.tokens.refresh_token,['https://www.googleapis.com/auth/cloud-platform'])).access_token;
 const url=`https://firebaseremoteconfig.googleapis.com/v1/projects/${cfg.projectId}/remoteConfig`,headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
 async function read(){const r=await fetch(url,{headers});const body=await r.json();if(!r.ok)throw Error(`Remote Config GET ${r.status}`);return {body,etag:r.headers.get('etag')};}
 const {body:before,etag}=await read();
 if(Object.values(before.parameterGroups||{}).some(g=>g.parameters?.[key]||g.parameters?.[globalKey]))throw Error('Grouped winter parameter needs separate review');
 if(!globalDisabled(before))throw Error('Production winter must already be false; this tool will not change it');
 if(mode==='inspect'){console.log(JSON.stringify({globalWinter:false,agentWinter:before.parameters?.[key]?.defaultValue?.value==='true',agentParameterExists:!!before.parameters?.[key],agentConditionalCount:Object.keys(before.parameters?.[key]?.conditionalValues||{}).length,version:before.version?.versionNumber}));return;}
 if(before.parameters?.[key]?.conditionalValues)throw Error('Existing agent conditions need review before replacement');
 if(!etag)throw Error('Missing ETag; refusing unsafe update');
 const after=structuredClone(before);delete after.version;
 after.parameters={...after.parameters,[key]:{defaultValue:{value:String(mode==='enable')},valueType:'BOOLEAN',description:'Test build only. Read exclusively when BuildConfig.AGENT_TEST is true; production winter flag remains separate.'}};
 const backup=path.join(path.dirname(cfg.reportPath),`remote-before-${Date.now()}.private.json`);fs.writeFileSync(backup,JSON.stringify(before,null,2));
 for(const validate of [true,false]){
  const result=await fetch(url+(validate?'?validate_only=true':''),{method:'PUT',headers:{...headers,'If-Match':etag},body:JSON.stringify(after)});
  if(!result.ok)throw Error(`Remote Config ${validate?'validation':'publish'} HTTP ${result.status}`);
 }
 const {body:verified}=await read();
 if(!globalDisabled(verified)||!equal(before.parameters?.[globalKey],verified.parameters?.[globalKey]))throw Error('Production parameter changed unexpectedly');
 const strip=t=>{const c=structuredClone(t);delete c.version;if(c.parameters)delete c.parameters[key];return c;};
 if(!equal(strip(before),strip(verified)))throw Error('Unrelated Remote Config data changed');
 if(verified.parameters?.[key]?.defaultValue?.value!==String(mode==='enable')||verified.parameters[key].conditionalValues)throw Error('Agent flag verification failed');
 const report={at:new Date().toISOString(),globalWinter:false,agentWinter:mode==='enable',globalAndOtherParametersPreserved:true,version:verified.version?.versionNumber};
 fs.writeFileSync(path.join(path.dirname(cfg.reportPath),'agent-winter-state.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
}
main().catch(e=>{console.error(e.message);process.exitCode=1;});
