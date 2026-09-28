const state = {
  conversations: [],
  activeConversationId: null,
  activeStream: null,
  lastDir: ''
};
const $ = selector => document.querySelector(selector);

function createId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'id-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

function toast(message, error = false) {
  const item = document.createElement('div');
  item.textContent = message;
  if (error) item.classList.add('error');
  $('#toast').appendChild(item);
  setTimeout(() => item.remove(), 4000);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[char]));
}

function renderMarkdown(text) {
  if (window.marked && window.DOMPurify) {
    return DOMPurify.sanitize(marked.parse(text));
  }
  return escapeHtml(text);
}

function fmtSize(bytes) {
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + ' GB';
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return bytes + ' B';
}

function setStreamStatus(text, visible = true) {
  $('#streamStatus').classList.toggle('hidden', !visible);
  $('#streamStatusText').innerHTML = `<span class="status-spinner"></span>${escapeHtml(text)}`;
}

function setBusy(busy) {
  $('#sendMessage').disabled = busy;
  $('#chatInput').disabled = busy;
  if (!busy) setStreamStatus('', false);
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  const body = response.headers.get('content-type')?.includes('json') ? await response.json() : null;
  if (!response.ok) throw new Error(body?.message || `请求失败 (${response.status})`);
  return body;
}

// ---------- conversations ----------

function newConversation() {
  const conversation = { id: createId(), title: '新对话', messages: [], updatedAt: new Date().toISOString() };
  state.conversations.unshift(conversation);
  state.activeConversationId = conversation.id;
  saveConversations();
  renderConversations();
  renderChatMessages();
}

function saveConversations() {
  localStorage.setItem('dsh.backupaudit.conversations', JSON.stringify(state.conversations.slice(0, 50)));
}

function loadConversations() {
  try { state.conversations = JSON.parse(localStorage.getItem('dsh.backupaudit.conversations') || '[]'); }
  catch { state.conversations = []; }
}

function renderConversations() {
  const list = $('#conversationList');
  list.innerHTML = '';
  state.conversations.forEach(conversation => {
    const button = document.createElement('button');
    button.className = 'conv-item' + (conversation.id === state.activeConversationId ? ' active' : '');
    button.textContent = conversation.title;
    button.onclick = () => {
      state.activeConversationId = conversation.id;
      renderConversations();
      renderChatMessages();
    };
    list.appendChild(button);
  });
}

function renderChatMessages() {
  const container = $('#chatMessages');
  container.innerHTML = '';
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (!conversation) return;
  conversation.messages.forEach(message => {
    const div = document.createElement('div');
    if (message.role === 'user') {
      div.className = 'msg user';
      div.textContent = message.content;
    } else {
      div.className = 'msg assistant';
      div.innerHTML = assistantHtml(message);
    }
    container.appendChild(div);
  });
  container.scrollTop = container.scrollHeight;
}

function assistantHtml(message) {
  let html = '';
  if (message.reasoning) html += `<div class="reasoning">${escapeHtml(message.reasoning.slice(-600))}</div>`;
  if (message.steps?.length) {
    html += '<div>' + message.steps.map((step, index) => `
      <details class="step-item">
        <summary>
          <span class="step-name">${escapeHtml(step.toolName || '工具 ' + (index + 1))}</span>
          <span class="step-state ${step.status === 'result' ? 'ok' : 'run'}">${step.status === 'result' ? '✓ 完成' : '… 执行中'}</span>
        </summary>
        <div class="step-detail">
          ${step.argsJson ? `<pre>${escapeHtml(step.argsJson)}</pre>` : ''}
          ${step.resultJson ? `<pre>${escapeHtml(String(step.resultJson).slice(0, 1200))}</pre>` : ''}
        </div>
      </details>`).join('') + '</div>';
  }
  html += renderMarkdown(message.content || (message.streaming ? '…' : '（本轮无内容返回）'));
  return html;
}

// ---------- audit panels ----------

function readDir() {
  const dir = $('#dirInput').value.trim();
  if (!dir) {
    toast('先输入备份目录路径', true);
    return null;
  }
  state.lastDir = dir;
  return dir;
}

