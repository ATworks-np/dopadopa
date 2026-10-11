const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');

function harness() {
  const elements = new Map(), timers = new Map(), requests = [];
  let next = 0;
  const element = () => ({disabled:false, checked:false, textContent:'',
    querySelector:()=>({}), querySelectorAll:()=>[], replaceChildren(){}, append(){}});
  const context = vm.createContext({AbortController, URLSearchParams, Date,
    document:{getElementById(id){if(!elements.has(id)) elements.set(id,element()); return elements.get(id);},createElement:element},
    setTimeout(callback, ms){timers.set(++next,{callback,ms});return next;},
    clearTimeout(id){timers.delete(id);},
    fetch(url, options){const request={url,options};requests.push(request);return new Promise((resolve,reject)=>{
      request.resolve=resolve;
      options.signal.addEventListener('abort',()=>reject(new Error('aborted')),{once:true});
    });}
  });
  vm.runInContext(fs.readFileSync(__dirname+'/app.js','utf8'),context);
  // Initial sample load is also bounded; abort it and ignore its UI error.
  timers.get(1).callback();
  return {context,timers,requests};
}

test('analysis deadline aborts a pending request and clears its timer',async()=>{
  const h=harness();
  const pending=vm.runInContext("jsonFetch('/v1/analyses',{method:'POST'})",h.context);
  assert.equal(h.timers.get(2).ms,65000);
  const rejection=assert.rejects(pending,/aborted/);
  h.timers.get(2).callback();
  await rejection;
  assert.equal(h.requests[1].options.signal.aborted,true);
  assert.equal(h.timers.has(2),false);
});

test('account change during credential refresh prevents an API request',async()=>{
  const h=harness();
  vm.runInContext("auth={idToken:'expired-test-token',refreshToken:'test-refresh',expires:0}",h.context);
  const pending=vm.runInContext("api('/v1/sessions')",h.context);
  assert.equal(h.timers.get(2).ms,15000);
  vm.runInContext('epoch++; auth=null',h.context);
  const rejection=assert.rejects(pending,/account changed/);
  h.requests[1].resolve({ok:true,json:async()=>({id_token:'new-test-token',refresh_token:'new-test-refresh',expires_in:'3600'})});
  await rejection;
  assert.equal(h.requests.some(r=>r.url==='/v1/sessions'),false);
});

test('auth failure uses safe messages and never exposes arbitrary provider content',async()=>{
  for(const [code,known] of [['TOO_MANY_ATTEMPTS_TRY_LATER',true],['secret@example.invalid sensitive provider body',false]]) {
    const h=harness();
    const pending=vm.runInContext("jsonFetch('https://identitytoolkit.googleapis.com/v1/accounts:signUp',{method:'POST'})",h.context);
    const rejection=assert.rejects(pending,error=>{
      assert.equal(error.message,'request failed');
      assert.equal(!!error.userMessage,known);
      assert.equal((error.userMessage||'').includes('secret@example.invalid'),false);
      return true;
    });
    h.requests[1].resolve({ok:false,json:async()=>({error:{message:code}})});
    await rejection;
    assert.equal(h.timers.has(2),false);
  }
});

test('batch carries the captured generation and refuses consent changes during its lookup',async()=>{
  for(const revoke of [false,true]) {
    const h=harness();
    vm.runInContext("auth={idToken:'test-token',expires:Date.now()+3600000}",h.context);
    const pending=vm.runInContext("api('/v1/sessions:batch','POST',{session_id:'sample'})",h.context);
    await new Promise(setImmediate);
    assert.equal(h.requests[1].url,'/v1/user/generation');
    if(revoke) vm.runInContext('epoch++',h.context);
    const rejection=revoke ? assert.rejects(pending,/account changed/) : null;
    h.requests[1].resolve({ok:true,json:async()=>({generation:7})});
    await new Promise(setImmediate);
    if(revoke) {
      await rejection;
      assert.equal(h.requests.length,2);
    } else {
      assert.equal(h.requests[2].url,'/v1/sessions:batch');
      assert.equal(h.requests[2].options.headers['X-DopaGuard-Generation'],'7');
      h.requests[2].resolve({ok:true,json:async()=>({session_id:'sample'})});
      await pending;
    }
  }
});
