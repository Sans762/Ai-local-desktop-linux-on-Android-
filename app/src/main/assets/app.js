'use strict';
const $=id=>document.getElementById(id);
let csrf='',busy=false;
function show(which){$('login').hidden=which!=='login';$('dashboard').hidden=which!=='dashboard'}
async function request(url,body=null){
  const opts={credentials:'same-origin',cache:'no-store'};
  if(body!==null){opts.method='POST';opts.headers={'Content-Type':'application/json','X-CSRF-Token':csrf};opts.body=JSON.stringify(body)}
  const response=await fetch(url,opts);let data;
  try{data=await response.json()}catch(_){throw new Error('Server returned an invalid response')}
  if(!response.ok||data.ok===false)throw new Error(data.error||'HTTP '+response.status);
  return data;
}
async function refresh(){
  try{
    const s=await request('/api/status');csrf=s.csrf||'';show('dashboard');
    $('aiName').textContent=s.name||'Local AI'; $('model').textContent=s.model||'ยังไม่ได้นำเข้า GGUF';
    $('ram').textContent=(s.ramAvailableMb||0)+' MB';
    $('service').textContent=s.working?'กำลังประมวลผล':'พร้อมใช้งาน';
    $('control').textContent=s.controlAllowed?(s.accessibilityEnabled?'พร้อมใช้งาน':'รออนุญาต Accessibility'):'ปิด';
    $('chatHint').textContent=s.model?'โมเดลในเครื่อง • ใช้งานออฟไลน์':'เปิดแอป Android และนำเข้าไฟล์ .gguf ก่อนส่งข้อความ';
    return s;
  }catch(e){if(/LOGIN_REQUIRED/.test(e.message))show('login');else $('loginError').textContent=e.message;return null}
}
function addMessage(role,text){const list=$('messages');list.querySelector('.intro')?.remove();const b=document.createElement('div');b.className='bubble '+(role==='user'?'user':'ai');b.textContent=text;list.appendChild(b);list.scrollTop=list.scrollHeight}
async function history(){try{const d=await request('/api/history');$('messages').textContent='';for(const m of d.messages)addMessage(m.role,m.content)}catch(_){}}
async function chat(){
  if(busy)return;const text=$('message').value.trim();if(!text)return;
  busy=true;$('send').disabled=true;$('message').value='';addMessage('user',text);$('chatHint').textContent='โมเดลกำลังประมวลผลในมือถือ…';
  try{const d=await request('/api/chat',{message:text});addMessage('assistant',d.reply||'(ไม่มีคำตอบ)')}
  catch(e){addMessage('assistant','ไม่สำเร็จ: '+e.message)}
  finally{busy=false;$('send').disabled=false;$('chatHint').textContent='พร้อมรับข้อความ';refresh()}
}
$('loginForm').addEventListener('submit',async e=>{
  e.preventDefault();$('loginError').textContent='';
  try{await request('/login',{password:$('password').value});$('password').value='';await refresh();await history()}
  catch(e){$('loginError').textContent=e.message}
});
$('send').addEventListener('click',chat);
$('message').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();chat()}});
$('refresh').addEventListener('click',refresh);
$('logout').addEventListener('click',async()=>{try{await request('/logout',{})}finally{csrf='';show('login')}});
for(const t of document.querySelectorAll('[data-tab]'))t.addEventListener('click',()=>{
  for(const b of document.querySelectorAll('[data-tab]'))b.classList.toggle('active',b===t);
  const target=t.dataset.tab; $('chatTab').hidden=target!=='chat';$('deviceTab').hidden=target!=='device';$('aboutTab').hidden=target!=='about';
});
$('observe').addEventListener('click',async()=>{try{const d=await request('/api/observe');$('snapshot').textContent=JSON.stringify(d.screen,null,2)}catch(e){$('snapshot').textContent=e.message}});
for(const button of document.querySelectorAll('[data-action]'))button.addEventListener('click',async()=>{
  const action=button.dataset.action;const args={action};
  if(action==='tap'){args.x=Number($('tapX').value);args.y=Number($('tapY').value)}
  if(action==='type')args.text=$('typeText').value;
  try{const d=await request('/api/action',args);$('snapshot').textContent=JSON.stringify(d,null,2)}
  catch(e){$('snapshot').textContent=e.message}
});
refresh().then(s=>{if(s)history()});setInterval(refresh,10000);