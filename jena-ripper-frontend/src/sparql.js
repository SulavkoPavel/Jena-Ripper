import { EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { bracketMatching, defaultHighlightStyle, syntaxHighlighting, StreamLanguage } from '@codemirror/language';
import { sparql } from '@codemirror/legacy-modes/mode/sparql';
import { api } from './api.js';
import { userDataRepository } from './user-data.js';
import { RDF_SOURCE_TYPE } from './source-types.js';

const DEFAULT_RESULT_LIMIT = 100;
const BENCHMARK_WARMUP_RUNS = 1;
const BENCHMARK_MEASURED_RUNS = 5;
const SPARQL_HISTORY_LIMIT = 20;
const OPERATION_STATUS_INTERVAL_MS = 100;
const SLOW_OPERATION_SECONDS = 5;
const VERY_SLOW_OPERATION_SECONDS = 15;

const DEFAULT_QUERY = `SELECT ?s ?p ?o
WHERE {
  ?s ?p ?o
}
LIMIT ${DEFAULT_RESULT_LIMIT}`;
const SYSTEM_TEMPLATES = [
  template('triples', 'Первые триплеты', 'Обзор содержимого Dataset', `SELECT ?s ?p ?o
WHERE { ?s ?p ?o }
LIMIT {{limit}}`, [parameter('limit', 'Лимит', 'number', DEFAULT_RESULT_LIMIT)]),
  template('classes', 'Все RDF-классы', 'Уникальные классы Dataset', `SELECT DISTINCT ?class
WHERE { ?s a ?class }
ORDER BY ?class`, []),
  template('counts', 'Количество объектов по классам', 'Размер каждого RDF-класса', `SELECT ?class (COUNT(?s) AS ?count)
WHERE { ?s a ?class }
GROUP BY ?class
ORDER BY DESC(?count)`, []),
  template('class-resources', 'Объекты класса', 'Ресурсы выбранного RDF-класса', `SELECT ?s
WHERE { ?s a {{class}} }
LIMIT {{limit}}`, [parameter('class', 'Класс', 'prefixed-uri', 'cim:Substation'),
    parameter('limit', 'Лимит', 'number', DEFAULT_RESULT_LIMIT)]),
  template('properties', 'Свойства resource', 'Исходящие свойства ресурса', `SELECT ?p ?o
WHERE { {{resource}} ?p ?o }`, [parameter('resource', 'Resource', 'prefixed-uri', 'ups:_...')]),
  template('incoming', 'Входящие связи', 'Ресурсы, которые ссылаются на выбранный', `SELECT ?s ?p
WHERE { ?s ?p {{resource}} }`, [parameter('resource', 'Resource', 'prefixed-uri', 'ups:_...')])
];

export class QueryTemplateRepository {
  all() { throw new Error('Not implemented'); }
  save() { throw new Error('Not implemented'); }
  remove() { throw new Error('Not implemented'); }
}

export class BackendQueryTemplateRepository extends QueryTemplateRepository {
  all() { return userDataRepository.all('sparqlTemplates'); }
  async save(value) {
    const items = this.all();
    const item = { ...value, id: value.id || crypto.randomUUID(), system: false, updatedAt: new Date().toISOString() };
    const index = items.findIndex(current => current.id === item.id);
    if (index < 0) items.unshift(item); else items[index] = item;
    await userDataRepository.replace('sparqlTemplates', items);
    return item;
  }
  async remove(id) { await userDataRepository.replace('sparqlTemplates', this.all().filter(item => item.id !== id)); }
  async recordRun(id, metrics) {
    const item = this.all().find(value => value.id === id);
    if (!item) return;
    const runs = (item.runCount || 0) + 1;
    await this.save({ ...item, runCount: runs, lastRunAt: new Date().toISOString(), averageTimeMs: Math.round((((item.averageTimeMs || 0) * (runs - 1)) + metrics.totalTimeMs) / runs) });
  }
}

export function initSparqlConsole({ openResource, showGraph }) {
  const repository = new BackendQueryTemplateRepository();
  const editorElement = document.querySelector('#sparql-editor');
  const resultElement = document.querySelector('#sparql-result');
  const metaElement = document.querySelector('#sparql-result-meta');
  const errorElement = document.querySelector('#sparql-error');
  const runButton = document.querySelector('#sparql-run');
  const cancelButton = document.querySelector('#sparql-cancel');
  const historyElement = document.querySelector('#sparql-history');
  const analysisElement = document.querySelector('#sparql-analysis');
  const algebraElement = document.querySelector('#sparql-algebra');
  let lastGraphResult = null;
  let running = false;
  let currentTemplateId = null;
  let editingTemplateId = null;
  let knownPrefixes = null;
  let runningTimer = null;
  let runningStartedAt = 0;
  let activeRequestId = null;
  let cancellationRequested = false;

  const editor = new EditorView({
    parent: editorElement,
    state: EditorState.create({ doc: DEFAULT_QUERY, extensions: [
      lineNumbers(), highlightActiveLineGutter(), history(), bracketMatching(), highlightActiveLine(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }), StreamLanguage.define(sparql),
      keymap.of([{ key: 'Ctrl-Enter', run: () => { execute(); return true; } }, indentWithTab, ...defaultKeymap, ...historyKeymap]),
      EditorView.lineWrapping,
      EditorView.theme({ '&': { height: '100%', fontSize: '14px' }, '.cm-content': { fontFamily: 'JetBrains Mono, Consolas, monospace', padding: '14px 0' }, '.cm-gutters': { backgroundColor: '#f5f8fa', color: '#8ca0aa', border: 'none' }, '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: '#eaf5f7' }, '&.cm-focused': { outline: 'none' } })
    ] })
  });

  runButton.addEventListener('click', () => execute());
  cancelButton.addEventListener('click', cancelActiveQuery);
  document.querySelector('#sparql-analyze').addEventListener('click', analyzeOnly);
  document.querySelector('#sparql-benchmark').addEventListener('click', benchmark);
  document.querySelector('#sparql-save-template').addEventListener('click', () => openTemplateDialog());
  document.querySelector('#sparql-history-clear').addEventListener('click', async () => {
    try { await saveHistory([]); renderHistory(); } catch (error) { renderError(error); }
  });
  document.querySelectorAll('[data-sparql-side]').forEach(button => button.addEventListener('click', () => showSidePanel(button.dataset.sparqlSide)));
  document.querySelectorAll('[data-result-tab]').forEach(button => button.addEventListener('click', () => showResultTab(button.dataset.resultTab)));
  bindTemplateDialog();
  renderTemplates();
  renderHistory();

  async function execute(queryOverride) {
    if (running) return;
    const source = (queryOverride || editor.state.doc.toString()).trim();
    if (!source) return;
    const query = await prepareQuery(source);
    activeRequestId = crypto.randomUUID();
    cancellationRequested = false;
    setRunning(true, 'execute');
    prepareRequest('Jena обрабатывает запрос…');
    try {
      const response = await api.sparql(query, activeRequestId,
        document.querySelector('#sparql-owner-rules').checked);
      lastGraphResult = ['CONSTRUCT', 'DESCRIBE'].includes(response.type) ? response : null;
      renderResult(response);
      renderAnalysis(response.analysis, response.metrics);
      renderAlgebra(response.algebra);
      try { await addHistory(query, response); } catch (error) { console.warn('Не удалось сохранить SPARQL history:', error.message); }
      if (currentTemplateId) {
        try { await repository.recordRun(currentTemplateId, response.metrics); renderTemplates(); }
        catch (error) { console.warn('Не удалось обновить статистику SPARQL template:', error.message); }
      }
    } catch (error) {
      if (error.details?.type === 'SPARQL_CANCELLED') renderCancelled();
      else renderError(error);
    } finally {
      activeRequestId = null;
      setRunning(false);
    }
  }

  async function cancelActiveQuery() {
    if (!running || !activeRequestId || cancellationRequested) return;
    cancellationRequested = true;
    cancelButton.disabled = true;
    cancelButton.textContent = 'Останавливаем…';
    document.querySelector('#sparql-running-hint').textContent = 'Останавливаем запрос…';
    try {
      const response = await api.cancelSparql(activeRequestId);
      if (!response.cancelled) throw new Error('Запрос уже завершился.');
    } catch (error) {
      cancellationRequested = false;
      cancelButton.disabled = false;
      cancelButton.textContent = '■ Отменить';
      document.querySelector('#sparql-running-hint').textContent = error.code === 'BACKEND_UNAVAILABLE'
        ? 'Не удалось связаться с backend для отмены.'
        : error.message;
    }
  }

  async function analyzeOnly() {
    if (running) return;
    const query = await prepareQuery(editor.state.doc.toString().trim());
    if (!query) return;
    setRunning(true, 'analyze');
    errorElement.hidden = true;
    try {
      const response = await api.analyze(query);
      renderAnalysis(response.analysis, null);
      renderAlgebra(response.algebra);
      showResultTab('analysis');
      metaElement.textContent = `${response.type} · статический анализ без выполнения`;
    } catch (error) { renderError(error, true); }
    finally { setRunning(false); }
  }

  async function benchmark() {
    const totalRuns = BENCHMARK_WARMUP_RUNS + BENCHMARK_MEASURED_RUNS;
    if (running || !window.confirm(`Запустить ${BENCHMARK_WARMUP_RUNS} прогрев и ${BENCHMARK_MEASURED_RUNS} измерений? Запрос выполнится ${totalRuns} раз.`)) return;
    const query = await prepareQuery(editor.state.doc.toString().trim());
    if (!query) return;
    document.querySelector('#sparql-more').open = false;
    setRunning(true, 'benchmark');
    errorElement.hidden = true;
    try {
      const useOwnerRules = document.querySelector('#sparql-owner-rules').checked;
      const response = await api.benchmark(query, BENCHMARK_WARMUP_RUNS,
        BENCHMARK_MEASURED_RUNS, useOwnerRules);
      renderBenchmark(response);
      metaElement.textContent = `Benchmark · Owner Rules: ${useOwnerRules ? 'включены' : 'выключены'}`;
      showResultTab('analysis');
    } catch (error) { renderError(error); }
    finally { setRunning(false); }
  }

  function prepareRequest(message) {
    errorElement.hidden = true;
    metaElement.textContent = 'Выполнение запроса…';
    resultElement.innerHTML = `<p class="sparql-placeholder">${message}</p>`;
    showResultTab('result');
  }

  function setRunning(value, mode) {
    running = value;
    const analyzeButton = document.querySelector('#sparql-analyze');
    const benchmarkButton = document.querySelector('#sparql-benchmark');
    document.querySelectorAll('#sparql-run, #sparql-analyze, #sparql-benchmark').forEach(button => { button.disabled = value; });
    const hint = document.querySelector('#sparql-running-hint');
    if (runningTimer) { window.clearInterval(runningTimer); runningTimer = null; }
    if (!value) {
      runButton.innerHTML = '<span aria-hidden="true">▶</span> Выполнить';
      analyzeButton.textContent = 'Анализировать';
      benchmarkButton.textContent = 'Benchmark';
      cancelButton.hidden = true;
      cancelButton.disabled = false;
      cancelButton.textContent = '■ Отменить';
      cancellationRequested = false;
      hint.textContent = '';
      return;
    }
    if (mode !== 'execute') {
      runButton.innerHTML = '<span aria-hidden="true">▶</span> Выполнить';
      if (mode === 'analyze') analyzeButton.textContent = 'Анализ…';
      if (mode === 'benchmark') benchmarkButton.textContent = 'Benchmark…';
      return;
    }
    cancelButton.hidden = false;
    runningStartedAt = performance.now();
    const update = () => {
      const seconds = (performance.now() - runningStartedAt) / 1000;
      runButton.innerHTML = `<span class="button-spinner" aria-hidden="true"></span> Выполняется… ${seconds.toFixed(1)} с`;
      hint.textContent = seconds >= VERY_SLOW_OPERATION_SECONDS
        ? 'Запрос выполняется дольше обычного.'
        : seconds >= SLOW_OPERATION_SECONDS ? 'Запрос всё ещё выполняется…' : '';
    };
    update();
    runningTimer = window.setInterval(update, OPERATION_STATUS_INTERVAL_MS);
  }

  function renderResult(response) {
    const metrics = response.metrics;
    const suffix = response.truncated ? ` · показаны первые ${response.limit}` : '';
    const unit = response.type === 'SELECT' ? plural(metrics.resultCount, 'строка', 'строки', 'строк') : ['CONSTRUCT', 'DESCRIBE'].includes(response.type) ? plural(metrics.resultCount, 'триплет', 'триплета', 'триплетов') : '';
    metaElement.textContent = `✓ Выполнено за ${formatDuration(metrics.executionTimeMs)}${response.type === 'ASK' ? '' : ` • ${metrics.resultCount} ${unit}`}${suffix}`;
    if (response.type === 'SELECT') return renderBindingsTable(response.variables, response.rows);
    if (response.type === 'ASK') {
      resultElement.innerHTML = `<div class="ask-result ${response.value ? 'true' : 'false'}"><span>${response.value ? '✓' : '✕'}</span><strong>${response.value ? 'TRUE' : 'FALSE'}</strong></div>`;
      return;
    }
    renderGraphResult(response);
  }

  function renderBindingsTable(variables, rows) {
    if (!rows.length) { resultElement.innerHTML = '<p class="sparql-placeholder">Запрос выполнен, строк не найдено.</p>'; return; }
    const headers = variables.map(variable => `<th>?${escapeHtml(variable)}</th>`).join('');
    const body = rows.map(row => `<tr>${variables.map(variable => `<td>${bindingHtml(row[variable])}</td>`).join('')}</tr>`).join('');
    resultElement.innerHTML = `<div class="sparql-table-wrap"><table><thead><tr>${headers}</tr></thead><tbody>${body}</tbody></table></div>`;
    bindResourceLinks();
  }

  function renderGraphResult(response) {
    const body = response.statements.map(statement => `<tr><td>${bindingHtml(statement.subject)}</td><td>${bindingHtml(statement.predicate)}</td><td>${bindingHtml(statement.object)}</td></tr>`).join('');
    resultElement.innerHTML = `<div class="graph-result-actions"><button id="sparql-show-graph" type="button">Показать как граф</button></div><div class="sparql-table-wrap"><table><thead><tr><th>subject</th><th>predicate</th><th>object</th></tr></thead><tbody>${body}</tbody></table></div>`;
    document.querySelector('#sparql-show-graph').addEventListener('click', () => showGraph(lastGraphResult));
    bindResourceLinks();
  }

  function renderAnalysis(analysis, metrics) {
    const metricsHtml = metrics ? `<div class="metrics-grid">
      ${metric('Выполнение Jena', `${metrics.executionTimeMs} мс`)}${metric('Формирование ответа', `${metrics.serializationTimeMs} мс`)}${metric('Общее время', `${metrics.totalTimeMs} мс`)}${metric('Результатов', metrics.resultCount)}${metric('Оценка', speedLabel(metrics.speed))}${metric('Статус', metrics.status === 'SUCCESS' ? 'Успешно' : metrics.status)}
    </div>` : '<p class="analysis-note">Запрос не выполнялся: показан только статический анализ.</p>';
    const structure = `<div class="structure-grid">${metric('Triple patterns', analysis.triplePatterns)}${metric('Переменных', analysis.variables)}${metric('Проецируется', analysis.projectedVariables)}${metric('FILTER', analysis.filters)}${metric('OPTIONAL', analysis.optionals)}${metric('UNION', analysis.unions)}${metric('Property paths', analysis.propertyPaths)}</div>`;
    const recommendations = analysis.recommendations.length
      ? analysis.recommendations.map(item => recommendationHtml(item)).join('')
      : '<div class="recommendation info"><strong>INFO · Явных рисков не найдено</strong><p>Статический анализ не обнаружил правил, требующих внимания.</p></div>';
    analysisElement.innerHTML = `<div class="analysis-scroll"><h3>Фактические метрики</h3>${metricsHtml}<h3>Структура запроса</h3>${structure}<h3>Query Doctor</h3><div class="recommendations">${recommendations}</div><div id="sparql-benchmark-result"></div></div>`;
    analysisElement.querySelector('[data-add-limit]')?.addEventListener('click', () => {
      setQuery(`${editor.state.doc.toString().trim()}\nLIMIT ${DEFAULT_RESULT_LIMIT}`);
    });
  }

  function renderAlgebra(algebra) {
    algebraElement.innerHTML = `<div class="algebra-scroll"><details open><summary>Исходная алгебра</summary><pre>${escapeHtml(algebra.original)}</pre></details><details><summary>Оптимизированная алгебра</summary><pre>${escapeHtml(algebra.optimized)}</pre></details></div>`;
  }

  function renderBenchmark(value) {
    let host = document.querySelector('#sparql-benchmark-result');
    if (!host) { analysisElement.innerHTML = '<div class="analysis-scroll"><div id="sparql-benchmark-result"></div></div>'; host = document.querySelector('#sparql-benchmark-result'); }
    host.innerHTML = `<section class="benchmark-result"><h3>Benchmark</h3><p><strong>Прогрев:</strong> ${value.warmupTimesMs.map(time => `${time} мс`).join(', ')}</p><ol>${value.runTimesMs.map(time => `<li>${time} мс</li>`).join('')}</ol><div class="metrics-grid">${metric('Min', `${value.minTimeMs} мс`)}${metric('Median', `${value.medianTimeMs} мс`)}${metric('Average', `${Math.round(value.averageTimeMs)} мс`)}${metric('Max', `${value.maxTimeMs} мс`)}</div></section>`;
  }

  function recommendationHtml(item) {
    const messages = {
      MISSING_LIMIT: ['Нет LIMIT', 'Запрос потенциально может вернуть большое количество результатов.'], SELECT_STAR: ['Используется SELECT *', 'Явное перечисление переменных делает результат понятнее и может уменьшить объём передачи.'], DISTINCT: ['DISTINCT', 'Удаление дубликатов является отдельной операцией.'], ORDER_BY: ['ORDER BY', 'Сортировка большого результата может потребовать дополнительное время и память.'], AGGREGATION: ['Группировка и агрегаты', 'Запрос использует GROUP BY или агрегатную функцию.'], REGEX_FILTER: ['REGEX в FILTER', 'REGEX может быть дорогой операцией на больших промежуточных наборах.'], MANY_OPTIONALS: ['Несколько OPTIONAL', `Обнаружено OPTIONAL-блоков: ${item.data?.count || 0}.`], MANY_UNIONS: ['Несколько UNION-ветвей', `Обнаружено альтернативных ветвей: ${item.data?.count || 0}.`], RECURSIVE_PROPERTY_PATH: ['Рекурсивный property path', 'Путь * или + может обходить большое количество RDF resources.'], CARTESIAN_PRODUCT: ['Возможное декартово произведение', 'Обнаружены независимые группы triple patterns. Это может резко увеличить промежуточный результат.']
    };
    const [title, body] = messages[item.code] || [item.code, ''];
    const action = item.code === 'MISSING_LIMIT'
      ? `<button type="button" data-add-limit>Добавить LIMIT ${DEFAULT_RESULT_LIMIT}</button>` : '';
    return `<div class="recommendation ${item.severity.toLowerCase()}"><strong>${item.severity} · ${title}</strong><p>${body}</p>${action}</div>`;
  }

  function renderTemplates() {
    renderTemplateGroup(document.querySelector('#sparql-system-templates'), SYSTEM_TEMPLATES, true);
    renderTemplateGroup(document.querySelector('#sparql-user-templates'), repository.all(), false);
  }

  function renderTemplateGroup(host, templates, system) {
    host.innerHTML = templates.length ? '' : '<p class="side-empty">Шаблонов пока нет.</p>';
    templates.forEach(item => {
      const card = document.createElement('article');
      card.className = 'template-card';
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      const stats = !system && item.runCount ? `<small>Запусков: ${item.runCount} · среднее ${item.averageTimeMs} мс</small>` : '';
      const menu = system ? '' : `<details class="template-menu"><summary aria-label="Действия с шаблоном">⋯</summary><div><button type="button" data-edit>Редактировать</button><button type="button" data-delete>Удалить</button></div></details>`;
      card.innerHTML = `<strong>${escapeHtml(item.name)}</strong><p>${escapeHtml(item.description || '')}</p>${stats}${menu}`;
      card.addEventListener('click', event => { if (!event.target.closest('.template-menu')) useTemplate(item); });
      card.addEventListener('keydown', event => { if ((event.key === 'Enter' || event.key === ' ') && !event.target.closest('.template-menu')) { event.preventDefault(); useTemplate(item); } });
      card.querySelector('[data-edit]')?.addEventListener('click', event => { event.stopPropagation(); openTemplateDialog(item); });
      card.querySelector('[data-delete]')?.addEventListener('click', async event => {
        event.stopPropagation();
        if (!window.confirm(`Удалить шаблон «${item.name}»?`)) return;
        try { await repository.remove(item.id); renderTemplates(); } catch (error) { renderError(error); }
      });
      host.append(card);
    });
  }

  async function useTemplate(item) {
    try {
      currentTemplateId = item.system ? null : item.id;
      document.querySelector('#sparql-owner-rules').checked = item.options?.useOwnerRules === true;
      if (!item.parameters?.length) { setQuery(await addRequiredPrefixes(item.query)); showSidePanel('templates'); return; }
      const usableDefaults = item.parameters.every(parameter => {
        const value = parameter.defaultValue;
        return value !== undefined && value !== null && String(value).trim() && !String(value).includes('...');
      });
      if (usableDefaults) {
        knownPrefixes ||= await api.prefixes();
        const values = Object.fromEntries(item.parameters.map(parameter => [parameter.name, parameter.defaultValue]));
        const query = applyParameters(item.query, item.parameters, values, knownPrefixes);
        setQuery(await addRequiredPrefixes(query));
        showSidePanel('templates');
        return;
      }
      await openParameterDialog(item);
    } catch (error) {
      renderError(error);
    }
  }

  async function openParameterDialog(item) {
    knownPrefixes ||= await api.prefixes();
    const dialog = document.querySelector('#sparql-parameter-dialog');
    dialog.querySelector('h2').textContent = item.name;
    dialog.querySelector('p').textContent = item.description || '';
    const fields = dialog.querySelector('.parameter-fields');
    fields.innerHTML = item.parameters.map(parameter => `<label>${escapeHtml(parameter.label)}<input name="${escapeHtml(parameter.name)}" value="${escapeHtml(parameter.defaultValue ?? '')}" required></label>`).join('');
    dialog.returnValue = '';
    dialog.showModal();
    const action = await waitDialog(dialog);
    if (!action) return;
    try {
      const values = Object.fromEntries(new FormData(dialog.querySelector('form')).entries());
      const query = applyParameters(item.query, item.parameters, values, knownPrefixes);
      setQuery(query);
    } catch (error) { window.alert(error.message); }
  }

  function bindTemplateDialog() {
    const dialog = document.querySelector('#sparql-template-dialog');
    dialog.querySelector('form').addEventListener('submit', async event => {
      event.preventDefault();
      const data = new FormData(event.currentTarget);
      const query = String(data.get('query')).trim();
      try {
        await repository.save({ id: editingTemplateId, name: String(data.get('name')).trim(), description: String(data.get('description')).trim(), query,
          parameters: inferParameters(query), options: { useOwnerRules: document.querySelector('#sparql-owner-rules').checked } });
        dialog.close(); renderTemplates(); showSidePanel('templates');
      } catch (error) { renderError(error); }
    });
  }

  function openTemplateDialog(item = null) {
    editingTemplateId = item?.id || null;
    const dialog = document.querySelector('#sparql-template-dialog');
    dialog.querySelector('h2').textContent = item ? 'Редактировать шаблон' : 'Сохранить как шаблон';
    dialog.querySelector('[name=name]').value = item?.name || '';
    dialog.querySelector('[name=description]').value = item?.description || '';
    dialog.querySelector('[name=query]').value = item?.query || editor.state.doc.toString();
    dialog.showModal();
  }

  function renderHistory() {
    const items = loadHistory();
    historyElement.innerHTML = items.length ? '' : '<p>История пуста</p>';
    items.forEach(item => {
      const button = document.createElement('button'); button.type = 'button';
      const time = new Date(item.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      button.innerHTML = `<span>${time} · ${escapeHtml(item.type)} · ${item.metrics?.totalTimeMs ?? '—'} мс</span><small>${escapeHtml(oneLine(item.query))}</small>`;
      button.addEventListener('click', () => { setQuery(item.query); document.querySelector('#sparql-owner-rules').checked = item.useOwnerRules === true; if (item.analysis) renderAnalysis(item.analysis, item.metrics); if (item.algebra) renderAlgebra(item.algebra); metaElement.textContent = `Сохранённый запуск · ${item.metrics?.resultCount ?? 0} результатов`; });
      historyElement.append(button);
    });
  }

  async function addHistory(query, response) {
    const items = loadHistory();
    items.unshift({ query, type: response.type, useOwnerRules: document.querySelector('#sparql-owner-rules').checked,
      time: new Date().toISOString(), metrics: response.metrics, analysis: response.analysis, algebra: response.algebra });
    await saveHistory(items.slice(0, SPARQL_HISTORY_LIMIT)); renderHistory();
  }

  async function prepareQuery(source) {
    if (!source) return '';
    try {
      const prepared = await addRequiredPrefixes(source);
      if (prepared !== source) setQuery(prepared);
      return prepared;
    } catch (error) {
      renderError(error);
      return '';
    }
  }

  async function addRequiredPrefixes(source) {
    const declared = new Set([...source.matchAll(/^\s*PREFIX\s+([A-Za-z][\w-]*)\s*:/gim)].map(match => match[1].toLowerCase()));
    const searchable = source
      .replace(/<[^>]*>/g, ' ')
      .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, ' ')
      .replace(/#[^\r\n]*/g, ' ');
    const candidates = new Set([...searchable.matchAll(/\b([A-Za-z][\w-]*):(?=[A-Za-z0-9_])/g)]
      .map(match => match[1].toLowerCase())
      .filter(prefix => !declared.has(prefix)));
    if (!candidates.size) return source;
    knownPrefixes ||= await api.prefixes();
    const byLowerCase = new Map(Object.entries(knownPrefixes).map(([prefix, uri]) => [prefix.toLowerCase(), [prefix, uri]]));
    const missing = [...candidates]
      .map(prefix => byLowerCase.get(prefix))
      .filter(Boolean)
      .map(([prefix, uri]) => `PREFIX ${prefix}: <${uri}>`);
    return missing.length ? `${missing.join('\n')}\n\n${source}` : source;
  }

  function bindingHtml(binding) {
    if (!binding) return '<span class="unbound">—</span>';
    const label = escapeHtml(binding.displayValue || binding.value); const title = escapeHtml(binding.value);
    if (binding.type === 'uri') return `<button class="sparql-resource" type="button" data-uri="${title}" title="${title}">${label}</button>`;
    const metadata = binding.language ? `@${binding.language}` : binding.datatype ? `^^${binding.datatype}` : '';
    return `<span class="sparql-value" title="${escapeHtml(`${binding.value}${metadata}`)}">${label}</span>`;
  }

  function bindResourceLinks() { resultElement.querySelectorAll('[data-uri]').forEach(button => button.addEventListener('click', () => openResource(button.dataset.uri))); }
  function renderError(error, analysisOnly = false) {
    const details = error.details || {};
    const network = error.code === 'BACKEND_UNAVAILABLE';
    const title = analysisOnly ? 'Невозможно проанализировать запрос' : network ? 'Не удалось выполнить запрос' : 'Ошибка SPARQL';
    const summary = analysisOnly ? 'Сначала исправьте синтаксис SPARQL.' : network ? 'Backend Jena Ripper недоступен.' : errorSummary(details.type);
    const location = details.line ? `Строка ${details.line}${details.column ? `, позиция ${details.column}` : ''}` : '';
    const technical = network ? '' : error.message;
    errorElement.hidden = true;
    metaElement.textContent = `⚠ ${title}`;
    resultElement.innerHTML = `<div class="sparql-error-state" role="alert"><span class="error-state-icon" aria-hidden="true">⚠</span><div><strong>${escapeHtml(title)}</strong><p>${escapeHtml(summary)}</p>${location ? `<div class="error-location">${escapeHtml(location)}</div>` : ''}${technical && technical !== summary ? `<code>${escapeHtml(technical)}</code>` : ''}</div></div>`;
    showResultTab('result');
  }
  function renderCancelled() {
    errorElement.hidden = true;
    metaElement.textContent = 'Запрос отменён';
    resultElement.innerHTML = '<div class="sparql-cancelled-state" role="status"><span aria-hidden="true">■</span><div><strong>Запрос отменён</strong><p>Выполнение остановлено пользователем.</p></div></div>';
    showResultTab('result');
  }
  function setQuery(query) { editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: query } }); editor.focus(); }
  function showSidePanel(name) { document.querySelectorAll('[data-sparql-side]').forEach(button => button.classList.toggle('active', button.dataset.sparqlSide === name)); document.querySelectorAll('[data-side-panel]').forEach(panel => panel.hidden = panel.dataset.sidePanel !== name); }
  function showResultTab(name) { document.querySelectorAll('[data-result-tab]').forEach(button => button.classList.toggle('active', button.dataset.resultTab === name)); document.querySelectorAll('[data-result-panel]').forEach(panel => panel.hidden = panel.dataset.resultPanel !== name); }
  return {
    focus: () => editor.focus(),
    resetResults() {
      lastGraphResult = null;
      metaElement.textContent = 'Запрос ещё не выполнялся';
      resultElement.innerHTML = '<p class="sparql-placeholder">Результат появится здесь.</p>';
      analysisElement.innerHTML = '<p class="sparql-placeholder">Выполните или проанализируйте запрос.</p>';
      algebraElement.innerHTML = '<p class="sparql-placeholder">Алгебра появится после анализа.</p>';
    },
    setCapabilities(features) {
      const option = document.querySelector('#sparql-owner-rules-option');
      option.hidden = features?.sparqlOwnerRules !== true;
      if (option.hidden) document.querySelector('#sparql-owner-rules').checked = false;
      const remote = features?.sourceType === RDF_SOURCE_TYPE.CIM_API;
      document.querySelector('.sparql-panel-heading p').textContent = remote
        ? 'Запросы выполняются через выбранную модель CIM App API.'
        : 'Запросы выполняются к текущему Apache Jena Dataset.';
      document.querySelector('.read-only-badge').textContent = remote
        ? 'SELECT · ASK · CONSTRUCT' : 'SELECT · ASK · CONSTRUCT · DESCRIBE';
    }
  };
}

