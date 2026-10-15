'use strict';
const $ = id => document.getElementById(id);
// Analysis can exceed Hosting's 60-second proxy timeout; call Cloud Run directly.
const apiOrigin = ['dopadopa.web.app','dopadopa.firebaseapp.com'].includes(globalThis.location?.hostname)
 ? 'https://dopaguard-api-463106527119.asia-northeast1.run.app' : '';
const purposes = [
 ['📖','学習・スキルアップ','新しい知識やスキルを身につけたい'],
 ['◎','情報収集','気になることを調べたい'],
 ['♫','趣味・娯楽','好きなコンテンツを楽しみたい'],
 ['☕','休憩・気分転換','少しひと息つきたい'],
 ['◷','長時間利用を減らす','目的を決めて、ほどよく楽しみたい']
];
const genres = [
 ['勉強','📐','Study Lab',[
  ['数学の苦手が変わる、やさしい微分入門','MATH\nMADE SIMPLE'],
  ['毎日使える英会話フレーズを練習しよう','HELLO,\nNEW WORDS'],
  ['はじめてのWebサイトを作ってみよう','HELLO\nWORLD.'],
  ['身近な「なぜ？」を科学でひもとく','STAY\nCURIOUS'],
  ['地図でたどる、世界の歴史','A WALK\nTHROUGH TIME']]],
 ['音楽','♫','Slow Notes',[
  ['雨の日に聴きたい、ゆったりピアノ','RAINY DAY\nPIANO'],
  ['勉強のおともに、静かなローファイ','LO-FI\nSTUDY TIME'],
  ['休日の朝に聴くアコースティックギター','SUNDAY\nACOUSTIC'],
  ['夜のカフェで流れるジャズ','LATE NIGHT\nJAZZ'],
  ['気分を上げたい日のポップミュージック','FEEL GOOD\nPLAYLIST']]],
 ['ミーム','🐈','Meme Club',[
  ['なぜか何度も見たくなる猫の一日','JUST\nCAT THINGS'],
  ['月曜日の自分すぎる猫ミーム集','MONDAY\nMOOD'],
  ['絶妙なタイミングで振り向く犬たち','WAIT\nFOR IT'],
  ['予想外の展開に思わず二度見','PLOT\nTWIST'],
  ['宿題が終わらないときの脳内あるある','BRAIN\nON BREAK']]],
 ['ゲーム','🎮','Play Room',[
  ['のんびり楽しむ、ゲームの世界','PRESS\nPLAY'],
  ['初心者から始める、街づくり実況','BUILD\nYOUR TOWN'],
  ['友達と挑戦する協力ゲーム','BETTER\nTOGETHER'],
  ['難問パズルをじっくり解いてみる','ONE MORE\nPUZZLE'],
  ['最後の一秒で逆転した名場面','LAST\nSECOND']]],
 ['暮らし','⌂','Living Well',[
  ['心地よい部屋をつくる、小さな習慣','SLOW\nLIVING'],
  ['フライパンひとつで作る、週末ごはん','WEEKEND\nKITCHEN'],
  ['朝の5分、気持ちよくストレッチ','BREATHE\n& STRETCH'],
  ['散らからない机の片づけ方','CLEAR\nYOUR DESK'],
  ['お気に入りのコーヒーで、ひと休み','COFFEE\nBREAK']]]
];
const clips = ['BigBuckBunny.mp4','ElephantsDream.mp4','ForBiggerFun.mp4','ForBiggerJoyrides.mp4','ForBiggerEscapes.mp4','Sintel.mp4','TearsOfSteel.mp4'];
const videos = genres.flatMap(([genre,icon,channel,contents],genreIndex)=>contents.map(([title,art],contentIndex)=>{
 const id=genreIndex*5+contentIndex;
 return {id,genre,icon,title,art,channel,source:clips[id%clips.length]};
}));
let goals = new Set(), savedGoals = new Set(), filter = 'すべて', selected = null, count = 0, loggedIn = false, loginEpoch = 0;
let agentAuth=null, agentBusy=false, agentEpoch=0, agentController=null, observations=[];
function cancelAgent(message){agentEpoch++;agentController?.abort();$('agentResult').replaceChildren();$('agentStatus').textContent=message;agentControls();}
function agentControls(){
 $('runAgent').disabled=agentBusy||!agentAuth||!$('agentConsent').checked||!savedGoals.size||!observations.length;
 $('eventCount').textContent=`記録中：${observations.length}件（最新100件まで）`;
 $('eventLog').replaceChildren();
 observations.slice(-12).forEach(row=>element('p',`${new Date(row.timestamp).toLocaleTimeString('ja-JP')} · ${row.event_type}${row.text?' · '+row.text:''}`,$('eventLog')));
}
function observe(type,text=null){
 if(!agentAuth||!$('agentConsent').checked||$('watch').hidden)return;
 observations.push({event_type:type,text,timestamp:new Date().toISOString(),text_status:text?'available':'missing',source:text?(type==='CLICK'?'clicked_node':'parent_context'):'none',observability:text?(type==='CLICK'?'direct':'inferred'):'unavailable'});
 observations=observations.slice(-100);agentControls();
}
let userScrollUntil=0,lastScrollAt=0;
function markUserScroll(){userScrollUntil=Date.now()+1500;}
window.addEventListener('wheel',markUserScroll,{passive:true});
window.addEventListener('touchmove',markUserScroll,{passive:true});
window.addEventListener('pointerdown',markUserScroll,{passive:true});
window.addEventListener('keydown',e=>{if(['ArrowDown','ArrowUp','PageDown','PageUp','Home','End',' '].includes(e.key)&&!['INPUT','TEXTAREA','SELECT','BUTTON'].includes(e.target?.tagName))markUserScroll();});
function recordScroll(){const now=Date.now();if(now>userScrollUntil||now-lastScrollAt<500)return;lastScrollAt=now;observe('SCROLL');}
window.addEventListener('scroll',recordScroll,{passive:true});
$('filters').addEventListener('scroll',recordScroll,{passive:true});
async function agentRequest(path,headers,body){
 const response=await fetch(apiOrigin+path,{method:body?'POST':'GET',headers,body:body?JSON.stringify(body):undefined,signal:agentController.signal,cache:'no-store'});
 if(!response.ok){const error=new Error('request');error.status=response.status;throw error;}return response.json();
}
$('agentConsent').onchange=()=>{observations=[];cancelAgent($('agentConsent').checked?'同意しました。ここからの操作を記録します。':'同意を解除しました。送信済みデータは既存の分析画面から削除できます。');if($('agentConsent').checked&&selected!==null)observe('WINDOW_CHANGE',videos[selected].title);};
$('runAgent').onclick=async()=>{
 if($('runAgent').disabled)return;
 if(Date.now()>=agentAuth.expires){agentAuth=null;loggedIn=false;$('loginOpen').textContent='ログイン';cancelAgent('ログインの有効期限が切れました。再ログインしてください。');return;}
 const revision=agentEpoch, sessionId='web-demo-'+crypto.randomUUID(), consentRevision='web-demo-v1';
 const headers={'Authorization':'Bearer '+agentAuth.token,'Content-Type':'application/json'};
 const batch={session_id:sessionId,user_goal:[...savedGoals].join(' / '),cloud_consent:true,consent_revision:consentRevision,events:observations.map((row,i)=>({...row,event_id:'e-'+i,session_id:sessionId,app_id:'dopaguard-web-demo',package_name:'demo.dopaguard.web',data_origin:'sample',consent_revision:consentRevision}))};
 agentBusy=true;agentController=new AbortController();agentControls();$('agentResult').replaceChildren();$('agentStatus').textContent='操作履歴を保存しています…';
 const timer=setTimeout(()=>agentController?.abort(),120000);
 try{
  const generation=await agentRequest('/v1/user/generation',headers);if(revision!==agentEpoch)return;
  await agentRequest('/v1/sessions:batch',{...headers,'X-DopaGuard-Generation':String(generation.generation)},batch);if(revision!==agentEpoch)return;
  $('agentStatus').textContent='エージェントが目的・操作履歴・転換点を調べています…';
  const record=await agentRequest('/v1/analyses',headers,{session_id:sessionId});if(revision!==agentEpoch)return;
  if(record.status==='failed')throw new Error('agent');
  $('agentStatus').textContent=record.status==='insufficient_data'?'分析完了：判断するための情報が不足しています。':'分析完了（デモデータに対する仮説）';
  const result=record.result;if(!result)return;
  element('h3',({aligned:'目的に沿っている可能性',possibly_diverged:'目的から離れた可能性',unknown:'関連は不明'})[result.goal_alignment]||'分析結果',$('agentResult'));
  if(result.suggestion_comment)element('p',result.suggestion_comment,$('agentResult'));
  element('p',result.uncertainty,$('agentResult'));
  (result.segments||[]).forEach(s=>{element('strong',s.topic,$('agentResult'));element('p',s.intent_hypothesis,$('agentResult'));});
  const details=element('details',null,$('agentResult'));element('summary','根拠とTool実行記録',details);
  batch.events.forEach(e=>element('p',`${e.event_id} · ${e.event_type} · ${e.text}`,details));
  (record.trace||[]).forEach(t=>element('p',`${t.tool} · ${t.count}件`,details));
  element('p','分析対象：'+batch.user_goal,$('agentResult'),'fine');
 }catch(error){if(revision===agentEpoch)$('agentStatus').textContent=error.status===401?'ログインの有効性を確認できませんでした。再ログインしてください。':error.status===503?'サーバーの認証・接続を利用できません。管理側のGoogle Cloud認証と設定を確認してください。':error.status===409?'クラウドデータが変更されました。同意し直して再実行してください。':error.status===429?'実行回数の上限です。時間を置いて試してください。':'分析を完了できませんでした。通信とサーバーのエージェント設定を確認してください。送信済み履歴は既存の分析画面で確認できます。';}
 finally{clearTimeout(timer);agentController=null;agentBusy=false;agentControls();}
};
function element(tag, text, parent, className) { const n=document.createElement(tag); if(text!==null)n.textContent=text; if(className)n.className=className; if(parent)parent.append(n); return n; }
purposes.forEach(([,title])=>{
 const row=element('label',null,$('goals'),'goal');
 const checkbox=element('input',null,row); checkbox.type='checkbox'; checkbox.value=title;
 element('span',title,row);
 checkbox.onchange=()=>{if(checkbox.checked)goals.add(title);else goals.delete(title);$('start').disabled=goals.size===0;};
});
['すべて',...genres.map(g=>g[0])].forEach(name=>{const b=element('button',name,$('filters')); b.type='button'; b.onclick=()=>{filter=name;render();};});
function render(){
 [...$('filters').children].forEach(b=>b.setAttribute('aria-pressed',String(b.textContent===filter)));
 const rows=videos.filter(v=>filter==='すべて'||v.genre===filter);
 $('videos').replaceChildren();
 rows.forEach(v=>{
  const b=element('button',null,$('videos'),'video-card'); b.type='button'; b.setAttribute('aria-current',String(v.id===selected)); b.setAttribute('aria-label',`${v.title}を再生`);
  const art=element('div',null,b,`thumbnail tone-${v.id%5}`); element('span',v.genre+' / DEMO',art,'thumb-label'); element('b',v.art,art); element('span',v.icon,art,'art-icon'); element('span','サンプル映像',art,'duration');
  element('h3',v.title,b); element('p',`${v.channel} · ${v.genre}`,b); b.onclick=()=>play(v.id,true);
 });
}
function play(id,autoplay){
 const v=videos.find(v=>v.id===id); if(!v)return;
 selected=id; count++; $('count').replaceChildren(document.createTextNode(String(count)+' ')); element('small','本',$('count'));
 if(autoplay)observe('CLICK',v.title);
 $('genre').textContent=v.genre+' / SAMPLE VIDEO'; $('videoTitle').textContent=v.title; $('channel').textContent=v.channel+' · DopaGuard demo'; $('playStatus').textContent='';
 const player=$('player'); player.pause(); player.src='https://storage.googleapis.com/gtv-videos-bucket/sample/'+v.source; player.load();
 observe('WINDOW_CHANGE',v.title);
 userScrollUntil=0;
 if(autoplay)player.play().catch(()=>{if(selected===id)$('playStatus').textContent='再生ボタンを押すと動画が始まります。';});
 render(); if(autoplay){$('videoTitle').focus({preventScroll:true}); window.scrollTo({top:0,behavior:'smooth'});}
}
$('player').onerror=()=>{$('playStatus').textContent='サンプル動画を読み込めませんでした。通信状態を確認するか、別の動画を選んでください。';};
$('start').onclick=()=>{if(!goals.size)return; if(!agentAuth){$('loginDialog').showModal();return;} cancelAgent('目的を保存しました。動画を選んで分析できます。');observations=[];savedGoals=new Set(purposes.map(p=>p[1]).filter(title=>goals.has(title))); $('currentGoal').textContent=[...savedGoals].join(' / '); document.body.classList.remove('goal-screen'); $('onboarding').hidden=true; $('watch').hidden=false; if(selected===null)play(0,false);else observe('WINDOW_CHANGE',videos[selected].title); $('videoTitle').focus({preventScroll:true}); window.scrollTo(0,0);};
$('changeGoal').onclick=()=>{$('player').pause(); goals=new Set(savedGoals); $('goals').querySelectorAll('input').forEach(n=>n.checked=goals.has(n.value)); $('start').disabled=!goals.size; document.body.classList.add('goal-screen'); $('watch').hidden=true; $('onboarding').hidden=false; $('onboarding').querySelector('input:checked')?.focus(); window.scrollTo(0,0);};
$('loginOpen').onclick=()=>{
 if(loggedIn){loginEpoch++;loggedIn=false;agentAuth=null;observations=[];$('agentConsent').checked=false;cancelAgent('ログアウトしました。');$('loginOpen').textContent='ログイン';$('authStatus').textContent='';$('player').pause();$('watch').hidden=true;$('onboarding').hidden=false;document.body.classList.add('goal-screen');$('loginDialog').showModal();return;}
 $('loginDialog').showModal();
};
function closeLogin(){loginEpoch++;$('password').value='';$('loginDialog').close();}
$('loginClose').onclick=closeLogin;
$('loginDialog').addEventListener('cancel',()=>{loginEpoch++;$('password').value='';});
$('loginForm').onsubmit=async e=>{
 e.preventDefault(); const revision=++loginEpoch; const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),15000);
 $('loginSubmit').disabled=true; $('authStatus').textContent='ログインしています…';
 try{
  const response=await fetch('https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=AIzaSyCfVlJYzZ6fmxoeVUV3TZaN1ydUdChr7c4',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:$('email').value.trim(),password:$('password').value,returnSecureToken:true}),signal:controller.signal});
  if(revision!==loginEpoch)return;
  if(!response.ok)throw new Error('login');
  const data=await response.json();if(revision!==loginEpoch)return;
  agentAuth={token:data.idToken,expires:Date.now()+Number(data.expiresIn)*1000-60000}; observations=[];cancelAgent('ログインしました。分析への同意を選択してください。');
  loggedIn=true; $('loginOpen').textContent='ログアウト'; $('authStatus').textContent='';$('loginDialog').close();$('onboarding').querySelector('input')?.focus();
 }catch{if(revision===loginEpoch)$('authStatus').textContent='ログインできませんでした。メール・パスワード・通信状態を確認してください。';}
 finally{clearTimeout(timer);$('password').value='';$('loginSubmit').disabled=false;}
};
render();
$('loginDialog').showModal();
