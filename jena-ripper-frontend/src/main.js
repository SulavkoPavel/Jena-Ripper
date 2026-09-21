import './styles.css';
import { api } from './api.js';
import { RdfGraph } from './graph.js';
import { initSparqlConsole } from './sparql.js';
import { initRedisConsole } from './redis-console.js';
import { initMetamodelExplorer } from './metamodel.js';
import { initConnectionSettings } from './settings.js';
import { initializeUserData } from './user-data.js';
import { RDF_SOURCE_TYPE } from './source-types.js';

const APPLICATION_RESTART_TIMEOUT_MS = 60_000;
const APPLICATION_RESTART_POLL_INTERVAL_MS = 500;
const SHUTDOWN_SCREEN_DELAY_MS = 1_500;
const METAMODEL_SEARCH_RESULT_LIMIT = 12;
const OPERATION_STATUS_INTERVAL_MS = 100;
const SLOW_OPERATION_SECONDS = 5;

async function bootstrap() {
  try {
    await initializeUserData();
  } catch (error) {
    console.warn('Пользовательские шаблоны и история пока недоступны:', error.message);
  }

const welcome = document.querySelector('#welcome');
const graphContainer = document.querySelector('#graph');
const detailsPanel = document.querySelector('#details');
const message = document.querySelector('#message');
const results = document.querySelector('#search-results');
const searchForm = document.querySelector('#search-form');
const searchInput = document.querySelector('#search-input');
const status = document.querySelector('#header-status');
const clearButton = document.querySelector('#clear-button');
const graphMode = document.querySelector('#graph-mode');
const sparqlMode = document.querySelector('#sparql-mode');
const redisMode = document.querySelector('#redis-mode');
const metamodelMode = document.querySelector('#metamodel-mode');
const graphModeButton = document.querySelector('#graph-mode-button');
const sparqlModeButton = document.querySelector('#sparql-mode-button');
const redisModeButton = document.querySelector('#redis-mode-button');
const metamodelModeButton = document.querySelector('#metamodel-mode-button');
const shutdownButton = document.querySelector('#shutdown-button');
const shutdownDialog = document.querySelector('#shutdown-dialog');
const shutdownConfirm = document.querySelector('#shutdown-confirm');
const shutdownError = document.querySelector('#shutdown-error');
let graph;
let detailsRequest = 0;
let ownerRulesAvailable = false;
let redisRulesAvailable = false;
let currentDetails = null;
let lastSuccessfulConnectionAt = 0;
let autocompleteRequest = 0;
let metamodelAutocompleteItems = [];
let metamodelAutocompleteIndex = -1;
const ownerRulesCache = new Map();
const redisRulesCache = new Map();
const redisRulesRequests = new Map();
const connectionSettings = initConnectionSettings({
  onRestartRequired: restartForConnectionProfile,
  onSourceChanged: () => {
    clearCurrentGraph();
    ownerRulesCache.clear();
    redisRulesCache.clear();
    sparqlConsole.resetResults();
    redisConsole.resetResults();
    loadStatus();
  }
});

async function restartForConnectionProfile(profileId) {
  clearCurrentGraph();
  ownerRulesCache.clear();
  redisRulesCache.clear();
  sparqlConsole.resetResults();
  redisConsole.resetResults();
  const previousRuntime = await api.applicationRuntime().catch(() => null);
  await api.restartApplication();
  const deadline = Date.now() + APPLICATION_RESTART_TIMEOUT_MS;
  while (Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, APPLICATION_RESTART_POLL_INTERVAL_MS));
    try {
      if (previousRuntime?.instanceId) {
        const runtime = await api.applicationRuntime();
        if (runtime.instanceId === previousRuntime.instanceId) continue;
      }
      const response = await api.status();
      if (response.features?.activeProfileId === profileId) {
        window.location.reload();
        return;
      }
    } catch (_) {
      // The backend is expected to be temporarily unavailable while its context restarts.
    }
  }
  throw new Error('Backend не успел применить профиль подключения. Проверьте журнал приложения.');
}

