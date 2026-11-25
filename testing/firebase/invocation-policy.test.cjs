const test=require('node:test'),assert=require('node:assert/strict');
const {withPrivateInvoker,hasInvoker}=require('../../backend/invocation-policy.cjs');
const member='serviceAccount:test-runtime@example-project.iam.gserviceaccount.com';
test('service grant preserves etag, unrelated and conditional bindings; repeats idempotently',()=>{
 const original={etag:'unchanged',version:3,bindings:[{role:'roles/run.viewer',members:['user:owner@example.test']},{role:'roles/run.invoker',members:[member],condition:{title:'bounded',expression:'false'}}]};
 const next=withPrivateInvoker(original,member);assert.equal(next.etag,original.etag);assert.deepEqual(next.bindings.slice(0,2),original.bindings);assert.equal(original.bindings.length,2);assert.equal(next.bindings.length,3);assert.ok(hasInvoker(next,member));assert.deepEqual(withPrivateInvoker(next,member),next);
});
test('event service rejects public transport grants without removing them silently',()=>{for(const m of ['allUsers','allAuthenticatedUsers'])assert.throws(()=>withPrivateInvoker({bindings:[{role:'roles/run.invoker',members:[m]}]},member),/Public/);});
test('cannot replace exact service identity with public, user or malformed principal',()=>{for(const m of ['allUsers','user:owner@example.test','serviceAccount:invalid'])assert.throws(()=>withPrivateInvoker({},m),/exact/);});
