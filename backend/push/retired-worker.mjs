// Final Cloudflare entrypoint after verified Functions handover. No delivery bindings or network access.
export default {
 fetch(request) {
  const path=new URL(request.url).pathname;
  return request.method==='GET'&&path==='/health'
   ? Response.json({ok:true,delivery:'retired',successor:'firebase-functions'})
   : new Response('Retired', {status:410});
 },
 scheduled() { /* Deliberately inert even if a stale schedule fires. */ }
};