const sparqlConsole = initSparqlConsole({
  openResource: resource => {
    switchMode('graph');
    openResource(resource);
  },
  showGraph: response => {
    switchMode('graph');
    ensureGraph();
    graph.load(response);
    detailsRequest++;
    detailsPanel.hidden = true;
    message.hidden = true;
    updateGraphControls();
  }
});
const redisConsole = initRedisConsole();
const metamodelExplorer = initMetamodelExplorer();

loadStatus();
loadApplicationRuntime();

graphModeButton.addEventListener('click', () => switchMode('graph'));
sparqlModeButton.addEventListener('click', () => switchMode('sparql'));
redisModeButton.addEventListener('click', () => switchMode('redis'));
metamodelModeButton.addEventListener('click', () => switchMode('metamodel'));

document.querySelector('#start-form').addEventListener('submit', event => {
  event.preventDefault();
  openResource(document.querySelector('#start-uri').value);
});

searchForm.addEventListener('submit', async event => {
  event.preventDefault();
  const query = searchInput.value.trim();
  if (!query) return;
  if (metamodelModeButton.classList.contains('active')) {
    await metamodelExplorer.show();
    const found = metamodelExplorer.search(query);
    if (!found.length) {
      closeMetamodelAutocomplete(false);
      results.hidden = false;
      results.innerHTML = '<p class="search-empty">Класс не найден</p>';
      searchInput.setAttribute('aria-expanded', 'true');
      return;
    }
    showMetamodelAutocomplete(found);
    metamodelExplorer.focus(found[0]);
    return;
  }
  results.hidden = false;
  searchInput.setAttribute('aria-expanded', 'true');
  results.innerHTML = '<p class="search-empty">Поиск…</p>';
  try {
    const found = await api.search(query);
    markConnectionAvailable();
    if (!found.length) {
      results.innerHTML = '<p class="search-empty">Ничего не найдено</p>';
      return;
    }
    results.replaceChildren(...found.map(searchResult));
  } catch (error) {
    results.hidden = true;
    searchInput.setAttribute('aria-expanded', 'false');
    showMessage(error.message, true);
  }
});

searchInput.addEventListener('input', updateMetamodelAutocomplete);
searchInput.addEventListener('focus', updateMetamodelAutocomplete);
searchInput.addEventListener('keydown', event => {
  if (!metamodelModeButton.classList.contains('active') || results.hidden) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeMetamodelAutocomplete();
    return;
  }
  if (!metamodelAutocompleteItems.length) return;
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    setMetamodelAutocompleteIndex((metamodelAutocompleteIndex + 1) % metamodelAutocompleteItems.length);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    setMetamodelAutocompleteIndex((metamodelAutocompleteIndex - 1 + metamodelAutocompleteItems.length)
      % metamodelAutocompleteItems.length);
  } else if (event.key === 'Enter' && metamodelAutocompleteIndex >= 0) {
    event.preventDefault();
    selectMetamodelClass(metamodelAutocompleteItems[metamodelAutocompleteIndex]);
  }
});

document.addEventListener('pointerdown', event => {
  if (!searchForm.contains(event.target)) closeMetamodelAutocomplete();
});

clearButton.addEventListener('click', clearCurrentGraph);
document.querySelector('#reset-button').addEventListener('click', startNewGraph);
shutdownButton.addEventListener('click', () => {
  shutdownError.hidden = true;
  shutdownDialog.showModal();
});
document.querySelector('#shutdown-cancel').addEventListener('click', () => shutdownDialog.close());
shutdownConfirm.addEventListener('click', shutdownApplication);

async function loadApplicationRuntime() {
  try {
    const runtime = await api.applicationRuntime();
    shutdownButton.hidden = runtime.shutdownAvailable !== true;
  } catch (_) {
    shutdownButton.hidden = true;
  }
}

async function shutdownApplication() {
  shutdownConfirm.disabled = true;
  shutdownConfirm.textContent = 'Завершение...';
  shutdownError.hidden = true;
  try {
    await api.shutdownApplication();
    shutdownDialog.close();
    const screen = document.querySelector('#shutdown-screen');
    screen.hidden = false;
    setTimeout(() => {
      document.querySelector('#shutdown-title').textContent = 'Jena Ripper выключен.';
      document.querySelector('#shutdown-message').textContent = 'Эту вкладку можно закрыть.';
    }, SHUTDOWN_SCREEN_DELAY_MS);
  } catch (error) {
    shutdownError.textContent = error.message;
    shutdownError.hidden = false;
    shutdownConfirm.disabled = false;
    shutdownConfirm.textContent = 'Выключить';
  }
}

