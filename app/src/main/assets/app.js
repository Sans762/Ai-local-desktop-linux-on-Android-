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
    const control = s.controlAllowed ? (s.accessibilityEnabled ? 'เปิดพร้อมใช้งาน' : 'รอสิทธิ์ Accessibility') : 'ปิดอยู่';
    setText('control', control);
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
$('observe').addEventListener('click', async () => {
  setText('snapshot', 'กำลังอ่านหน้าจอ…');
  try {
    const data = await request('/api/observe');
    setText('snapshot', JSON.stringify(data.screen, null, 2));
  } catch (e) { setText('snapshot', e.message); }
});
for (const button of document.querySelectorAll('[data-action]')) {
  button.addEventListener('click', async () => {
    const action = button.dataset.action;
    const args = { action };
    if (action === 'tap') {
      const x = Number($('tapX').value), y = Number($('tapY').value);
      if (!$('tapX').value || !$('tapY').value || !Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0) {
        setText('snapshot', 'โปรดกรอกพิกัด X และ Y ที่ถูกต้อง'); return;
      }
      args.x = x; args.y = y;
    }
    if (action === 'type') {
      args.text = $('typeText').value;
      if (!args.text) { setText('snapshot', 'โปรดกรอกข้อความที่ต้องการพิมพ์'); return; }
    }
    button.disabled = true;
    setText('snapshot', 'กำลังส่งคำสั่ง…');
    try {
      const result = await request('/api/action', args);
      setText('snapshot', JSON.stringify(result, null, 2));
    } catch (e) { setText('snapshot', e.message); }
    finally { button.disabled = false; }
  });
}
refresh().then(s => { if (s) history(); });
setInterval(() => { if (!document.hidden) refresh(); }, 12000);