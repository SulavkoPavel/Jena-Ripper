import { api } from './api.js';
import { userDataRepository } from './user-data.js';

const REDIS_TEMPLATE_LIMIT = 50;
const REDIS_HISTORY_LIMIT = 20;
const OPERATION_STATUS_INTERVAL_MS = 100;
const SLOW_OPERATION_SECONDS = 5;
const VERY_SLOW_OPERATION_SECONDS = 15;

export function initRedisConsole() {
  const editor = document.querySelector('#redis-editor');
  const runButton = document.querySelector('#redis-run');
  const help = document.querySelector('#redis-command-help');
  const errorBox = document.querySelector('#redis-error');
  const result = document.querySelector('#redis-result');
  const rawResult = document.querySelector('#redis-raw-result');
  const resultMeta = document.querySelector('#redis-result-meta');
  const hint = document.querySelector('#redis-running-hint');
  const unavailable = document.querySelector('#redis-unavailable');
  const helperDataset = document.querySelector('#redis-helper-dataset');
  const helperResource = document.querySelector('#redis-helper-resource');
  const helperRule = document.querySelector('#redis-helper-rule');
  const templateDialog = document.querySelector('#redis-template-dialog');
  let metadata = { available: false, datasetId: 0, commands: [], templates: [] };
  let lastResponse = null;
  let running = false;

  runButton.addEventListener('click', execute);
  editor.addEventListener('input', updateHelp);
  editor.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); execute(); }
  });
  document.querySelector('#redis-helper-build').addEventListener('click', buildPermissionCommand);
  document.querySelector('#redis-save-template').addEventListener('click', openTemplateDialog);
  templateDialog.addEventListener('submit', saveTemplate);
  document.querySelector('#redis-history-clear').addEventListener('click', async () => {
    try { await userDataRepository.replace('redisHistory', []); renderHistory(); }
    catch (error) { showError('USER_DATA_SAVE_FAILED', error.message); }
  });
  document.querySelectorAll('[data-redis-side]').forEach(button => button.addEventListener('click', () => switchSide(button.dataset.redisSide)));
  document.querySelectorAll('[data-redis-result-tab]').forEach(button => button.addEventListener('click', () => switchResult(button.dataset.redisResultTab)));

  loadMetadata();
  renderUserTemplates();
  renderHistory();

  async function loadMetadata() {
    try {
      metadata = await api.redisMetadata();
      helperDataset.value = metadata.datasetId || 0;
      document.querySelector('#redis-command-list').replaceChildren(...metadata.commands.map(item => {
        const option = document.createElement('option'); option.value = item.name; option.label = item.syntax; return option;
      }));
      renderSystemTemplates();
      setAvailable(metadata.available);
      updateHelp();
    } catch (error) {
      setAvailable(false);
      unavailable.querySelector('p').textContent = error.message;
    }
  }

  async function execute() {
    if (running) return;
    errorBox.hidden = true;
    let parsed;
    try { parsed = parseCommand(editor.value); }
    catch (error) { showError('REDIS_COMMAND_INVALID', error.message); return; }
    if (!parsed.command) { showError('REDIS_COMMAND_INVALID', 'Введите Redis-команду.'); return; }
    running = true;
    runButton.disabled = true;
    const started = performance.now();
    const timer = window.setInterval(() => {
      const seconds = (performance.now() - started) / 1000;
      runButton.innerHTML = `<span class="button-spinner"></span> Выполняется… ${seconds.toFixed(1)} с`;
      hint.textContent = seconds > VERY_SLOW_OPERATION_SECONDS
        ? 'Команда выполняется дольше обычного.'
        : seconds > SLOW_OPERATION_SECONDS ? 'Команда всё ещё выполняется…' : '';
    }, OPERATION_STATUS_INTERVAL_MS);
    try {
      lastResponse = await api.redisCommand(parsed.command, parsed.args);
      renderResponse(lastResponse);
      try { await addHistory(editor.value.trim(), lastResponse); }
      catch (error) { console.warn('Не удалось сохранить Redis history:', error.message); }
    } catch (error) {
      showError(error.details?.type || error.code || 'REDIS_UNAVAILABLE', error.message);
    } finally {
      window.clearInterval(timer);
      running = false;
      runButton.disabled = false;
      runButton.innerHTML = '<span aria-hidden="true">▶</span> Выполнить';
      hint.textContent = '';
    }
  }

  function renderResponse(response) {
    errorBox.hidden = true;
    rawResult.innerHTML = `<pre class="redis-raw">${escapeHtml(JSON.stringify(response, null, 2))}</pre>`;
    const metric = response.resultType === 'SCAN' ? `${response.count} keys • ${response.executionTimeMs} мс`
      : ['COLLECTION', 'MAP'].includes(response.resultType) ? `${response.count} элементов • ${response.executionTimeMs} мс`
        : `${response.executionTimeMs} мс`;
    resultMeta.textContent = `✓ Выполнено: ${response.command} • ${metric}`;
    if (response.resultType === 'SCAN') {
      result.innerHTML = `<div class="redis-result-scroll"><div class="redis-summary"><strong>Cursor: ${escapeHtml(response.cursor)}</strong><span>Найдено keys: ${response.count}</span></div>${collection(response.value)}${response.cursor !== '0' ? '<button id="redis-scan-next" class="redis-next" type="button">Продолжить SCAN</button>' : ''}</div>`;
      document.querySelector('#redis-scan-next')?.addEventListener('click', continueScan);
    } else if (response.resultType === 'COLLECTION') {
      result.innerHTML = `<div class="redis-result-scroll"><div class="redis-summary"><strong>${response.count} элементов</strong><span>Тип: ${escapeHtml(response.category)}</span></div>${collection(response.value)}</div>`;
    } else if (response.resultType === 'MAP') {
      const rows = Object.entries(response.value || {}).map(([key, value]) => `<tr><th>${escapeHtml(key)}</th><td>${displayValue(value)}</td></tr>`).join('');
      result.innerHTML = `<div class="redis-result-scroll"><table class="redis-map"><tbody>${rows || '<tr><td>(empty)</td></tr>'}</tbody></table></div>`;
    } else if (response.resultType === 'BOOLEAN') {
      result.innerHTML = `<div class="ask-result ${response.value ? 'true' : 'false'}"><span>${response.value ? '✓' : '×'}</span><strong>${response.value ? 'TRUE' : 'FALSE'}</strong></div>`;
    } else if (response.resultType === 'INTEGER') {
      result.innerHTML = `<div class="redis-scalar"><strong>${escapeHtml(String(response.value))}</strong></div>`;
    } else {
      result.innerHTML = `<div class="redis-result-scroll"><div class="redis-summary"><strong>VALUE</strong></div><pre class="redis-value">${escapeHtml(response.value == null ? '(nil)' : String(response.value))}</pre></div>`;
    }
    switchResult('view');
  }

  function collection(values) {
    const items = (values || []).map((value, index) => `<li><span>${index + 1}</span>${displayValue(value)}</li>`).join('');
    return `<ol class="redis-values">${items || '<li class="redis-empty-value">(empty)</li>'}</ol>`;
  }

  function displayValue(value) {
    const text = value == null ? '(nil)' : String(value);
    const rdf = /^(?:https?:\/\/\S+|[A-Za-z][\w-]*:_\S+)$/.test(text);
    return `<code class="${rdf ? 'redis-rdf-value' : ''}" title="${escapeHtml(text)}">${escapeHtml(text)}</code>`;
  }

  function continueScan() {
    const parsed = parseCommand(editor.value);
    if (parsed.command !== 'SCAN' || !lastResponse?.cursor) return;
    parsed.args[0] = lastResponse.cursor;
    editor.value = formatCommand(parsed.command, parsed.args);
    execute();
  }

  async function buildPermissionCommand() {
    errorBox.hidden = true;
    try {
      const response = await api.redisKey({
        datasetId: Number(helperDataset.value), resource: helperResource.value.trim(), rule: helperRule.value
      });
      editor.value = response.command;
      updateHelp(); editor.focus();
    } catch (error) { showError(error.details?.type || 'REDIS_COMMAND_INVALID', error.message); }
  }

  function renderSystemTemplates() {
    const target = document.querySelector('#redis-system-templates');
    target.replaceChildren(...metadata.templates.map(item => templateCard(item, () => loadSystemTemplate(item))));
  }

  async function loadSystemTemplate(template) {
    let command = template.command.replaceAll('{{datasetId}}', String(helperDataset.value || metadata.datasetId || 0));
    const replacements = [['{{readKey}}', 'READ'], ['{{readTopKey}}', 'READ_TOP'], ['{{writeKey}}', 'WRITE'], ['{{key}}', helperRule.value]];
    for (const [token, rule] of replacements) {
      if (!command.includes(token)) continue;
      const generated = await api.redisKey({ datasetId: Number(helperDataset.value || metadata.datasetId || 0), resource: helperResource.value.trim() || 'ups:_...', rule });
      command = command.replaceAll(token, generated.key);
    }
    command = command.replaceAll('{{owner}}', 'owner');
    editor.value = command; updateHelp(); editor.focus();
  }

  function openTemplateDialog() {
    templateDialog.querySelector('[name=command]').value = editor.value;
    templateDialog.querySelector('[name=name]').value = '';
    templateDialog.querySelector('[name=description]').value = '';
    templateDialog.showModal();
  }

  async function saveTemplate(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const templates = userDataRepository.all('redisTemplates');
    templates.unshift({ id: crypto.randomUUID(), name: form.elements.name.value.trim(), description: form.elements.description.value.trim(), command: form.elements.command.value.trim() });
    try {
      await userDataRepository.replace('redisTemplates', templates.slice(0, REDIS_TEMPLATE_LIMIT));
      templateDialog.close(); renderUserTemplates();
    } catch (error) { showError('USER_DATA_SAVE_FAILED', error.message); }
  }

  function renderUserTemplates() {
    const target = document.querySelector('#redis-user-templates');
    const templates = userDataRepository.all('redisTemplates');
    if (!templates.length) { target.innerHTML = '<p class="side-empty">Шаблонов пока нет.</p>'; return; }
    target.replaceChildren(...templates.map(item => templateCard(item, () => { editor.value = item.command; updateHelp(); editor.focus(); }, true)));
  }

  function templateCard(item, action, removable = false) {
    const card = document.createElement('button'); card.type = 'button'; card.className = 'template-card';
    card.innerHTML = `<strong>${escapeHtml(item.name)}</strong><p>${escapeHtml(item.description || '')}</p><small>${escapeHtml(item.command)}</small>`;
    card.addEventListener('click', action);
    if (removable) {
      const remove = document.createElement('span'); remove.className = 'redis-template-remove'; remove.textContent = '×'; remove.title = 'Удалить';
      remove.addEventListener('click', async event => {
        event.stopPropagation();
        try {
          await userDataRepository.replace('redisTemplates', userDataRepository.all('redisTemplates').filter(value => value.id !== item.id));
          renderUserTemplates();
        } catch (error) { showError('USER_DATA_SAVE_FAILED', error.message); }
      });
      card.append(remove);
    }
    return card;
  }

  async function addHistory(command, response) {
    const history = userDataRepository.all('redisHistory');
    history.unshift({ command, name: response.command, duration: response.executionTimeMs, at: new Date().toISOString() });
    await userDataRepository.replace('redisHistory', history.slice(0, REDIS_HISTORY_LIMIT));
    renderHistory();
  }

  function renderHistory() {
    const target = document.querySelector('#redis-history');
    const history = userDataRepository.all('redisHistory');
    if (!history.length) { target.innerHTML = '<p>История пока пуста.</p>'; return; }
    target.replaceChildren(...history.map(item => {
      const button = document.createElement('button'); button.type = 'button';
      const time = new Date(item.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      button.innerHTML = `<span>${escapeHtml(time)} · ${escapeHtml(item.name)} · ${item.duration} мс</span><small>${escapeHtml(item.command)}</small>`;
      button.addEventListener('click', () => { editor.value = item.command; updateHelp(); editor.focus(); });
      return button;
    }));
  }

  function updateHelp() {
    let command = '';
    try { command = parseCommand(editor.value).command; } catch {}
    const item = metadata.commands.find(value => value.name === command);
    help.innerHTML = item ? `<strong>${escapeHtml(item.syntax)}</strong><span>${escapeHtml(item.description)}</span>` : 'Введите READ-команду Redis.';
  }

  function showError(type, message) {
    errorBox.innerHTML = `<strong>${escapeHtml(type)}</strong><p>${escapeHtml(message)}</p>`;
    errorBox.hidden = false;
    resultMeta.textContent = 'Команда не выполнена';
  }

  function switchSide(name) {
    document.querySelectorAll('[data-redis-side]').forEach(button => button.classList.toggle('active', button.dataset.redisSide === name));
    document.querySelectorAll('[data-redis-side-panel]').forEach(panel => { panel.hidden = panel.dataset.redisSidePanel !== name; });
  }

  function switchResult(name) {
    document.querySelectorAll('[data-redis-result-tab]').forEach(button => button.classList.toggle('active', button.dataset.redisResultTab === name));
    document.querySelectorAll('[data-redis-result-panel]').forEach(panel => { panel.hidden = panel.dataset.redisResultPanel !== name; });
  }

  function setAvailable(value) {
    unavailable.hidden = value;
    document.querySelector('.redis-workspace').classList.toggle('redis-workspace--disabled', !value);
  }

  return {
    focus() { editor.focus(); },
    resetResults() { lastResponse = null; result.innerHTML = '<p class="sparql-placeholder">Результат появится здесь.</p>'; rawResult.innerHTML = ''; },
    setCapabilities(features) { setAvailable(features.redisConsole === true); }
  };
}

export function parseCommand(input) {
  const tokens = [];
  let current = '', quote = null, escaped = false;
  for (const char of String(input || '').trim()) {
    if (escaped) { current += char; escaped = false; continue; }
    if (char === '\\') { escaped = true; continue; }
    if (quote) { if (char === quote) quote = null; else current += char; continue; }
    if (char === '"' || char === "'") { quote = char; continue; }
    if (/\s/.test(char)) { if (current) { tokens.push(current); current = ''; } }
    else current += char;
  }
  if (escaped) current += '\\';
  if (quote) throw new Error('Не закрыта кавычка в Redis-команде.');
  if (current) tokens.push(current);
  return { command: (tokens.shift() || '').toUpperCase(), args: tokens };
}

function formatCommand(command, args) {
  return [command, ...args.map(value => /\s/.test(value) ? `"${value.replaceAll('"', '\\"')}"` : value)].join(' ');
}

function escapeHtml(value) {
  const element = document.createElement('span'); element.textContent = String(value ?? ''); return element.innerHTML;
}