function template(id, name, description, query, parameters) { return { id, name, description, category: 'Системные', query, parameters, system: true }; }
function parameter(name, label, type, defaultValue) { return { name, label, type, defaultValue, required: true }; }
function inferParameters(query) { return [...query.matchAll(/\{\{([a-zA-Z][\w-]*)}}/g)].map(match => { const name = match[1]; const types = { limit: 'number', class: 'prefixed-uri', resource: 'prefixed-uri', name: 'text' }; return parameter(name, ({ limit: 'Лимит', class: 'Класс', resource: 'Resource', name: 'Название' })[name] || name, types[name] || 'text', name === 'limit' ? DEFAULT_RESULT_LIMIT : ''); }); }
function applyParameters(query, parameters, values, prefixes) { let result = query; for (const parameter of parameters) { const value = String(values[parameter.name] ?? '').trim(); if (parameter.required && !value) throw new Error(`Заполните параметр «${parameter.label}».`); const encoded = encodeParameter(value, parameter.type, prefixes); result = result.replaceAll(`{{${parameter.name}}}`, encoded); } if (/\{\{[^}]+}}/.test(result)) throw new Error('Не все параметры шаблона заполнены.'); return result; }
function encodeParameter(value, type, prefixes) { if (type === 'number') { if (!/^-?(?:\d+|\d*\.\d+)$/.test(value) || !Number.isFinite(Number(value))) throw new Error('Ожидалось корректное число.'); return value; } if (type === 'text') return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r/g, '\\r').replace(/\n/g, '\\n').replace(/\t/g, '\\t')}"`; if (value.startsWith('<') && value.endsWith('>')) { validateAbsoluteUri(value.slice(1, -1)); return value; } if (/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) { validateAbsoluteUri(value); return `<${value}>`; } const match = /^([A-Za-z][\w-]*):([^\s<>"{}|^`\\]+)$/.exec(value); if (!match || !prefixes[match[1]]) throw new Error('Ожидался известный compact URI или абсолютный URI.'); return value; }
function validateAbsoluteUri(value) { try { const uri = new URL(value); if (!uri.protocol || /[<>"{}|^`\\\s]/.test(value)) throw new Error(); } catch { throw new Error('Некорректный абсолютный URI.'); } }
function waitDialog(dialog) { return new Promise(resolve => { const done = () => { dialog.removeEventListener('close', done); resolve(dialog.returnValue || null); }; dialog.addEventListener('close', done); }); }
function metric(label, value) { return `<div><span>${label}</span><strong>${value}</strong></div>`; }
function speedLabel(value) { return ({ VERY_FAST: 'Очень быстро', FAST: 'Быстро', MEDIUM: 'Средне', SLOW: 'Медленно', VERY_SLOW: 'Очень медленно' })[value] || value; }
function errorSummary(type) { return ({ SPARQL_PARSE_ERROR: 'Не удалось разобрать запрос.', SPARQL_EXECUTION_ERROR: 'Не удалось выполнить запрос.', SPARQL_TIMEOUT: 'Превышено допустимое время выполнения запроса.', SPARQL_UPDATE_DISABLED: 'Изменение Dataset через SPARQL отключено.' })[type] || 'Не удалось выполнить запрос.'; }
function formatDuration(milliseconds) { return milliseconds < 1000 ? `${milliseconds} мс` : `${(milliseconds / 1000).toFixed(2)} с`; }
function plural(value, one, few, many) { const lastTwo = value % 100; const last = value % 10; if (lastTwo >= 11 && lastTwo <= 19) return many; if (last === 1) return one; if (last >= 2 && last <= 4) return few; return many; }
function loadHistory() { return userDataRepository.all('sparqlHistory'); }
function saveHistory(history) { return userDataRepository.replace('sparqlHistory', history); }
function oneLine(query) { const value = query.replace(/\s+/g, ' ').trim(); return value.length > 72 ? `${value.slice(0, 71)}…` : value; }
function escapeHtml(value) { const element = document.createElement('span'); element.textContent = value == null ? '' : String(value); return element.innerHTML; }
