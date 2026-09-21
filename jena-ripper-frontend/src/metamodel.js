import { api } from './api.js';
import { MetamodelGraph } from './metamodel-graph.js';

export function initMetamodelExplorer() {
  const container = document.querySelector('#metamodel-graph');
  const details = document.querySelector('#metamodel-details');
  const message = document.querySelector('#metamodel-message');
  let graph;
  let response;
  let loading;
  let classesById = new Map();
  let structuralClasses = [];
  let structuralAssociations = [];
  let auxiliaryAssociations = [];
  let selectedClassId;
  const attributesByClass = new Map();
  const attributeLoads = new Map();

  async function show() {
    if (response || loading) return loading;
    loading = load();
    try {
      await loading;
    } finally {
      loading = null;
    }
  }

  async function load() {
    message.textContent = 'Загрузка и построение метамодели…';
    message.classList.remove('error');
    message.hidden = false;
    details.hidden = true;
    try {
      response = await api.metamodelGraph();
      classesById = new Map(response.classes.map(item => [item.id, item]));
      structuralAssociations = response.associations.filter(item => item.show === true);
      auxiliaryAssociations = response.associations.filter(item => item.show !== true);

      const structuralClassIds = new Set();
      structuralAssociations.forEach(association => {
        if (!classesById.has(association.sourceClassId) || !classesById.has(association.targetClassId)) return;
        structuralClassIds.add(association.sourceClassId);
        structuralClassIds.add(association.targetClassId);
      });
      const nodes = response.classes
        .filter(item => structuralClassIds.has(item.id))
        .map(item => ({
          id: item.id,
          name: item.name,
          label: item.name || localName(item.id),
          uri: item.uri,
          metamodelClass: item
        }));
      structuralClasses = nodes.map(node => node.metamodelClass);
      const edges = groupStructuralAssociations(structuralAssociations, structuralClassIds);

      graph?.destroy();
      graph = new MetamodelGraph(container, showClass, showAssociation);
      await graph.load({ nodes, edges });
      message.hidden = true;
      requestAnimationFrame(() => graph?.fit(0));
    } catch (error) {
      response = undefined;
      structuralClasses = [];
      graph?.destroy();
      graph = undefined;
      message.textContent = error.message;
      message.classList.add('error');
      message.hidden = false;
    }
  }

  function search(query) {
    if (!response) return [];
    const normalized = query.trim().toLocaleLowerCase('ru');
    if (!normalized) return [];
    return structuralClasses
      .map(item => ({ item, rank: searchRank(item, normalized) }))
      .filter(match => Number.isFinite(match.rank))
      .sort((left, right) => left.rank - right.rank
        || className(left.item).localeCompare(className(right.item), 'en', { sensitivity: 'base' }))
      .map(match => match.item);
  }

  function focus(item) {
    const visible = graph?.focus(item.id);
    showClass({ metamodelClass: item });
    if (!visible) showTemporaryMessage('Класс не участвует в связях show=true и отсутствует на основной схеме.');
  }

  function showClass(node) {
    const item = node.metamodelClass;
    if (!item) return;
    selectedClassId = item.id;
    const titleRu = item.reference?.titleRu || item.labelRu;
    graph?.select(item.id);
    const outgoing = structuralAssociations.filter(edge => edge.sourceClassId === item.id);
    const incoming = structuralAssociations.filter(edge => edge.targetClassId === item.id);
    const auxiliary = auxiliaryAssociations.filter(edge =>
      edge.sourceClassId === item.id || edge.targetClassId === item.id);
    details.innerHTML = `<button class="details-close" type="button" aria-label="Закрыть">×</button>
      <header class="details-heading"><h2>${escapeHtml(item.name)}</h2>${titleRu ? `<p class="details-class">${escapeHtml(titleRu)}</p>` : ''}</header>
      ${referenceSection(item.reference)}
      <h3 class="metamodel-technical-heading">CIM</h3>
      <section><h3>URI</h3><code>${escapeHtml(item.uri)}</code><code>${escapeHtml(item.id)}</code></section>
      ${!titleRu ? textSection('Название', item.label) : ''}
      ${listSection('Родительские классы', item.parents)}
      ${listSection('Дочерние классы', item.children)}
      <section><h3>Параметры</h3><div class="property-list">
        ${property('show', booleanText(item.show))}${property('enumeration', booleanText(item.enumeration))}
        ${property('compound', booleanText(item.compound))}${property('isCimDataType', booleanText(item.cimDataType))}
        ${property('dictionary', booleanText(item.dictionary))}</div></section>
      <div class="metamodel-tabs" role="tablist" aria-label="Данные класса">
        <button type="button" role="tab" aria-selected="true" data-metamodel-tab="associations">Ассоциации <b>${incoming.length + outgoing.length}</b></button>
        <button type="button" role="tab" aria-selected="false" data-metamodel-tab="attributes">Атрибуты <b class="metamodel-attribute-count">${attributesByClass.has(item.id) ? attributesByClass.get(item.id).length : ''}</b></button>
      </div>
      <div class="metamodel-tab-panel" role="tabpanel" data-metamodel-panel="associations">
        ${associationsPanel(incoming, outgoing, auxiliary)}
      </div>
      <div class="metamodel-tab-panel" role="tabpanel" data-metamodel-panel="attributes" hidden></div>`;
    openDetails();
    details.querySelectorAll('[data-metamodel-tab]').forEach(button => {
      button.addEventListener('click', () => selectClassTab(item.id, button.dataset.metamodelTab));
    });
  }

  function associationsPanel(incoming, outgoing, auxiliary) {
    return `<section><h3>Структурные связи</h3>
      <div class="relation-counts"><span>Входящие <b>${incoming.length}</b></span><span>Исходящие <b>${outgoing.length}</b></span></div>
      ${associationList('Исходящие', outgoing, 'outgoing')}
      ${associationList('Входящие', incoming, 'incoming')}
    </section>
    <section><h3>Неструктурные связи</h3>
      <p class="metamodel-auxiliary-summary">${auxiliary.length} связанных ассоциаций с show=false/null. На схеме они не отображаются.</p>
      ${associationList('Связанные', auxiliary, 'related', 30)}
    </section>`;
  }

  async function selectClassTab(classId, tab) {
    if (selectedClassId !== classId) return;
    const scrollTop = details.scrollTop;
    const panels = Array.from(details.querySelectorAll('[data-metamodel-panel]'));
    const currentPanel = panels.find(panel => !panel.hidden);
    const nextPanel = panels.find(panel => panel.dataset.metamodelPanel === tab);
    if (currentPanel && nextPanel && currentPanel !== nextPanel) {
      const currentHeight = currentPanel.getBoundingClientRect().height;
      const nextMinHeight = Number.parseFloat(nextPanel.style.minHeight) || 0;
      nextPanel.style.minHeight = `${Math.ceil(Math.max(currentHeight, nextMinHeight))}px`;
    }
    details.querySelectorAll('[data-metamodel-tab]').forEach(button => {
      button.setAttribute('aria-selected', String(button.dataset.metamodelTab === tab));
    });
    if (nextPanel) nextPanel.hidden = false;
    panels.filter(panel => panel !== nextPanel).forEach(panel => {
      panel.hidden = true;
    });
    if (tab !== 'attributes') {
      restoreInspectorScroll(scrollTop);
      return;
    }

    const panel = details.querySelector('[data-metamodel-panel="attributes"]');
    if (attributesByClass.has(classId)) {
      panel.innerHTML = attributesPanel(attributesByClass.get(classId));
      restoreInspectorScroll(scrollTop);
      return;
    }
    panel.innerHTML = '<p class="details-loading">Загрузка атрибутов…</p>';
    restoreInspectorScroll(scrollTop);
    try {
      if (!attributeLoads.has(classId)) attributeLoads.set(classId, api.metamodelAttributes(classId));
      const attributes = await attributeLoads.get(classId);
      attributesByClass.set(classId, attributes);
      if (selectedClassId !== classId) return;
      const currentScrollTop = details.scrollTop;
      const count = details.querySelector('.metamodel-attribute-count');
      if (count) count.textContent = attributes.length;
      if (details.querySelector('[data-metamodel-tab="attributes"]')?.getAttribute('aria-selected') === 'true') {
        panel.innerHTML = attributesPanel(attributes);
      }
      restoreInspectorScroll(currentScrollTop);
    } catch (error) {
      if (selectedClassId === classId) {
        const currentScrollTop = details.scrollTop;
        panel.innerHTML = `<p class="details-error">${escapeHtml(error.message)}</p>`;
        restoreInspectorScroll(currentScrollTop);
      }
    } finally {
      attributeLoads.delete(classId);
    }
  }

  function restoreInspectorScroll(scrollTop) {
    window.requestAnimationFrame(() => {
      details.scrollTop = scrollTop;
      window.requestAnimationFrame(() => {
        details.scrollTop = scrollTop;
      });
    });
  }

  function showAssociation(edge) {
    const associations = edge.associations || [];
    const item = associations[0];
    if (!item) return;
    details.innerHTML = `<button class="details-close" type="button" aria-label="Закрыть">×</button>
      <header class="details-heading"><h2>${escapeHtml(edge.label)}</h2><p class="details-class">Структурная ассоциация${associations.length > 1 ? ` · ${associations.length} объединено` : ''}</p></header>
      <section><h3>Направление</h3><div class="association-direction"><code>${escapeHtml(edge.source)}</code><b>→</b><code>${escapeHtml(edge.target)}</code></div></section>
      ${associations.map(association => associationDetails(association)).join('')}`;
    openDetails();
  }

  function associationDetails(item) {
    return `<section class="metamodel-association-details"><h3>${escapeHtml(associationDisplayName(item))}</h3>
      ${textSection('Название', item.labelRu || item.label)}
      <div class="property-list">
        ${property('URI', item.uri)}${property('name', item.name)}
        ${property('source role', item.sourceRole)}${property('target role', item.targetRole)}
        ${property('range', item.range)}${property('ranges', item.ranges?.join(', '))}
        ${property('ranges need', item.rangesNeed?.map(value => `${value.range}: ${value.need}`).join(', '))}
        ${property('auto create', item.autoCreate?.join(', '))}
        ${property('data type', item.dataType)}${property('data type info', item.dataTypeInfo)}
        ${property('table columns', item.tableColumns)}${property('show', booleanText(item.show))}
      </div></section>`;
  }

  function associationList(title, associations, direction, limit = associations.length) {
    if (!associations.length) return `<div class="metamodel-relation-group"><strong>${escapeHtml(title)}</strong><p>Нет связей</p></div>`;
    const visible = associations.slice(0, limit);
    const rows = visible.map(association => {
      const source = classesById.get(association.sourceClassId)?.name || localName(association.sourceClassId);
      const target = classesById.get(association.targetClassId)?.name || localName(association.targetClassId);
      return `<li><span>${escapeHtml(source)} <b>→</b> ${escapeHtml(target)}</span><small>${escapeHtml(associationDisplayName(association))}</small></li>`;
    }).join('');
    const remainder = associations.length > limit
      ? `<p class="metamodel-list-limit">Показаны первые ${limit} из ${associations.length}</p>`
      : '';
    return `<div class="metamodel-relation-group" data-direction="${direction}"><strong>${escapeHtml(title)}</strong><ul>${rows}</ul>${remainder}</div>`;
  }

  function openDetails() {
    details.hidden = false;
    details.querySelector('.details-close').addEventListener('click', () => {
      details.hidden = true;
      selectedClassId = undefined;
      graph?.clearSelection();
    });
  }

  function showTemporaryMessage(text) {
    message.textContent = text;
    message.classList.remove('error');
    message.hidden = false;
    window.setTimeout(() => {
      if (message.textContent === text) message.hidden = true;
    }, 3500);
  }

  return { show, search, focus };
}