async function loadStatus() {
  const requestStartedAt = Date.now();
  try {
    const response = await api.status();
    const profiles = await api.connectionProfiles().catch(() => null);
    if (profiles) connectionSettings.setProfiles(profiles);
    const activeProfile = profiles?.profiles.find(profile => profile.id === profiles.activeProfileId);
    const runtimeProfile = profiles?.profiles.find(profile => profile.id === response.features?.activeProfileId)
      || activeProfile;
    const available = response.dataset.available || lastSuccessfulConnectionAt >= requestStartedAt;
    status.className = `status ${available ? 'status--up' : 'status--down'}`;
    renderConnectionStatus(runtimeProfile,
      response.dataset.available ? 'Dataset подключён' : 'Dataset недоступен');
    status.title = runtimeProfile
      ? connectionTooltip(runtimeProfile, available ? null : response.dataset.error,
          activeProfile?.id !== runtimeProfile.id ? activeProfile : null)
      : response.dataset.error || `${response.dataset.type}${response.dataset.path ? ` · ${response.dataset.path}` : ''}`;
    ownerRulesAvailable = response.features?.ownerRules === true;
    redisRulesAvailable = response.features?.redisRules === true;
    connectionSettings.setRuntimeProfileId(runtimeProfile?.id || response.features?.activeProfileId);
    connectionSettings.setCapabilities(response.features || {});
    sparqlConsole.setCapabilities(response.features || {});
    redisConsole.setCapabilities(response.features || {});
    if (currentDetails) renderDetails(currentDetails);
  } catch (error) {
    if (lastSuccessfulConnectionAt >= requestStartedAt) return;
    status.className = 'status status--down';
    status.innerHTML = '<i></i> Backend недоступен';
    status.title = error.message;
  }
}

function renderConnectionStatus(activeProfile, fallback) {
  const dot = document.createElement('i');
  const label = document.createElement('span');
  label.className = 'status-label';
  label.textContent = activeProfile?.name || fallback;
  status.replaceChildren(dot, label);
}

