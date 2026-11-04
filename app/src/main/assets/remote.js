'use strict';
const el = id => document.getElementById(id);
let csrf = '', paired = false, active = false, inFlight = false, frameTimer = null, refreshTimer = null;
function message(text) { el('actionInfo').textContent = text; }
async function api(path, body) {
  const opts = {credentials:'same-origin', cache:'no-store'};
  if (body !== undefined) { opts.method='POST'; opts.headers={'Content-Type':'application/json','X-CSRF-Token':csrf}; opts.body=JSON.stringify(body); }
  const response = await fetch(path, opts);
  let data; try { data = await response.json(); } catch { throw new Error('Unexpected server response'); }
  if (!response.ok || !data.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
async function sync() {
  try {
    const s = await api('/status');
    csrf = s.csrf; paired = true; active = !!s.running;
    el('pairPanel').hidden = true; el('viewerPanel').hidden = false;
    el('indicator').textContent = active ? '● LIVE' : '● OFFLINE'; el('indicator').classList.toggle('live',active);
    el('controlBadge').textContent = s.control ? 'INPUT ENABLED' : 'VIEW ONLY';
    el('controlBadge').classList.toggle('live',s.control);
    el('remoteInfo').textContent = `${s.width} × ${s.height} · ${s.control ? 'Touch controls available' : 'View-only mode'}`;
    for(const button of document.querySelectorAll('[data-action],#typing button')) button.disabled = !s.control;
    if(!s.control) message('Enable remote input and Accessibility on the Android phone to control the screen.');
    if(active && !frameTimer) frames();
  } catch(err) { paired=false; active=false; el('pairPanel').hidden=false; el('viewerPanel').hidden=true; el('indicator').textContent='● PAIRING REQUIRED'; if(frameTimer)clearTimeout(frameTimer);frameTimer=null; }
}
function frames() {
  if(!paired || !active) { frameTimer=null; return; }
  if (inFlight) { frameTimer=setTimeout(frames,330);return; }
  inFlight=true;
  const temp = new Image();
  temp.onload=()=>{ el('live').src=temp.src; el('empty').hidden=true; inFlight=false;frameTimer=setTimeout(frames,280); };
  temp.onerror=()=>{ inFlight=false;frameTimer=setTimeout(frames,800); };
  temp.src='/frame.jpg?t='+Date.now();
}
el('pairForm').addEventListener('submit',async e=>{
  e.preventDefault();el('pairError').textContent='';
  try{await api('/pair',{pin:el('pin').value.trim()});el('pin').value='';await sync();}
  catch(err){el('pairError').textContent=err.message;}
});
async function action(payload) { try{const data=await api('/action',payload);message(`${data.action}: sent successfully`);}catch(err){message('Remote action rejected: '+err.message);} }
for(const b of document.querySelectorAll('[data-action]')) b.addEventListener('click',()=>action({action:b.dataset.action}));
el('typing').addEventListener('submit',e=>{e.preventDefault();const t=el('typeInput').value;if(t){action({action:'type',text:t});el('typeInput').value='';}});
el('live').addEventListener('click',event=>{
  if(el('controlBadge').textContent !== 'INPUT ENABLED')return;
  const r=el('live').getBoundingClientRect();
  // CSS max-width/max-height with intrinsic image dimensions has no letterboxing.
  const x=(event.clientX-r.left)/r.width, y=(event.clientY-r.top)/r.height;
  if(x>=0&&x<=1&&y>=0&&y<=1)action({action:'tap',x,y});
});
el('refresh').addEventListener('click',sync);
refreshTimer=setInterval(()=>{if(!document.hidden)sync();},4000);
sync();