function searchRank(item, query) {
  const name = className(item).toLocaleLowerCase('ru');
  if (name === query) return 0;
  if (name.startsWith(query)) return 1;
  if (name.includes(query)) return 2;
  return [item.id, item.uri].some(value => value?.toLocaleLowerCase('ru').includes(query))
    ? 3
    : Number.POSITIVE_INFINITY;
}

function className(item) {
  return item.name || localName(item.id);
}

function attributesPanel(attributes) {
  if (!attributes.length) return '<p class="details-empty">У класса нет атрибутов</p>';
  return `<ul class="metamodel-attribute-list">${attributes.map(attribute => {
    const title = attributeName(attribute.name);
    const subtitle = attribute.labelRu || attribute.label;
    const type = attribute.dataType || attribute.type || attribute.range;
    return `<li><details><summary><span><strong>${escapeHtml(title)}</strong>${subtitle ? `<small>${escapeHtml(subtitle)}</small>` : ''}</span>${type ? `<code>${escapeHtml(type)}</code>` : ''}</summary>
      <div class="property-list">
        ${property('canonical name', attribute.name)}${property('URI', attribute.uri)}
        ${property('Название', attribute.label)}${property('Русское название', attribute.labelRu)}
        ${property('type', attribute.type)}${property('data type', attribute.dataType)}
        ${property('data type info', attribute.dataTypeInfo)}${property('range', attribute.range)}
        ${property('unit', attribute.unit)}${property('factor', attribute.factor)}
        ${property('show', booleanText(attribute.show))}
      </div></details></li>`;
  }).join('')}</ul>`;
}