async function runScan() {
  const dirPath = readDir();
  if (!dirPath) return;
  const data = await request('/api/backup/scan', { method: 'POST', body: JSON.stringify({ dirPath }) });
  $('#scanResult').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.fileCount}</b><span>文件</span></div>
      <div class="metric"><b>${fmtSize(data.totalSizeBytes)}</b><span>总量</span></div>
      <div class="metric"><b>${data.freshCount}</b><span>新鲜</span></div>
    </div>` +
    (data.files || []).slice(0, 20).map(f => `
      <div class="branch-row">
        <b>${escapeHtml(f.name)}</b>
        <span class="badge ${f.fresh ? 'ok' : 'warn'}">${f.ageHours}h</span>
        <div class="small muted">${fmtSize(f.sizeBytes)} · ${escapeHtml(String(f.modified).slice(0, 19).replace('T', ' '))}</div>
      </div>`).join('') || '<div class="muted small">目录为空——先确认备份任务是否在跑</div>';
}

async function runFresh() {
  const dirPath = readDir();
  if (!dirPath) return;
  const data = await request('/api/backup/freshness', { method: 'POST', body: JSON.stringify({ dirPath }) });
  $('#freshResult').innerHTML =
    `<div class="branch-row">
      <span class="badge ${data.verdict === 'FRESH' ? 'ok' : 'error'}">${escapeHtml(data.verdict)}</span>
      ${data.newestAgeHours != null ? `<span class="small muted">最新 ${data.newestAgeHours}h 前 · 最老 ${Math.round(data.oldestAgeHours / 24)} 天前</span>` : ''}
      <div class="small muted">${escapeHtml(data.advice)}</div>
    </div>`;
}

async function runTrend() {
  const dirPath = readDir();
  if (!dirPath) return;
  const data = await request('/api/backup/size-trend', { method: 'POST', body: JSON.stringify({ dirPath }) });
  const maxSize = Math.max(1, ...(data.trend || []).map(t => t.sizeBytes));
  $('#trendResult').innerHTML =
    `<div class="small muted">共 ${data.dayCount} 天 · 总量 ${fmtSize(data.totalSizeBytes)}</div>` +
    (data.trend || []).map(t => `
      <div class="bar-row"><span class="label">${escapeHtml(t.day.slice(5))}</span>
        <span class="bar" style="width:${Math.max(4, t.sizeBytes / maxSize * 90)}px"></span>
        <span class="num">${fmtSize(t.sizeBytes)}</span></div>`).join('') +
    (data.anomalies || []).map(a => `
      <div class="branch-row"><span class="badge error">骤降</span>
        <b>${escapeHtml(a.day)}</b>
        <div class="small muted">${fmtSize(a.from)} → ${fmtSize(a.to)}：${escapeHtml(a.advice)}</div>
      </div>`).join('');
}

async function runGaps() {
  const dirPath = readDir();
  if (!dirPath) return;
  const interval = parseInt($('#intervalInput').value, 10) || 24;
  const data = await request('/api/backup/gaps', { method: 'POST', body: JSON.stringify({ dirPath, expectedIntervalHours: interval }) });
  $('#gapResult').innerHTML =
    `<div class="metric-grid">
      <div class="metric"><b>${data.gapCount}</b><span>缺口</span></div>
      <div class="metric"><b>${data.zeroByteCount}</b><span>0字节坏文件</span></div>
    </div>` +
    (data.gaps || []).map(g => `
      <div class="branch-row"><span class="badge warn">缺口 ${Math.round(g.holeHours / 24)} 天</span>
        <div class="small muted">${escapeHtml(g.advice)}</div>
      </div>`).join('') +
    (data.zeroByteFiles || []).map(z => `
      <div class="branch-row"><span class="badge error">0 字节</span>
        <b>${escapeHtml(z.name)}</b>
        <div class="small muted">${escapeHtml(z.advice)}</div>
      </div>`).join('') ||
    '<div class="muted small" style="margin-top:8px">✓ 无缺口、无坏文件</div>';
}

async function refreshAll() {
  if ($('#dirInput').value.trim()) {
    await runScan().catch(e => toast(e.message, true));
  }
}

// ---------- chat ----------

async function sendMessage() {
  const input = $('#chatInput');
  const text = input.value.trim();
  if (!text) return;
  if (!state.activeConversationId) newConversation();
  const conversation = state.conversations.find(item => item.id === state.activeConversationId);
  if (conversation.title === '新对话') conversation.title = text.slice(0, 30);
  conversation.messages.push({ role: 'user', content: text });
  const pending = { id: createId(), role: 'assistant', content: '', reasoning: '', steps: [], streaming: true };
  conversation.messages.push(pending);
  input.value = '';
  saveConversations();
  renderConversations();
  renderChatMessages();
  setBusy(true);
  setStreamStatus('AI 正在思考');
  try {
    await streamAssistant(text, pending);
    setStreamStatus('已完成');
    setTimeout(() => setBusy(false), 450);
  } catch (error) {
    pending.content = error.name === 'AbortError' ? '**已停止生成。**' : error.message;
    setStreamStatus('执行失败');
    setTimeout(() => setBusy(false), 900);
  }
  saveConversations();
  renderChatMessages();
}

async function streamAssistant(message, pending) {
  const controller = new AbortController();
  state.activeStream = controller;
  const response = await fetch('/api/assistant/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agentId: state.activeConversationId, message }),
    signal: controller.signal
  });
  if (!response.ok || !response.body) throw new Error(`请求失败 (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = 'message';
  let data = '';
  let finished = false;
  let renderQueued = false;
  const render = () => {
    if (renderQueued) return;
    renderQueued = true;
    setTimeout(() => { renderQueued = false; renderChatMessages(); }, 60);
  };
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(line => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += `${line.slice(5).trim()}\n`;
      else if (!line && data) {
        try {
          const payload = JSON.parse(data);
          if (event === 'chunk') pending.content += payload.content || '';
          if (event === 'chunk' && pending.content) setStreamStatus('正在回复');
          if (event === 'reasoning') pending.reasoning += payload.content || '';
          if (event === 'step_break') {
            const exists = payload.callId && pending.steps.some(item => item.callId === payload.callId);
            if (!exists) pending.steps.push({ ...payload, status: 'running' });
            setStreamStatus(`正在执行 ${payload.toolName || '工具'}`);
          }
          if (event === 'tool_result') {
            const step = [...pending.steps].reverse().find(item => item.callId === payload.callId)
              || [...pending.steps].reverse().find(item => item.status === 'running');
            if (step) Object.assign(step, payload, { status: 'result' });
            else pending.steps.push({ ...payload, status: 'result' });
            setStreamStatus('正在整理结果');
          }
          if (event === 'finish' || event === 'done') {
            pending.steps.forEach(item => { if (item.status === 'running') item.status = 'result'; });
            pending.streaming = false;
            finished = true;
          }
          if (event === 'error') pending.content += `\n\n**错误：** ${payload.message || '未知错误'}`;
          render();
        } catch { }
        event = 'message';
        data = '';
      }
    });
    if (finished) break;
  }
  if (!finished) pending.streaming = false;
  state.activeStream = null;
  renderChatMessages();
}

