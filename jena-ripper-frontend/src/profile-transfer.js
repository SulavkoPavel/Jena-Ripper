import { api } from './api.js';

export function initProfileTransfer({ getSelectedProfileId, onImported, onError }) {
  const importFile = document.querySelector('#profile-import-file');
  const importDialog = document.querySelector('#profile-import-dialog');
  const importForm = document.querySelector('#profile-import-form');
  const applyButton = document.querySelector('#profile-import-apply');
  let importDocument = null;

  document.querySelector('#profile-import').addEventListener('click', () => importFile.click());
  importFile.addEventListener('change', previewImport);
  document.querySelector('#profile-export-selected').addEventListener('click', () => exportProfiles(false));
  document.querySelector('#profile-export-all').addEventListener('click', () => exportProfiles(true));
  document.querySelector('#profile-import-cancel').addEventListener('click', () => importDialog.close());
  importForm.addEventListener('submit', applyImport);

  async function exportProfiles(allProfiles) {
    try {
      const exported = allProfiles
        ? await api.exportConnectionProfiles()
        : await api.exportConnectionProfile(getSelectedProfileId());
      download(exported);
    } catch (error) {
      onError(error.message);
    }
  }

  function download(exported) {
    const link = document.createElement('a');
    link.href = URL.createObjectURL(exported.blob);
    link.download = exported.fileName;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  async function previewImport() {
    const file = importFile.files[0];
    if (!file) return;
    try {
      importDocument = JSON.parse(await file.text());
      const result = await api.importConnectionProfiles(importDocument);
      if (result.applied) {
        onImported(result);
      } else {
        renderImportPlan(result.items);
        importDialog.showModal();
      }
    } catch (error) {
      onError(error.message);
    } finally {
      importFile.value = '';
    }
  }

  function renderImportPlan(items) {
    const container = document.querySelector('#profile-import-items');
    container.replaceChildren(...items.map(importPlanRow));
  }

  function importPlanRow(item) {
    const row = document.createElement('div');
    row.className = 'profile-import-item';
    const description = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = item.name;
    const message = document.createElement('small');
    message.textContent = item.message;
    description.append(name, message);
    row.append(description);
    if (item.actions.length) {
      row.append(actionSelect(item));
    }
    return row;
  }

  function actionSelect(item) {
    const select = document.createElement('select');
    select.name = item.id;
    select.replaceChildren(...item.actions.map(action => {
      const option = document.createElement('option');
      option.value = action;
      option.textContent = importActionLabel(action);
      return option;
    }));
    return select;
  }

  async function applyImport(event) {
    event.preventDefault();
    const decisions = Object.fromEntries(new FormData(event.currentTarget).entries());
    applyButton.disabled = true;
    hideImportError();
    try {
      const result = await api.importConnectionProfiles(importDocument, decisions);
      if (!result.applied) {
        throw new Error('Выберите действие для каждого конфликта.');
      }
      importDialog.close();
      onImported(result);
    } catch (error) {
      const errorBox = document.querySelector('#profile-import-error');
      errorBox.textContent = error.message;
      errorBox.hidden = false;
    } finally {
      applyButton.disabled = false;
    }
  }

  function hideImportError() {
    const errorBox = document.querySelector('#profile-import-error');
    errorBox.textContent = '';
    errorBox.hidden = true;
  }
}

function importActionLabel(action) {
  return {
    UPDATE: 'Обновить',
    REPLACE: 'Заменить',
    REPLACE_NAME: 'Заменить существующий',
    COPY: 'Создать копию',
    SKIP: 'Пропустить'
  }[action] || action;
}