function groupStructuralAssociations(associations, structuralClassIds) {
  const grouped = new Map();
  associations.forEach(association => {
    if (!structuralClassIds.has(association.sourceClassId)
      || !structuralClassIds.has(association.targetClassId)) return;
    const key = `${association.sourceClassId}\u0000${association.name}\u0000${association.targetClassId}`;
    if (!grouped.has(key)) {
      grouped.set(key, {
        id: `structural-${grouped.size}`,
        source: association.sourceClassId,
        target: association.targetClassId,
        associations: []
      });
    }
    grouped.get(key).associations.push(association);
  });
  return [...grouped.values()].map(edge => {
    const names = [...new Set(edge.associations.map(associationDisplayName).filter(Boolean))];
    return {
      ...edge,
      label: names.length > 1 ? `${names[0]} +${names.length - 1}` : names[0] || 'association'
    };
  });
}

function associationDisplayName(association) {
  return [association.labelRu, association.label, association.name]
    .find(value => value?.trim()) || 'association';
}

function referenceSection(reference) {
  if (!reference?.description) return '';
  return `<div class="metamodel-reference">${textSection('Описание', reference.description)}</div>`;
}

function property(name, value) {
  if (value === null || value === undefined || value === '') return '';
  return `<div class="property"><dt>${escapeHtml(name)}</dt><dd>${escapeHtml(String(value))}</dd></div>`;
}

function textSection(title, value) {
  return value ? `<section><h3>${escapeHtml(title)}</h3><p>${escapeHtml(value)}</p></section>` : '';
}

function listSection(title, values) {
  return values?.length
    ? `<section><h3>${escapeHtml(title)}</h3><div class="metamodel-values">${values.map(value => `<code>${escapeHtml(value)}</code>`).join('')}</div></section>`
    : '';
}

function booleanText(value) {
  return value === null || value === undefined ? null : value ? 'Да' : 'Нет';
}

function localName(value) {
  if (!value) return '';
  return value.slice(Math.max(value.lastIndexOf('#'), value.lastIndexOf('/'), value.lastIndexOf(':')) + 1);
}

function attributeName(value) {
  const name = localName(value);
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1) : name;
}

function escapeHtml(value) {
  const element = document.createElement('span');
  element.textContent = value || '';
  return element.innerHTML;
}
