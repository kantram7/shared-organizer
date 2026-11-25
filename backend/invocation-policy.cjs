'use strict';
const PUBLIC=new Set(['allUsers','allAuthenticatedUsers']);
function hasInvoker(policy,member){return (policy.bindings||[]).some(b=>b.role==='roles/run.invoker'&&!b.condition&&b.members?.includes(member));}
function hasPublic(policy){return (policy.bindings||[]).some(b=>b.members?.some(m=>PUBLIC.has(m)));}
function withPrivateInvoker(policy,member){
 if(!/^serviceAccount:[^\s]+@[^\s]+\.iam\.gserviceaccount\.com$/.test(member))throw Error('Expected exact service account');
 if(hasPublic(policy))throw Error('Public event endpoint requires separate review');
 const result=structuredClone(policy);result.bindings ||= [];let binding=result.bindings.find(b=>b.role==='roles/run.invoker'&&!b.condition);
 if(!binding){binding={role:'roles/run.invoker',members:[]};result.bindings.push(binding);}if(!binding.members.includes(member))binding.members.push(member);return result;
}
module.exports={hasInvoker,hasPublic,withPrivateInvoker};
