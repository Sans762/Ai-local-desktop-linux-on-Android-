'use strict';
const $ = id => document.getElementById(id);
let csrf = '';
let busy = false;
const intro = $('messages').innerHTML;

/**
 * Android WebView may not shrink 100dvh when the soft keyboard opens.
 * Follow the visible viewport instead so the composer stays on-screen.
 */
function syncAppViewport() {
  const viewport = window.visualViewport;
  const height = Math.floor(
    viewport && viewport.scale === 1 ? viewport.height : window.innerHeight
  );
  if (Number.isFinite(height) && height > 80) {
    document.documentElement.style.setProperty('--app-height', height + 'px');
  }
}
syncAppViewport();
window.addEventListener('resize', syncAppViewport);
window.addEventListener('orientationchange', syncAppViewport);
if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', syncAppViewport);
}


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
/**
 * Commands start from the chat box, not a second embedded Linux control panel.
 * A command proposal is NEVER executed without a per-task approval.
 */
function isLinuxAction(message) {
  const input = message.trim();
  return /^(?:ช่วย(?:ฉัน|ผม)?\s*)?(?:เปิด(?:โปรแกรม|แอป|เว็บ|เบราว์เซอร์|เทอร์มินัล|ไฟล์|โฟลเดอร์|หน้าต่าง)|คลิก|กด(?:ปุ่ม|ที่)|ขยับเมาส์|พิมพ์(?:ข้อความ|ใน|ลง)|สร้าง(?:โฟลเดอร์|ไฟล์)|ติดตั้ง(?:โปรแกรม|แพ็กเกจ)|สลับหน้าต่าง|ปิด(?:หน้าต่าง|โปรแกรม)|สั่ง(?:งาน)?\s*Linux|ควบคุม\s*Linux)/iu.test(input)
    || /^(?:please\s+)?(?:open|launch|click|move(?:\s+the)?\s+mouse|type|install(?:\s+a)?\s+package|create(?:\s+a)?\s+(?:file|folder)|close(?:\s+the)?\s+window)\b/i.test(input);
}
async function planChatLinuxTask(task, indicator) {
  const data = await request('/api/linux/plan', {task});
  indicator.remove();
  const text = [
    data.summary || 'AI เตรียมคำสั่ง Linux แล้ว',
    ...((data.commands || []).map((command, i) => (i + 1) + '. ' + command)),
    'ตรวจคำสั่งก่อนอนุมัติ · ยังไม่มีการรัน'
  ].join('\n');
  const bubble = addMessage('assistant', text);
  const actions = document.createElement('div');
  actions.className = 'approval-actions';
  const approve = document.createElement('button');
  approve.textContent = 'อนุมัติให้ทำ';
  approve.type = 'button';
  const cancel = document.createElement('button');
  cancel.textContent = 'ยกเลิก';
  cancel.type = 'button';
  actions.append(approve, cancel);
  bubble.appendChild(actions);
  cancel.addEventListener('click', () => {
    actions.remove();
    addMessage('assistant', 'ยกเลิกแล้ว ไม่มีการรันคำสั่ง');
  });
  approve.addEventListener('click', async () => {
    approve.disabled = true;
    cancel.disabled = true;
    approve.textContent = 'กำลังทำงาน…';
    try {
      const result = await request('/api/linux/execute', {planId:data.planId});
      actions.remove();
      addMessage('assistant', (result.summary || 'งาน Linux') +
        (result.completed ?
          '\n✓ รันคำสั่งครบแล้ว (ยังไม่ยืนยันผลจากภาพหน้าจอ)' :
          '\n⚠ บางขั้นตอนไม่สำเร็จ') +
        (result.desktopObservation ? '\nหน้าต่างที่ตรวจพบ: ' + result.desktopObservation.slice(0,180) : ''));
    } catch (e) {
      actions.remove();
      addMessage('assistant', 'ยังทำงานไม่ได้: ' + e.message);
    }
  });
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
    if (isLinuxAction(content)) {
      await planChatLinuxTask(content, indicator);
    } else {
      const data = await request('/api/chat', { message: content });
      indicator.remove();
      addMessage('assistant', data.reply || '(ไม่มีคำตอบ)');
    }
  } catch (e) {
    indicator.remove();
    const reason = e.message === 'INSTALL_QWEN_GGUF_FIRST'
      ? 'ยังไม่ได้ติดตั้งโมเดล GGUF · ไปที่ตั้งค่าเพื่อเพิ่มโมเดล'
      : e.message;
    addMessage('assistant', 'ส่งไม่สำเร็จ: ' + reason + ' · ลองอีกครั้ง');
    // Do not lose a failed message; also do not overwrite a new draft.
    if (!textarea.value.trim()) textarea.value = content;
  } finally {
    busy = false;
    $('send').disabled = false;
    await refresh();
    textarea.focus();
  }
}
function changeTab(name) {
  if (document.activeElement && typeof document.activeElement.blur === 'function') {
    document.activeElement.blur();
  }
  syncAppViewport();
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
    if (s) { await history(); }
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