// ---------- events ----------

$('#sendMessage').onclick = sendMessage;
$('#chatInput').addEventListener('keydown', event => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    sendMessage();
  }
});
$('#stopStream').onclick = () => state.activeStream?.abort();
$('#newConversation').onclick = () => { newConversation(); };

$('#scanBtn').onclick = () => runScan().catch(e => toast(e.message, true));
$('#freshBtn').onclick = () => runFresh().catch(e => toast(e.message, true));
$('#trendBtn').onclick = () => runTrend().catch(e => toast(e.message, true));
$('#gapBtn').onclick = () => runGaps().catch(e => toast(e.message, true));

$('#openStrategy').onclick = () => $('#strategyModal').classList.remove('hidden');
$('#cancelStrategyModal').onclick = () => $('#strategyModal').classList.add('hidden');
$('#strategyForm').onsubmit = async event => {
  event.preventDefault();
  const form = new FormData(event.target);
  try {
    const data = await request('/api/backup/strategy', {
      method: 'POST',
      body: JSON.stringify({ copiesInfo: `copies=${form.get('copies')} media=${form.get('media') || 'unknown'} offsite=${form.get('offsite')}` })
    });
    $('#strategyModal').classList.add('hidden');
    $('#freshResult').innerHTML =
      `<div class="small muted"><b>${escapeHtml(data.verdict)}</b>（${data.passCount}/${data.totalChecks} 达标）</div>` +
      (data.checks || []).map(c => `
        <div class="branch-row">
          <span class="badge ${c.pass ? 'ok' : 'error'}">${c.pass ? '✓' : '✗'}</span>
          <b>${escapeHtml(c.item)}</b>
          <div class="small muted">${escapeHtml(c.advice)}</div>
        </div>`).join('');
    document.querySelector('[data-resource-tab="fresh"]').click();
    toast(`评估完成：${data.passCount}/${data.totalChecks} 达标`);
  } catch (error) {
    toast(error.message, true);
  }
};

$('#refreshResource').onclick = async () => {
  await refreshAll();
  toast('已刷新');
};
document.querySelectorAll('[data-resource-tab]').forEach(button => {
  button.onclick = () => {
    document.querySelectorAll('[data-resource-tab]').forEach(item => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-tab]').forEach(panel => panel.classList.toggle('hidden', panel.dataset.tab !== button.dataset.resourceTab));
  };
});
$('#toggleTheme').onclick = () => {
  const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('dsh.backupaudit.theme', next);
  $('#toggleTheme').textContent = next === 'dark' ? '☀️' : '🌙';
};
document.documentElement.dataset.theme = localStorage.getItem('dsh.backupaudit.theme') || 'light';
$('#toggleTheme').textContent = document.documentElement.dataset.theme === 'dark' ? '☀️' : '🌙';

// ---------- init ----------

(async function init() {
  loadConversations();
  if (!state.conversations.length) newConversation();
  else { state.activeConversationId = state.conversations[0].id; }
  renderConversations();
  renderChatMessages();
  try {
    await refreshAll();
  } catch (error) {
    toast(error.message, true);
  }
})();
