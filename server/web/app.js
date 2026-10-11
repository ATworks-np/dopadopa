'use strict';
const $ = id => document.getElementById(id);
const apiKey = 'AIzaSyCfVlJYzZ6fmxoeVUV3TZaN1ydUdChr7c4'; // Public Firebase client key, not a credential.
let auth = null, epoch = 0, busy = false, samples = [], selected = null, scenario = null, analysis = null;
function node(tag, text, parent, className) { const n = document.createElement(tag); if(text !== undefined) n.textContent = text; if(className) n.className = className; parent.append(n); return n; }
function status(text) { $('status').textContent = text; }
function controls() { ['analyze','delete','sessions','history'].forEach(id => $(id).disabled = busy || !auth || ((id === 'analyze' || id === 'history') && (!selected || (id === 'analyze' && !$('consent').checked)))); ['sample','scenario','signup'].forEach(id => $(id).disabled = busy); $('auth').querySelector('button').disabled = busy; }
async function jsonFetch(url, options={}) {
    const controller=new AbortController();
    const timer=setTimeout(()=>controller.abort(),url==='/v1/analyses'?65000:15000);
    try {
        const response=await fetch(url,{cache:'no-store',...options,signal:controller.signal});
        if(!response.ok) {
            const error=new Error('request failed');
            if(url.startsWith('https://identitytoolkit.googleapis.com/')) {
                const data=await response.json();
                const code=data.error?.message?.split(' : ')[0];
                const messages={
                    TOO_MANY_ATTEMPTS_TRY_LATER:'認証の試行が一時的に制限されています。時間を置いて再試行してください。',
                    EMAIL_EXISTS:'登録済みのメールです。ログインしてください。',
                    INVALID_LOGIN_CREDENTIALS:'メールまたはパスワードを確認してください。',
                    INVALID_EMAIL:'メールアドレスの形式を確認してください。',
                    OPERATION_NOT_ALLOWED:'現在このログイン方式は利用できません。'
                };
                error.userMessage=messages[code];
            }
            throw error;
        }
        return await response.json();
    } finally { clearTimeout(timer); }
}
async function token() { if(!auth) throw new Error('login required'); if(Date.now() < auth.expires) return auth.idToken; const prior = auth; const data = await jsonFetch('https://securetoken.googleapis.com/v1/token?key='+apiKey, {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'refresh_token',refresh_token:prior.refreshToken})}); if(auth !== prior) throw new Error('account changed'); auth = {...prior,idToken:data.id_token,refreshToken:data.refresh_token,expires:Date.now()+Number(data.expires_in)*1000-60000}; return auth.idToken; }
async function api(path, method='GET', body) { const revision = epoch; const bearer = await token(); if(revision !== epoch) throw new Error('account changed'); const headers={Authorization:'Bearer '+bearer,'Content-Type':'application/json'}; if(method==='POST' && (path==='/v1/sessions:batch' || path.endsWith('/import'))) { const state=await jsonFetch('/v1/user/generation',{headers}); if(revision!==epoch) throw new Error('account changed'); headers['X-DopaGuard-Generation']=String(state.generation); } const result = await jsonFetch(path, {method,headers,...(body ? {body:JSON.stringify(body)} : {})}); if(revision !== epoch) throw new Error('account changed'); return result; }
async function run(action) { if(busy) return; const revision=epoch; busy=true; controls(); try { await action(revision); } catch (error) { if(revision===epoch) status(error.userMessage || '処理を完了できませんでした。ログイン・同意・通信状態を確認してください。保存に失敗した場合は再送前に履歴を再取得してください。'); } finally { busy=false; controls(); } }
function clearPersonal() { epoch++; analysis=null; $('analysisList').replaceChildren(); selected=null; scenario=null; $('sessionList').replaceChildren(); $('analysis').textContent='分析前です。'; $('feedback').replaceChildren(); $('timeline').replaceChildren(); $('goal').textContent=''; $('origin').textContent='未選択'; }
function showTimeline(value, sampleKey=null) { selected=value; scenario=sampleKey; analysis=null; $('analysisList').replaceChildren(); $('analysis').textContent='分析前です。'; $('feedback').replaceChildren(); $('origin').textContent = value.events[0].data_origin === 'sample' ? 'sample / 架空データ' : 'real / 本人の観測ログ'; $('goal').textContent='本人のゴール: '+value.user_goal; $('timeline').replaceChildren(); value.events.forEach(e => { const row=node('div',undefined,$('timeline'),'event'); row.id='event-'+e.event_id; node('code',e.event_id+' · '+e.timestamp,row); node('div',e.event_type+' · '+(e.text_status==='available' ? e.text : '取得不能 / 非表示'),row); node('div',`text_status: ${e.text_status} · source: ${e.source} · observability: ${e.observability}`,row,'metadata'); }); controls(); }
function evidence(ids,parent) { ids.forEach(id => { const detail=node('details',undefined,parent); node('summary','根拠イベント: '+id,detail); const event=selected.events.find(e=>e.event_id===id); node('p',event ? `${event.timestamp} · ${event.event_type} · ${event.text_status==='available' ? event.text : '取得不能 / 非表示'}` : '根拠イベントは取得できません',detail); if(event) node('p',`text_status: ${event.text_status} · source: ${event.source} · observability: ${event.observability}`,detail,'metadata'); }); }
function showAnalysis(record) { analysis=record; const box=$('analysis'); box.replaceChildren(); node('p','状態: '+record.status,box); if(record.result) { const r=record.result; node('h3','AIの仮説 / 本人の確認が必要',box); node('p','ゴールとの関連: '+r.goal_alignment,box); node('p','不確実性: '+r.uncertainty,box); r.segments.forEach(s=>{const div=node('div',undefined,box,'hypothesis'); node('h3',s.topic,div); node('p','意図の仮説: '+s.intent_hypothesis,div); node('p',s.from_event_id+' → '+s.to_event_id,div); evidence(s.evidence_ids,div);}); r.transitions.forEach(t=>{const div=node('div',undefined,box,'hypothesis'); node('h3','転換点の候補: '+t.at_event_id,div); node('p','変化の仮説: '+t.hypothesis,div); evidence(t.evidence_ids,div);}); } node('h3','Tool実行記録',box); (record.trace||[]).forEach(t=>node('p',`${t.tool} · ${t.count}件 · ${t.elapsed_ms}ms`,box,'metadata')); }
async function showFeedback(aid) { const rows=await api('/v1/analyses/'+aid+'/feedback'); const box=$('feedback'); box.replaceChildren(); node('h3','本人の確認・訂正',box); node('p',rows.length ? '回答履歴（観測イベントとは独立）' : '未回答',box); const labels={correct:'合っている',incorrect:'違う',unknown:'分からない'}; rows.forEach(r=>node('p',r.created_at+' · '+labels[r.verdict]+(r.corrected_intent ? ' · 本人の訂正: '+r.corrected_intent : ''),box)); const form=node('form',undefined,box); const label=node('label','回答',form); const choice=node('select',undefined,label); choice.required=true; node('option','選択してください',choice).value=''; Object.entries(labels).forEach(([v,l])=>node('option',l,choice).value=v); const correctionLabel=node('label','利用意図の訂正（違う場合のみ・任意）',form); const correction=node('textarea',undefined,correctionLabel); correction.maxLength=500; correction.disabled=true; choice.onchange=()=>{correction.disabled=choice.value!=='incorrect';}; node('p','クラウドへ保存します。個人情報や秘密は入力しないでください。',form); const save=node('button','この回答を保存',form); save.disabled=!$('consent').checked; form.onsubmit=e=>{e.preventDefault(); run(async()=>{if(!$('consent').checked || !choice.value) throw new Error('consent required'); await api('/v1/analyses/'+aid+'/feedback','POST',{verdict:choice.value,corrected_intent:choice.value==='incorrect' ? correction.value.trim()||null : null}); await showFeedback(aid); status('本人の回答を保存しました。');});}; }
$('sample').onclick=()=>showTimeline(samples.find(s=>s.scenario===$('scenario').value).session,$('scenario').value);
$('history').onclick=()=>run(async()=>{
    const rows=await api('/v1/sessions/'+selected.session_id+'/analyses');
    $('analysisList').replaceChildren();
    rows.forEach(row=>{
        const button=node('button',(row.created_at || '作成時刻未記録')+' · '+row.status,$('analysisList'));
        button.onclick=()=>run(async()=>{
            const record=await api('/v1/analyses/'+row.analysis_id);
            showAnalysis(record);
            if(['completed','insufficient_data'].includes(record.status)) await showFeedback(record.analysis_id);
            status('保存済みの分析と回答を表示しました。新しい分析は実行していません。');
        });
    });
    status(rows.length ? '保存済みの分析を選択してください。' : '保存済みの分析はありません。');
});
async function login(signup) { const email=$('email').value, password=$('password').value; if(!$('auth').reportValidity()) return; await run(async revision=>{ const value=await jsonFetch('https://identitytoolkit.googleapis.com/v1/accounts:'+(signup?'signUp':'signInWithPassword')+'?key='+apiKey,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,password,returnSecureToken:true})}); if(revision!==epoch) return; clearPersonal(); auth={idToken:value.idToken,refreshToken:value.refreshToken,expires:Date.now()+Number(value.expiresIn)*1000-60000}; $('password').value=''; $('account').textContent=value.email+' / ログイン中'; status('ログインしました。サンプルまたは本人のセッションを選択してください。'); }); }
$('auth').onsubmit=e=>{e.preventDefault();login(false);}; $('signup').onclick=()=>login(true);
$('logout').onclick=()=>{auth=null;clearPersonal();$('password').value='';$('consent').checked=false;$('account').textContent='未ログイン';status('ログアウトしました。');controls();};
$('consent').onchange=()=>{ epoch++; controls(); if(analysis) $('feedback').querySelectorAll('button').forEach(b=>b.disabled=!$('consent').checked); };
$('sessions').onclick=()=>run(async()=>{const rows=await api('/v1/sessions');$('sessionList').replaceChildren();rows.forEach(r=>{const button=node('button',r.session_id+' · '+r.data_origin,$('sessionList'));button.onclick=()=>run(async()=>showTimeline(await api('/v1/sessions/'+r.session_id)));});status(rows.length ? '本人のセッションを選択してください。' : '同期済みセッションはありません。');});
$('analyze').onclick=()=>run(async()=>{if(!$('consent').checked) throw new Error('consent required');status('Geminiが分析中です。'); if(scenario) await api('/v1/demo/sessions/'+scenario+'/import','POST',{}); const record=await api('/v1/analyses','POST',{session_id:selected.session_id}); showAnalysis(record); if(['completed','insufficient_data'].includes(record.status)) await showFeedback(record.analysis_id);status('分析結果は仮説です。根拠と照合してください。');});
$('delete').onclick=()=>{if(confirm('このアカウントの全クラウドセッション・分析・回答を削除しますか？')) run(async()=>{await api('/v1/user/data','DELETE');clearPersonal();$('consent').checked=false;status('本人のクラウドデータを削除しました。');});};
jsonFetch('/v1/demo/sessions').then(rows=>{samples=rows;rows.forEach(s=>node('option',s.label,$('scenario')).value=s.scenario);showTimeline(rows[0].session,rows[0].scenario);status('架空サンプルを表示しています。分析にはログインと同意が必要です。');}).catch(()=>status('サンプルを取得できませんでした。'));controls();
