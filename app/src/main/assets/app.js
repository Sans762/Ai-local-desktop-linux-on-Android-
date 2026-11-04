'use strict';
const $ = id => document.getElementById(id);
let csrf = '';
let busy = false;
const intro = $('messages').innerHTML;

function show(which) {
  $('login').hidden = which !== 'login';
  $('dashboard').hidden = which !== 'dashboard';
}
function setText(id, value) {
  const element = $(id);
  if (element) element.textContent = value;
}
async function request(path, body = null) {
  const opts = { credentials: 'same-origin', cache: 'no-store' };
  if (body !== null) {
    opts.method = 'POST';
    opts.headers = { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf };
    opts.body = JSON.stringify(body);
  }
  const response = await fetch(path, opts);
  let data;
  try { data = await response.json(); }
  catch (_) { throw new Error('เซิร์ฟเวอร์ตอบกลับไม่ถูกต้อง'); }
  if (!response.ok || data.ok === false) throw new Error(data.error || 'HTTP ' + response.status);
  return data;
}
async function refresh() {
  try {
    const s = await request('/api/status');
    csrf = s.csrf || '';
    show('dashboard');
    setText('aiName', s.name || 'Local AI');
    setText('model', s.model || 'ยังไม่ได้เลือกโมเดล GGUF');
    setText('ram', (s.ramAvailableMb || 0).toLocaleString() + ' MB');
    setText('service', s.working ? 'กำลังประมวลผล…' : 'พร้อมใช้งาน');
    setText('onlineLabel', s.working ? 'AI is thinking' : 'Local only');
    const control = s.linuxInstalled ? 'Alpine installed' : 'Not installed';
    setText('control', control);
    setText('linuxReady', s.linuxInstalled ? '● Alpine Linux ติดตั้งแล้ว' : '○ ติดตั้ง Alpine จาก Control Center ก่อน');
    setText('sysModel', s.model || 'ยังไม่มีโมเดล');
    setText('sysRam', (s.ramAvailableMb || 0).toLocaleString() + ' MB');
    setText('sysControl', control);
    if (!busy) setText('chatHint', s.model ? 'โมเดลอยู่ในมือถือ • ใช้งานออฟไลน์' : 'นำเข้าโมเดล .gguf ผ่าน Control Center ก่อนเริ่มแชท');
    return s;
  } catch (e) {
    if (/LOGIN_REQUIRED/.test(e.message)) show('login');
    else setText('loginError', e.message);
    return null;
  }
}
function addMessage(role, content, waiting = false) {
  const list = $('messages');
  list.querySelector('.intro')?.remove();
  const bubble = document.createElement('div');
  bubble.className = 'bubble ' + (role === 'user' ? 'user' : 'ai') + (waiting ? ' waiting' : '');
  if (role !== 'user') {
    const by = document.createElement('span');
    by.className = 'bubble-label';
    by.textContent = '✦ LOCAL AI';
    bubble.appendChild(by);
  }
  const message = document.createElement('span');
  message.textContent = content;
  bubble.appendChild(message);
  list.appendChild(bubble);
  list.scrollTop = list.scrollHeight;
  return bubble;
}
async function history() {
  try {
    const data = await request('/api/history');
    $('messages').textContent = '';
    if (!data.messages || !data.messages.length) {
      $('messages').innerHTML = intro;
      return;
    }
    for (const m of data.messages) addMessage(m.role, m.content || '');
  } catch (e) { setText('chatHint', e.message); }
}
async function chat() {
  if (busy) return;
  const textarea = $('message');
  const content = textarea.value.trim();
  if (!content) return;
  busy = true;
  $('send').disabled = true;
  textarea.value = '';
  addMessage('user', content);
  const indicator = addMessage('assistant', 'กำลังคิดและประมวลผลในเครื่อง…', true);
  setText('chatHint', 'กำลังทำงาน • โมเดลอาจใช้เวลาตามขนาดและ RAM');
  try {
    const data = await request('/api/chat', { message: content });
    indicator.remove();
    addMessage('assistant', data.reply || '(ไม่มีคำตอบ)');
  } catch (e) {
    indicator.remove();
    addMessage('assistant', 'ไม่สำเร็จ: ' + e.message);
  } finally {
    busy = false;
    $('send').disabled = false;
    await refresh();
    textarea.focus();
  }
}
function changeTab(name) {
  for (const button of document.querySelectorAll('[data-tab]')) {
    const selected = button.dataset.tab === name;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-current', selected ? 'page' : 'false');
  }
  $('chatTab').hidden = name !== 'chat';
  $('deviceTab').hidden = name !== 'device';
  $('aboutTab').hidden = name !== 'about';
}
async function logout() {
  try { await request('/logout', {}); }
  catch (_) { /* The next authenticated call still validates the current cookie. */ }
  csrf = '';
  show('login');
  setText('loginError', '');
}

