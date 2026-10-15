const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
function harness(){
 const nodes=new Map(), requests=[];
 function el(){return {children:[],value:'',checked:false,disabled:false,textContent:'',classList:{add(){},remove(){}},append(n){this.children.push(n);},replaceChildren(){this.children=[];},setAttribute(){},addEventListener(){},querySelector(){return null;},querySelectorAll(){return [];},showModal(){this.open=true;},close(){this.open=false;},pause(){},load(){},focus(){}};}
 const context=vm.createContext({document:{getElementById(id){if(!nodes.has(id))nodes.set(id,el());return nodes.get(id);},createElement:el,createTextNode:t=>({textContent:t}),body:el()},window:{scrollTo(){},addEventListener(){}},crypto:{randomUUID:()=> 'test-id'},AbortController,Date,setTimeout,clearTimeout,fetch:async(url,opts)=>{requests.push({url,opts});if(url==='/v1/user/generation')return {ok:true,json:async()=>({generation:3})};if(url==='/v1/sessions:batch')return {ok:true,json:async()=>({})};return {ok:true,json:async()=>({status:'completed',result:{goal_alignment:'aligned',uncertainty:'demo',segments:[]},trace:[{tool:'get_user_goals',count:1}]})};}});
 vm.runInContext(fs.readFileSync(__dirname+'/demo.js','utf8'),context);
 return {context,nodes,requests,run:s=>vm.runInContext(s,context)};
}
test('login is shown first; analysis requires identity, goals, consent and observations',()=>{
 const h=harness();assert.equal(h.nodes.get('loginDialog').open,true);
 h.run('agentControls()');assert.equal(h.nodes.get('runAgent').disabled,true);
 h.run("observe('CLICK','before consent')");assert.equal(h.run('observations.length'),0);
});
test('real API sequence preserves generation, sample provenance and selected goal',async()=>{
 const h=harness();h.run("agentAuth={token:'test-token',expires:Date.now()+60000};savedGoals=new Set(['学習・スキルアップ']);$('agentConsent').checked=true;observe('CLICK','数学');agentControls()");
 await h.nodes.get('runAgent').onclick();
 assert.deepEqual(h.requests.map(r=>r.url),['/v1/user/generation','/v1/sessions:batch','/v1/analyses']);
 const request=h.requests[1],body=JSON.parse(request.opts.body);
 assert.equal(request.opts.headers['X-DopaGuard-Generation'],'3');
 assert.equal(request.opts.headers.Authorization,'Bearer test-token');
 assert.equal(body.user_goal,'学習・スキルアップ');assert.equal(body.events[0].data_origin,'sample');assert.equal(body.events[0].event_type,'CLICK');
 assert.equal(body.events[0].app_id,'dopaguard-web-demo');
 assert.match(h.nodes.get('agentStatus').textContent,/分析完了/);
});
test('revocation during generation lookup prevents uploading and clears observations',async()=>{
 const h=harness();let resolve;
 h.context.fetch=()=>new Promise(r=>{resolve=r;});
 h.run("agentAuth={token:'test-token',expires:Date.now()+60000};savedGoals=new Set(['情報収集']);$('agentConsent').checked=true;observe('CLICK','news');agentControls()");
 const pending=h.nodes.get('runAgent').onclick();
 h.nodes.get('agentConsent').checked=false;h.nodes.get('agentConsent').onchange();
 resolve({ok:true,json:async()=>({generation:1})});await pending;
 assert.equal(h.run('observations.length'),0);assert.equal(h.nodes.get('runAgent').disabled,true);
 assert.match(h.nodes.get('agentStatus').textContent,/同意を解除/);
});
test('video selection sends click then destination title; scroll sends no invented content',async()=>{
 const h=harness();h.nodes.get('player').play=()=>Promise.resolve();
 h.run("agentAuth={token:'test-token',expires:Date.now()+60000};savedGoals=new Set(['学習']);$('agentConsent').checked=true;play(5,true);markUserScroll();recordScroll();recordScroll();");
 assert.equal(h.run('observations.length'),3);
 assert.equal(h.run('observations[0].event_type'),'CLICK');
 assert.equal(h.run('observations[1].event_type'),'WINDOW_CHANGE');
 assert.equal(h.run('observations[0].text===observations[1].text'),true);
 assert.equal(h.run('observations[2].event_type'),'SCROLL');
 assert.equal(h.run('observations[2].text'),null);
 assert.equal(h.run('observations[2].text_status'),'missing');
 h.run('agentControls()');await h.nodes.get('runAgent').onclick();
 const batch=JSON.parse(h.requests[1].opts.body);
 assert.deepEqual(batch.events.map(e=>e.event_type),['CLICK','WINDOW_CHANGE','SCROLL']);
 assert.equal(batch.events[2].source,'none');assert.equal(batch.events[2].observability,'unavailable');
});
test('programmatic scrolling and hidden goal screen are not collected',()=>{
 const h=harness();h.run("agentAuth={token:'test-token',expires:Date.now()+60000};$('agentConsent').checked=true;recordScroll();");
 assert.equal(h.run('observations.length'),0);
 h.run("$('watch').hidden=true;markUserScroll();recordScroll();observe('WINDOW_CHANGE','private screen');");
 assert.equal(h.run('observations.length'),0);
});
test('failed analysis explains timeout, validation and explicit 429 separately',async()=>{
 const h=harness();
 assert.match(h.run("analysisFailureMessage({failure_stage:'engine_deadline'})"),/制限時間/);
 assert.match(h.run("analysisFailureMessage({failure_stage:'engine_result_validation'})"),/検証/);
 assert.match(h.run("analysisFailureMessage({engine_http_status:429})"),/429/);
 assert.doesNotMatch(h.run("analysisFailureMessage({failure_stage:'engine_model_execution'})"),/429/);
 h.run("agentAuth={token:'test-token',expires:Date.now()+60000};savedGoals=new Set(['学習']);$('agentConsent').checked=true;observe('CLICK','数学');");
 const original=h.context.fetch;
 h.context.fetch=async(url,opts)=>url==='/v1/analyses'?{ok:true,json:async()=>({status:'failed',analysis_id:'a-test',failure_stage:'engine_deadline'})}:original(url,opts);
 await h.nodes.get('runAgent').onclick();assert.match(h.nodes.get('agentStatus').textContent,/制限時間/);
});