function connectionTooltip(profile, connectionError, pendingProfile) {
  const jena = profile.jena;
  const lines = [`Профиль: ${profile.name}`];
  if (jena.sourceType === RDF_SOURCE_TYPE.CIM_API) {
    lines.push('Источник: CIM App API');
    const model = jena.cimApi?.modelName;
    const modelId = jena.cimApi?.modelId;
    if (model || modelId != null) lines.push(`Модель: ${model || 'Без названия'}${modelId != null ? ` (#${modelId})` : ''}`);
  } else {
    lines.push(`Источник: ${jena.type || 'TDB2'}`);
    if (jena.path) lines.push(`Dataset: ${jena.path}`);
  }
  if (connectionError) lines.push(`Статус: ${connectionError}`);
  if (pendingProfile) lines.push(`Выбран для подключения: ${pendingProfile.name}`);
  return lines.join('\n');
}

function markConnectionAvailable() {
  lastSuccessfulConnectionAt = Date.now();
  status.classList.remove('status--down', 'status--pending');
  status.classList.add('status--up');
  status.title = status.title.split('\n').filter(line => !line.startsWith('Статус:')).join('\n');
}

function searchResult(item) {
  const button = document.createElement('button');
  button.type = 'button';
  const type = item.types?.[0] || 'RDF Resource';
  button.innerHTML = `<strong>${escapeHtml(item.label)}</strong><span>${escapeHtml(type)}</span><small>${escapeHtml(item.compactUri)}</small>`;
  button.addEventListener('click', () => {
    results.hidden = true;
    searchInput.setAttribute('aria-expanded', 'false');
    openResource(item.uri);
  });
  return button;
}

function metamodelSearchResult(item, index) {
  const button = document.createElement('button');
  button.type = 'button';
  button.id = `metamodel-search-option-${index}`;
  button.setAttribute('role', 'option');
  button.setAttribute('aria-selected', String(index === metamodelAutocompleteIndex));
  button.classList.toggle('active', index === metamodelAutocompleteIndex);
  button.innerHTML = `<strong>${escapeHtml(item.name || item.id)}</strong><small>${escapeHtml(item.id || item.uri)}</small>`;
  button.addEventListener('mouseenter', () => setMetamodelAutocompleteIndex(index, false));
  button.addEventListener('click', () => selectMetamodelClass(item));
  return button;
}

async function updateMetamodelAutocomplete() {
  const request = ++autocompleteRequest;
  const query = searchInput.value.trim();
  if (!metamodelModeButton.classList.contains('active') || !query) {
    closeMetamodelAutocomplete();
    return;
  }
  await metamodelExplorer.show();
  if (request !== autocompleteRequest
    || !metamodelModeButton.classList.contains('active')
    || searchInput.value.trim() !== query) return;
  showMetamodelAutocomplete(metamodelExplorer.search(query));
}

function showMetamodelAutocomplete(items) {
  metamodelAutocompleteItems = items.slice(0, METAMODEL_SEARCH_RESULT_LIMIT);
  metamodelAutocompleteIndex = metamodelAutocompleteItems.length ? 0 : -1;
  if (!metamodelAutocompleteItems.length) {
    closeMetamodelAutocomplete();
    return;
  }
  results.classList.add('metamodel-autocomplete');
  results.replaceChildren(...metamodelAutocompleteItems.map(metamodelSearchResult));
  results.hidden = false;
  searchInput.setAttribute('aria-expanded', 'true');
  searchInput.setAttribute('aria-activedescendant', `metamodel-search-option-${metamodelAutocompleteIndex}`);
}

function setMetamodelAutocompleteIndex(index, scroll = true) {
  metamodelAutocompleteIndex = index;
  const buttons = Array.from(results.querySelectorAll('[role="option"]'));
  buttons.forEach((button, buttonIndex) => {
    const active = buttonIndex === index;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  });
  const active = buttons[index];
  if (!active) return;
  searchInput.setAttribute('aria-activedescendant', active.id);
  if (scroll) {
    if (active.offsetTop < results.scrollTop) results.scrollTop = active.offsetTop;
    const bottom = active.offsetTop + active.offsetHeight;
    if (bottom > results.scrollTop + results.clientHeight) results.scrollTop = bottom - results.clientHeight;
  }
}

function selectMetamodelClass(item) {
  searchInput.value = item.name || item.id;
  closeMetamodelAutocomplete();
  metamodelExplorer.focus(item);
}

function closeMetamodelAutocomplete(clear = true) {
  autocompleteRequest++;
  metamodelAutocompleteItems = [];
  metamodelAutocompleteIndex = -1;
  results.hidden = true;
  results.classList.remove('metamodel-autocomplete');
  searchInput.setAttribute('aria-expanded', 'false');
  searchInput.removeAttribute('aria-activedescendant');
  if (clear) results.replaceChildren();
}

async function openResource(resource) {
  try {
    if (graph?.hasNode(resource)) {
      graph.focus(resource);
      await showDetails({ uri: resource });
      return;
    }
    ensureGraph();
    await expandNode({ uri: resource });
  } catch (error) {
    showMessage(error.message, true);
  }
}

function ensureGraph() {
  if (graph) return;
  welcome.hidden = true;
  graphContainer.hidden = false;
  graph = new RdfGraph(graphContainer, showDetails, expandNode);
  updateGraphControls();
}

async function expandNode(node) {
  showMessage(`Загрузка связей: ${node.label || node.compactUri || node.uri}…`);
  try {
    const neighborhood = await api.neighbors(node.uri);
    markConnectionAvailable();
    const canonicalUri = neighborhood.stats.requestedUri;
    graph.merge(neighborhood, canonicalUri);
    graph.select(canonicalUri);
    updateGraphControls();
    await showDetails({ uri: canonicalUri });
    if (neighborhood.stats.truncated) {
      showMessage(`Показано не более ${neighborhood.stats.limit} из ${neighborhood.stats.incomingTotal + neighborhood.stats.outgoingTotal} связей`);
    } else {
      message.hidden = true;
    }
  } catch (error) {
    showMessage(error.message, true);
  }
}

async function showDetails(node) {
  const request = ++detailsRequest;
  graph?.select(node.uri);
  detailsPanel.hidden = false;
  detailsPanel.innerHTML = '<p class="details-loading">Загрузка свойств…</p>';
  try {
    const details = await api.details(node.uri);
    markConnectionAvailable();
    if (request !== detailsRequest) return;
    renderDetails(details);
  } catch (error) {
    if (request === detailsRequest) detailsPanel.innerHTML = `<p class="details-error">${escapeHtml(error.message)}</p>`;
  }
}

function renderDetails(details) {
  currentDetails = details;
  const classes = details.types.length
    ? details.types.map(type => `<span title="${escapeHtml(type)}">${escapeHtml(type)}</span>`).join('<i>·</i>')
    : '<span>Класс не указан</span>';
  const properties = details.properties.length
    ? details.properties.map(propertyRow).join('')
    : '<p class="details-empty">Свойства отсутствуют</p>';
  const collapseAction = graph?.canCollapse(details.uri)
    ? '<button class="branch-collapse" type="button">Свернуть ветку</button>'
    : '';
  const inspectorTabsAvailable = ownerRulesAvailable || redisRulesAvailable;
  const inspectorTabCount = 1 + Number(ownerRulesAvailable) + Number(redisRulesAvailable);
  const inspectorTabs = inspectorTabsAvailable ? `<nav class="details-tabs" style="--details-tab-count:${inspectorTabCount}" aria-label="Разделы инспектора">
      <button class="details-tab active" type="button" data-details-tab="properties" aria-selected="true">Свойства</button>
      ${ownerRulesAvailable ? '<button class="details-tab" type="button" data-details-tab="owner" aria-selected="false">Owner Rules</button>' : ''}
      ${redisRulesAvailable ? '<button class="details-tab" type="button" data-details-tab="redis" aria-selected="false">Redis Rules</button>' : ''}
    </nav>` : '';
  detailsPanel.innerHTML = `
    <button class="details-close" type="button" aria-label="Закрыть">×</button>
    <header class="details-heading">
      <h2>${escapeHtml(details.label)}</h2>
      <p class="details-class">${classes}</p>
    </header>
    ${inspectorTabs}
    <div class="details-tab-panel" data-details-panel="properties">
      <section><h3>URI</h3><code title="${escapeHtml(details.uri)}">${escapeHtml(details.compactUri)}</code></section>
      ${details.mrid ? `<section><h3>mRID</h3><code>${escapeHtml(details.mrid)}</code></section>` : ''}
      <section><h3>Свойства</h3><div class="property-list">${properties}</div>
        ${details.propertiesTruncated ? '<p class="limit-note">Список свойств ограничен настройкой backend.</p>' : ''}
      </section>
      <section><h3>Связи</h3><div class="relation-counts"><span>Входящие <b>${details.incomingCount}</b></span><span>Исходящие <b>${details.outgoingCount}</b></span></div>${collapseAction}</section>
      <p class="hint">Двойной клик по узлу раскрывает следующий уровень.</p>
    </div>
    ${ownerRulesAvailable ? '<div class="details-tab-panel owner-rules-panel" data-details-panel="owner" hidden></div>' : ''}
    ${redisRulesAvailable ? '<div class="details-tab-panel redis-rules-panel" data-details-panel="redis" hidden></div>' : ''}`;
  detailsPanel.querySelector('.details-close').addEventListener('click', () => {
    currentDetails = null;
    detailsPanel.hidden = true;
  });
  detailsPanel.querySelectorAll('[data-resource]').forEach(button => {
    button.addEventListener('click', () => openResource(button.dataset.resource));
  });
  detailsPanel.querySelector('.branch-collapse')?.addEventListener('click', () => {
    graph.collapse(details.uri);
    graph.select(details.uri);
    renderDetails(details);
    updateGraphControls();
  });
  detailsPanel.querySelectorAll('[data-details-tab]').forEach(tab => {
    tab.addEventListener('click', () => switchDetailsTab(details, tab.dataset.detailsTab));
  });
}

function switchDetailsTab(details, tabName) {
  detailsPanel.querySelectorAll('[data-details-tab]').forEach(tab => {
    const active = tab.dataset.detailsTab === tabName;
    tab.classList.toggle('active', active);
    tab.setAttribute('aria-selected', String(active));
  });
  detailsPanel.querySelectorAll('[data-details-panel]').forEach(panel => {
    panel.hidden = panel.dataset.detailsPanel !== tabName;
  });
  if (tabName === 'owner') loadOwnerRules(details);
  if (tabName === 'redis') loadRedisRules(details);
}

async function loadOwnerRules(details, refresh = false) {
  const panel = detailsPanel.querySelector('[data-details-panel="owner"]');
  if (!panel) return;
  const cached = ownerRulesCache.get(details.uri);
  if (cached && !refresh) {
    renderOwnerRules(panel, details, cached);
    return;
  }
  const started = performance.now();
  const renderLoading = () => {
    const elapsed = (performance.now() - started) / 1000;
    panel.innerHTML = `<div class="owner-loading"><span class="owner-spinner"></span><strong>Определяем владельца… ${elapsed.toFixed(1)} с</strong>${elapsed >= SLOW_OPERATION_SECONDS ? '<p>Определение владельца занимает больше времени…</p>' : ''}</div>`;
  };
  renderLoading();
  const timer = window.setInterval(renderLoading, OPERATION_STATUS_INTERVAL_MS);
  try {
    const response = await api.ownerRules(details.uri);
    ownerRulesCache.set(details.uri, response);
    if (currentDetails?.uri === details.uri && panel.isConnected) renderOwnerRules(panel, details, response);
  } catch (error) {
    if (currentDetails?.uri === details.uri && panel.isConnected) {
      panel.innerHTML = `<div class="owner-error"><strong>Не удалось определить владельца</strong><p>${escapeHtml(error.message)}</p><button type="button" data-owner-refresh>Повторить</button></div>`;
      bindOwnerActions(panel, details);
    }
  } finally {
    window.clearInterval(timer);
  }
}

function renderOwnerRules(panel, details, response) {
  const assetOwners = response.assetOwners ?? response.owners ?? [];
  const dataSources = response.dataSources ?? [];
  panel.innerHTML = `${renderOwnerRole('Asset Owner', assetOwners, response.assetOwnerPaths ?? response.paths ?? [], response.assetOwnerMatchedRules ?? response.matchedRules ?? [])}
    ${renderOwnerRole('Data Source', dataSources, response.dataSourcePaths ?? [], response.dataSourceMatchedRules ?? [])}
    <footer class="owner-footer"><span>Определено за ${response.executionTimeMs} мс</span><button type="button" data-owner-refresh>↻ Обновить</button></footer>`;
  bindOwnerActions(panel, details);
}

function renderOwnerRole(title, resources, paths, rules) {
  const cards = resources.length
    ? resources.map(owner => `<article class="owner-card">
        <button class="owner-name" type="button" data-owner-resource="${escapeHtml(owner.uri)}">${escapeHtml(owner.label)}</button>
        <p>${escapeHtml(owner.classes?.join(' · ') || 'RDF Resource')}</p>
        <code title="${escapeHtml(owner.uri)}">${escapeHtml(owner.compactUri)}</code>
        <button class="owner-open" type="button" data-owner-resource="${escapeHtml(owner.uri)}">Открыть в графе</button>
      </article>`).join('')
    : '<div class="owner-empty"><strong>Не найден</strong></div>';
  const renderedPaths = paths.length
    ? `<h4>Цепочка определения</h4>${paths.map((path, index) => `<div class="owner-path">
        ${paths.length > 1 ? `<strong>Результат ${index + 1}</strong>` : ''}
        ${path.steps.map((step, stepIndex) => `${stepIndex === 0 ? ownerPathResource(step.from) : ''}<div class="owner-path-edge"><span>${step.direction === 'incoming' ? '←' : '↓'}</span><code title="${escapeHtml(step.predicateUri)}">${escapeHtml(step.predicate)}</code></div>${ownerPathResource(step.to)}`).join('')}
      </div>`).join('')}`
    : '';
  const renderedRules = rules.length
    ? `<h4>Использованные правила</h4><div class="owner-rule-list">${rules.map(rule => `<details><summary>${escapeHtml(rule.name)}</summary><code>${escapeHtml(rule.conclusion)}</code>${rule.premises?.length ? `<ul>${rule.premises.map(premise => `<li>${escapeHtml(premise)}</li>`).join('')}</ul>` : ''}</details>`).join('')}</div>`
    : '';
  return `<section class="owner-section owner-role"><h3>${title}${resources.length > 1 ? ` · ${resources.length}` : ''}</h3>${cards}${renderedPaths}${renderedRules}</section>`;
}

function ownerPathResource(resource) {
  return `<button class="owner-path-resource" type="button" data-owner-resource="${escapeHtml(resource.uri)}"><strong>${escapeHtml(resource.label)}</strong><small>${escapeHtml(resource.classes?.[0] || resource.compactUri)}</small></button>`;
}

function bindOwnerActions(panel, details) {
  panel.querySelectorAll('[data-owner-resource]').forEach(button => {
    button.addEventListener('click', () => openResource(button.dataset.ownerResource));
  });
  panel.querySelector('[data-owner-refresh]')?.addEventListener('click', () => loadOwnerRules(details, true));
}

async function loadRedisRules(details, refresh = false) {
  const panel = detailsPanel.querySelector('[data-details-panel="redis"]');
  if (!panel) return;
  const cached = redisRulesCache.get(details.uri);
  if (cached && !refresh) {
    renderRedisRules(panel, details, cached);
    return;
  }
  const started = performance.now();
  const renderLoading = () => {
    const elapsed = (performance.now() - started) / 1000;
    panel.innerHTML = `<div class="owner-loading"><span class="owner-spinner"></span><strong>Читаем Redis… ${elapsed.toFixed(1)} с</strong>${elapsed >= SLOW_OPERATION_SECONDS ? '<p>Redis отвечает дольше обычного…</p>' : ''}</div>`;
  };
  renderLoading();
  const timer = window.setInterval(renderLoading, OPERATION_STATUS_INTERVAL_MS);
  let request = !refresh ? redisRulesRequests.get(details.uri) : null;
  if (!request) {
    request = api.redisRules(details.uri);
    redisRulesRequests.set(details.uri, request);
  }
  try {
    const response = await request;
    redisRulesCache.set(details.uri, response);
    if (currentDetails?.uri === details.uri && panel.isConnected) renderRedisRules(panel, details, response);
  } catch (error) {
    if (currentDetails?.uri === details.uri && panel.isConnected) {
      panel.innerHTML = `<div class="owner-error"><strong>Redis временно недоступен</strong><p>${escapeHtml(error.message)}</p><button type="button" data-redis-refresh>Повторить</button></div>`;
      bindRedisActions(panel, details);
    }
  } finally {
    window.clearInterval(timer);
    if (redisRulesRequests.get(details.uri) === request) redisRulesRequests.delete(details.uri);
  }
}

function renderRedisRules(panel, details, response) {
  const permissions = response.permissions || {};
  const sections = [
    ['READ', 'Организации с правом чтения объекта', permissions.read || []],
    ['READ TOP', 'Организации с верхнеуровневым правом чтения', permissions.readTop || []],
    ['WRITE', 'Организации с правом записи объекта', permissions.write || []]
  ].map(([title, description, resources]) => redisPermissionSection(title, description, resources)).join('');
  const rawKeys = response.rawKeys?.length
    ? response.rawKeys.map(key => `<div class="redis-key"><strong>${escapeHtml(key.role)}</strong><code>${escapeHtml(key.key)}</code><span>Тип: ${escapeHtml(key.type)} · значений: <b>${key.valueCount}</b></span></div>`).join('')
    : '<p class="details-empty">Ключи отсутствуют</p>';
  const empty = response.message
    ? `<div class="owner-empty redis-empty"><strong>Redis Rules не найдены</strong><p>${escapeHtml(response.message)}</p></div>`
    : '';
  panel.innerHTML = `<div class="redis-context"><span>Dataset <b>${response.datasetId}</b></span><span>Модель <b>${escapeHtml(response.modelType)}</b></span></div>${empty}${sections}<details class="redis-technical"><summary>Техническая информация</summary>${rawKeys}</details><footer class="owner-footer"><span>Получено из Redis за ${response.executionTimeMs} мс</span><button type="button" data-redis-refresh>↻ Обновить</button></footer>`;
  bindRedisActions(panel, details);
}

function redisPermissionSection(title, description, resources) {
  const content = resources.length
    ? `<div class="redis-resource-list">${resources.map(redisResource).join('')}</div>`
    : '<p class="details-empty">Нет записей</p>';
  return `<section class="owner-section redis-section"><h3>${title} <span>${resources.length}</span></h3><p class="redis-description">${description}</p>${content}</section>`;
}

function redisResource(resource) {
  return `<article class="redis-resource"><button type="button" data-redis-resource="${escapeHtml(resource.uri)}"><strong>${escapeHtml(resource.label)}</strong><span>${escapeHtml(resource.classes?.join(' · ') || 'RDF Resource')}</span><code title="${escapeHtml(resource.uri)}">${escapeHtml(resource.compactUri)}</code></button></article>`;
}

function bindRedisActions(panel, details) {
  panel.querySelectorAll('[data-redis-resource]').forEach(button => {
    button.addEventListener('click', () => openResource(button.dataset.redisResource));
  });
  panel.querySelector('[data-redis-refresh]')?.addEventListener('click', () => loadRedisRules(details, true));
}

function clearCurrentGraph() {
  if (!graph || graph.isEmpty()) return;
  graph.clear();
  detailsRequest++;
  currentDetails = null;
  detailsPanel.hidden = true;
  message.hidden = true;
  results.hidden = true;
  updateGraphControls();
}

function startNewGraph() {
  graph?.destroy();
  graph = undefined;
  detailsRequest++;
  currentDetails = null;
  detailsPanel.hidden = true;
  message.hidden = true;
  results.hidden = true;
  graphContainer.hidden = true;
  welcome.hidden = false;
  document.querySelector('#start-uri').value = '';
  updateGraphControls();
}

function updateGraphControls() {
  clearButton.disabled = !graph || graph.isEmpty();
}

function switchMode(mode) {
  const sparqlActive = mode === 'sparql';
  const redisActive = mode === 'redis';
  const metamodelActive = mode === 'metamodel';
  const graphActive = !sparqlActive && !redisActive && !metamodelActive;
  graphMode.hidden = !graphActive;
  metamodelMode.hidden = !metamodelActive;
  sparqlMode.hidden = !sparqlActive;
  redisMode.hidden = !redisActive;
  graphModeButton.classList.toggle('active', graphActive);
  sparqlModeButton.classList.toggle('active', sparqlActive);
  redisModeButton.classList.toggle('active', redisActive);
  metamodelModeButton.classList.toggle('active', metamodelActive);
  graphModeButton.setAttribute('aria-current', graphActive ? 'page' : 'false');
  sparqlModeButton.setAttribute('aria-current', sparqlActive ? 'page' : 'false');
  redisModeButton.setAttribute('aria-current', redisActive ? 'page' : 'false');
  metamodelModeButton.setAttribute('aria-current', metamodelActive ? 'page' : 'false');
  clearButton.hidden = metamodelActive;
  document.querySelector('#reset-button').hidden = metamodelActive;
  searchInput.placeholder = metamodelActive
    ? 'Поиск класса метамодели по имени или URI'
    : 'Поиск по имени, URI, UUID или классу';
  closeMetamodelAutocomplete();
  if (sparqlActive) sparqlConsole.focus();
  if (redisActive) redisConsole.focus();
  if (metamodelActive) metamodelExplorer.show();
}

function propertyRow(property) {
  const predicate = `<span title="${escapeHtml(property.predicateUri)}">${escapeHtml(property.predicate)}</span>`;
  if (property.valueType === 'RESOURCE') {
    return `<div class="property"><dt>${predicate}</dt><dd><button class="resource-link" data-resource="${escapeHtml(property.fullValue)}" title="${escapeHtml(property.fullValue)}">${escapeHtml(property.value)} <b>→</b></button></dd></div>`;
  }
  return `<div class="property"><dt>${predicate}</dt><dd title="${escapeHtml(property.datatype || '')}">${escapeHtml(property.value)}</dd></div>`;
}

function showMessage(text, error = false) {
  message.textContent = text;
  message.classList.toggle('error', error);
  message.hidden = false;
}

function escapeHtml(value) {
  const element = document.createElement('span');
  element.textContent = value || '';
  return element.innerHTML;
}

}

bootstrap();