$('loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  setText('loginError', '');
  try {
    await request('/login', { password: $('password').value });
    $('password').value = '';
    const s = await refresh();
    if (s) await history();
  } catch (e) { setText('loginError', e.message); }
});
$('send').addEventListener('click', chat);
$('message').addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); chat(); }
});
for (const trigger of document.querySelectorAll('[data-tab]')) {
  trigger.addEventListener('click', () => changeTab(trigger.dataset.tab));
}
for (const prompt of document.querySelectorAll('[data-prompt]')) {
  prompt.addEventListener('click', () => {
    $('message').value = prompt.dataset.prompt;
    $('message').focus();
  });
}
for (const refreshButton of [ $('refresh'), ...document.querySelectorAll('[data-refresh]') ]) {
  refreshButton.addEventListener('click', refresh);
}
for (const logoutButton of [ $('logout'), ...document.querySelectorAll('[data-logout]') ]) {
  logoutButton.addEventListener('click', logout);
}
let pendingLinuxPlan = null;
function linuxOutput(value) { setText('linuxOutput', value); }
function showLinuxPlan(show) { $('linuxPlanView').hidden = !show; }
$('linuxPlan').addEventListener('click', async () => {
  const task = $('linuxTask').value.trim();
  if (!task) { linuxOutput('กรุณาเขียนงานที่ต้องการให้ AI ทำใน Linux'); return; }
  $('linuxPlan').disabled = true;
  pendingLinuxPlan = null;
  showLinuxPlan(false);
  linuxOutput('โมเดลกำลังสร้างแผนคำสั่ง Linux…');
  try {
    const data = await request('/api/linux/plan', {task});
    pendingLinuxPlan = data.planId;
    setText('linuxSummary', data.summary);
    setText('linuxPlanCommands', data.commands.map((c, i) => (i + 1) + '. $ ' + c).join('\n\n'));
    showLinuxPlan(true);
    linuxOutput('สร้างแผนแล้ว ยังไม่ได้รันคำสั่งใด ๆ');
  } catch (e) { linuxOutput('วางแผนไม่สำเร็จ: ' + e.message); }
  finally { $('linuxPlan').disabled = false; }
});
$('linuxCancel').addEventListener('click', () => {
  pendingLinuxPlan = null;
  showLinuxPlan(false);
  linuxOutput('ยกเลิกแผนแล้ว ไม่มีการรันคำสั่ง');
});
$('linuxExecute').addEventListener('click', async () => {
  if (!pendingLinuxPlan) return;
  if (!window.confirm('คุณตรวจคำสั่ง Linux ทุกบรรทัดแล้ว และยืนยันให้รันจริงใน Alpine Linux ใช่หรือไม่?')) return;
  const planId = pendingLinuxPlan;
  pendingLinuxPlan = null;
  showLinuxPlan(false);
  $('linuxExecute').disabled = true;
  linuxOutput('กำลังดำเนินงานใน Alpine Linux…');
  try {
    const data = await request('/api/linux/execute', {planId});
    linuxOutput(data.results.map(x => '$ ' + x.command + '\n' + x.output).join('\n\n') +
      '\n\n' + (data.completed ? '✓ เสร็จสิ้น' : '⚠ หยุดเมื่อคำสั่งผิดพลาด'));
  } catch (e) { linuxOutput('Linux execution error: ' + e.message); }
  finally { $('linuxExecute').disabled = false; }
});
$('linuxRun').addEventListener('click', async () => {
  const command = $('linuxCommand').value.trim();
  if (!command) return;
  if (!window.confirm('รันคำสั่งนี้ใน Alpine Linux จริงหรือไม่?\n\n' + command)) return;
  $('linuxRun').disabled = true;
  linuxOutput('กำลังรัน Linux command…');
  try {
    const result = await request('/api/linux/command', {command});
    linuxOutput('$ ' + result.command + '\n' + result.output);
  } catch (e) { linuxOutput('Linux error: ' + e.message); }
  finally { $('linuxRun').disabled = false; }
});
$('linuxCheck').addEventListener('click', async () => {
  try {
    const data = await request('/api/linux/status');
    setText('linuxReady', data.ready ? '● Alpine Linux พร้อมใช้งาน' : '○ ยังต้องติดตั้ง Alpine Linux จาก Control Center');
    linuxOutput(JSON.stringify(data, null, 2));
  } catch (e) { linuxOutput('Linux status error: ' + e.message); }
});
refresh().then(s => { if (s) history(); });
setInterval(() => { if (!document.hidden) refresh(); }, 12000);