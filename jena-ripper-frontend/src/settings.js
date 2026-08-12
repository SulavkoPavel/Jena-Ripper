import { api } from './api.js';

export function initConnectionSettings({ onSourceChanged = () => {}, onRestartRequired = async () => {} } = {}) {
  const dialog = document.querySelector('#connection-settings-dialog');
  const form = document.querySelector('#connection-settings-form');
  const openButton = document.querySelector('#settings-button');
  const profileSelect = document.querySelector('#connection-profile');
  const activeBadge = document.querySelector('#active-profile-badge');
  const useProfileButton = document.querySelector('#profile-use');
  const saveButton = document.querySelector('#settings-save');
  const dirtyLabel = document.querySelector('#settings-dirty');
  const loadError = document.querySelector('#settings-load-error');
  const saveMessage = document.querySelector('#settings-save-message');
  const profileDialog = document.querySelector('#profile-dialog');
  const profileForm = document.querySelector('#profile-form');
  let collection = null;
  let selectedId = null;
  let baseline = '';
  let profileDialogMode = 'create';
  let runtimeProfileId = null;

  openButton.addEventListener('click', open);
  document.querySelector('#settings-close').addEventListener('click', requestClose);
  document.querySelector('#settings-cancel').addEventListener('click', requestClose);
  dialog.addEventListener('cancel', event => { event.preventDefault(); requestClose(); });
  dialog.addEventListener('close', clearSecrets);
  form.addEventListener('submit', save);
  form.addEventListener('input', onFormInput);
  form.elements.jenaSourceType.addEventListener('change', updateSourceMode);
  document.querySelector('#cim-load-models').addEventListener('click', loadCimModels);
  profileSelect.addEventListener('change', selectProfile);
  useProfileButton.addEventListener('click', () => form.requestSubmit());
  document.querySelector('#profile-create').addEventListener('click', () => openProfileDialog('create'));
  document.querySelector('#profile-rename').addEventListener('click', () => openProfileDialog('rename'));
  document.querySelector('#profile-delete').addEventListener('click', deleteProfile);
  document.querySelector('#profile-dialog-cancel').addEventListener('click', () => profileDialog.close());
  profileForm.addEventListener('submit', submitProfileDialog);
  form.querySelectorAll('[data-test-connection]').forEach(button => {
    button.addEventListener('click', () => testConnection(button.dataset.testConnection, button));
  });

  async function open() {
    resetMessages();
    openButton.disabled = true;
    try {
      collection = await api.connectionProfiles();
      selectedId = runtimeProfileId || collection.activeProfileId;
      renderProfiles();
      fill(selectedProfile());
      dialog.showModal();
    } catch (error) {
      loadError.textContent = error.message;
      loadError.hidden = false;
      dialog.showModal();
    } finally {
      openButton.disabled = false;
    }
  }

  function requestClose() {
    if (isDirty() && !window.confirm('Есть несохранённые изменения. Закрыть без сохранения?')) return;
    dialog.close();
  }

  function selectedProfile() {
    return collection?.profiles.find(profile => profile.id === selectedId);
  }

  function renderProfiles() {
    profileSelect.replaceChildren(...collection.profiles.map(profile => {
      const option = document.createElement('option');
      option.value = profile.id;
      const runtime = profile.id === runtimeProfileId;
      const pending = profile.active && runtimeProfileId && !runtime;
      option.textContent = `${profile.name}${runtime ? '  ● сейчас' : pending ? '  ◷ выбран' : ''}`;
      option.selected = profile.id === selectedId;
      return option;
    }));
    const profile = selectedProfile();
    const runtime = profile?.id === runtimeProfileId;
    const pending = profile?.active && runtimeProfileId && !runtime;
    activeBadge.hidden = !runtime && !pending;
    activeBadge.classList.toggle('active-profile-badge--pending', Boolean(pending));
    activeBadge.textContent = runtime ? '● Используется сейчас' : '◷ Выбран';
    useProfileButton.disabled = profile?.active === true;
    useProfileButton.textContent = runtime
      ? profile?.active ? 'Используется' : 'Применить'
      : pending ? 'Выбран' : 'Использовать';
    const deleteButton = document.querySelector('#profile-delete');
    const onlyProfile = collection.profiles.length <= 1;
    deleteButton.disabled = onlyProfile || profile?.active || runtime;
    deleteButton.title = onlyProfile
      ? 'Нельзя удалить единственный профиль'
      : runtime ? 'Нельзя удалить профиль, используемый сейчас'
        : profile?.active ? 'Сначала выберите другой профиль' : 'Удалить профиль';
  }

  function selectProfile(event) {
    const nextId = event.target.value;
    if (isDirty() && !window.confirm('Есть несохранённые изменения. Переключить профиль без сохранения?')) {
      profileSelect.value = selectedId;
      return;
    }
    selectedId = nextId;
    resetMessages();
    renderProfiles();
    fill(selectedProfile());
  }

  function fill(profile) {
    if (!profile) return;
    form.elements.jenaSourceType.value = profile.jena.sourceType || 'LOCAL_TDB2';
    form.elements.jenaType.value = profile.jena.type;
    form.elements.jenaPath.value = profile.jena.path;
    const cim = profile.jena.cimApi || {};
    form.elements.cimBaseUrl.value = cim.baseUrl || '';
    form.elements.cimAuthBaseUrl.value = cim.authBaseUrl && cim.authBaseUrl !== cim.baseUrl ? cim.authBaseUrl : '';
    form.elements.cimUsername.value = cim.username || '';
    form.elements.cimConnectTimeoutMs.value = cim.connectTimeoutMs || 5000;
    form.elements.cimReadTimeoutMs.value = cim.readTimeoutMs || 30000;
    form.elements.cimTrustUntrustedCertificates.checked = cim.trustUntrustedCertificates !== false;
    setModelOptions(cim.modelId, cim.modelName);
    form.elements.postgresHost.value = profile.postgres.host;
    form.elements.postgresPort.value = profile.postgres.port;
    form.elements.postgresDatabase.value = profile.postgres.database;
    form.elements.postgresSchema.value = profile.postgres.schema;
    form.elements.postgresUsername.value = profile.postgres.username;
    form.elements.redisHost.value = profile.redis.host;
    form.elements.redisPort.value = profile.redis.port;
    form.elements.redisDatabase.value = profile.redis.database;
    form.elements.redisUsername.value = profile.redis.username || '';
    form.elements.redisTimeoutMs.value = profile.redis.timeoutMs;
    passwordField('postgresPassword', profile.postgres.passwordConfigured);
    passwordField('redisPassword', profile.redis.passwordConfigured);
    passwordField('cimPassword', cim.passwordConfigured === true);
    updateSourceMode();
    resetAllStates();
    baseline = comparable(allPayload());
    updateDirty();
  }

  function updateSourceMode() {
    const remote = form.elements.jenaSourceType.value === 'CIM_API';
    form.querySelectorAll('[data-source-fields="local"]').forEach(element => { element.hidden = remote; });
    form.querySelectorAll('[data-source-fields="cim"]').forEach(element => { element.hidden = !remote; });
    const postgresSection = form.querySelector('[data-settings-section="postgres"]');
    postgresSection.hidden = remote;
    postgresSection.querySelectorAll('input, select, button').forEach(element => { element.disabled = remote; });
    form.querySelector('[data-settings-section="redis"]').hidden = false;
    document.querySelector('#cim-managed-connections').hidden = !remote;
    document.querySelector('#jena-source-description').textContent = remote
      ? 'Удалённый Dataset через бизнес-API CIM App' : 'Локальный Apache Jena TDB2';
    const testButton = form.querySelector('[data-test-connection="jena"]');
    testButton.textContent = remote ? 'Войти и проверить' : 'Проверить';
    updateDirty();
  }

  function setModelOptions(modelId, modelName, models = []) {
    const select = form.elements.cimModelId;
    const values = [...models];
    if (modelId != null && !values.some(model => String(model.id) === String(modelId))) {
      values.unshift({ id: modelId, name: modelName || `Модель ${modelId}` });
    }
    select.replaceChildren(...values.map(model => {
      const option = document.createElement('option');
      option.value = model.id;
      option.textContent = `${model.name || 'Без названия'} (#${model.id})`;
      option.selected = String(model.id) === String(modelId);
      return option;
    }));
    if (!values.length) {
      const option = document.createElement('option'); option.value = ''; option.textContent = 'Сначала загрузите модели';
      select.append(option);
    }
  }

  async function loadCimModels() {
    const button = document.querySelector('#cim-load-models');
    const oldText = button.textContent;
    button.disabled = true; button.textContent = 'Загружаем…';
    setSectionState('jena', null, 'Авторизация…');
    try {
      const currentId = form.elements.cimModelId.value;
      const models = await api.cimModels(sectionPayload('jena'), selectedId);
      setModelOptions(currentId || models[0]?.id, null, models);
      setSectionState('jena', true, `● Доступно моделей: ${models.length}`);
      updateDirty();
    } catch (error) {
      setSectionState('jena', false, error.message);
    } finally {
      button.disabled = false; button.textContent = oldText;
    }
  }

  function passwordField(name, configured) {
    const field = form.elements[name];
    field.value = '';
    field.placeholder = configured ? 'Пароль сохранён' : 'Пароль не задан';
    field.closest('label').querySelector('.password-note').textContent = configured
      ? 'Оставьте пустым, чтобы сохранить текущий пароль.' : '';
  }

  function onFormInput(event) {
    const section = event.target.closest('[data-settings-section]');
    if (section) setSectionState(section.dataset.settingsSection, null, 'Не проверено');
    saveMessage.hidden = true;
    updateDirty();
  }

  function isDirty() {
    return baseline !== '' && comparable(allPayload()) !== baseline;
  }

  function updateDirty() {
    const dirty = isDirty();
    const activating = selectedProfile()?.active === false;
    saveButton.disabled = !dirty;
    saveButton.textContent = dirty && activating ? 'Сохранить и использовать' : 'Сохранить';
    dirtyLabel.hidden = !dirty;
  }

  async function testConnection(type, button) {
    setSectionState(type, null, 'Проверяем…');
    if (!validateSection(type)) {
      setSectionState(type, null, 'Не проверено');
      return;
    }
    const originalText = button.textContent;
    button.disabled = true;
    button.textContent = 'Проверяем…';
    try {
      const payload = sectionPayload(type);
      let response;
      if (type === 'jena') response = await api.testJenaConnection(payload, selectedId);
      else if (type === 'postgres') await api.testPostgresConnection(payload, selectedId);
      else await api.testRedisConnection(payload, selectedId);
      setSectionState(type, true, response?.message ? `● ${response.message}` : '● Доступен');
    } catch (error) {
      setSectionState(type, false, error.message);
    } finally {
      button.disabled = false;
      button.textContent = originalText;
    }
  }

  async function save(event) {
    event.preventDefault();
    resetMessages();
    const profile = selectedProfile();
    const activating = profile?.active === false;
    if ((!isDirty() && !activating) || !form.reportValidity()) return;
    const payload = allPayload();
    const datasetChanged = profile && (profile.jena.sourceType !== payload.jena.sourceType
      || profile.jena.type !== payload.jena.type || profile.jena.path !== payload.jena.path
      || profile.jena.cimApi?.modelId !== payload.jena.cimApi?.modelId);
    if ((datasetChanged || activating) && !window.confirm('Смена активного подключения очистит текущий граф и автоматически переподключит Jena Ripper. Продолжить?')) return;
    saveButton.disabled = true;
    saveButton.textContent = 'Сохраняем…';
    try {
      const response = await api.saveConnectionProfile(selectedId, payload);
      collection = await api.connectionProfiles();
      renderProfiles();
      fill(selectedProfile());
      saveMessage.className = 'settings-message settings-message--success';
      saveMessage.textContent = response.message;
      saveMessage.hidden = false;
      if (response.restartRequired && (datasetChanged || activating)) {
        saveMessage.textContent = 'Применяем профиль подключения…';
        await onRestartRequired(selectedId);
      } else if (datasetChanged || activating) {
        onSourceChanged();
      }
    } catch (error) {
      saveMessage.className = 'settings-message settings-message--error';
      saveMessage.textContent = error.message;
      saveMessage.hidden = false;
      updateDirty();
    } finally {
      updateDirty();
    }
  }

  function openProfileDialog(mode) {
    profileDialogMode = mode;
    const rename = mode === 'rename';
    document.querySelector('#profile-dialog-title').textContent = rename ? 'Переименовать профиль' : 'Новый профиль';
    document.querySelector('#profile-dialog-submit').textContent = rename ? 'Сохранить' : 'Создать';
    document.querySelector('#profile-copy-label').hidden = rename;
    profileForm.elements.profileName.value = rename ? selectedProfile().name : '';
    profileForm.elements.copyCurrent.checked = false;
    document.querySelector('#profile-dialog-error').hidden = true;
    profileDialog.showModal();
    profileForm.elements.profileName.focus();
  }

  async function submitProfileDialog(event) {
    event.preventDefault();
    const name = profileForm.elements.profileName.value.trim();
    const errorBox = document.querySelector('#profile-dialog-error');
    try {
      if (profileDialogMode === 'rename') {
        collection = await api.renameConnectionProfile(selectedId, name);
      } else {
        collection = await api.createConnectionProfile({
          name,
          copyCurrent: profileForm.elements.copyCurrent.checked,
          sourceProfileId: selectedId
        });
        selectedId = collection.profiles[collection.profiles.length - 1].id;
      }
      profileDialog.close();
      renderProfiles();
      fill(selectedProfile());
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.hidden = false;
    }
  }

  async function deleteProfile() {
    const profile = selectedProfile();
    if (!window.confirm(`Удалить профиль «${profile.name}»?`)) return;
    try {
      collection = await api.deleteConnectionProfile(profile.id);
      selectedId = collection.activeProfileId;
      renderProfiles();
      fill(selectedProfile());
    } catch (error) {
      saveMessage.className = 'settings-message settings-message--error';
      saveMessage.textContent = error.message;
      saveMessage.hidden = false;
    }
  }

  function validateSection(type) {
    const invalid = sectionFields(type).find(field => !field.checkValidity());
    if (invalid) { invalid.reportValidity(); return false; }
    return true;
  }

  function sectionFields(type) {
    return [...form.querySelector(`[data-settings-section="${type}"]`).querySelectorAll('input, select')];
  }

  function sectionPayload(type) {
    if (type === 'jena') {
      const selectedModel = form.elements.cimModelId.selectedOptions[0];
      return {
        sourceType: form.elements.jenaSourceType.value,
        type: form.elements.jenaType.value,
        path: form.elements.jenaPath.value.trim(),
        cimApi: {
          baseUrl: form.elements.cimBaseUrl.value.trim().replace(/\/$/, ''),
          authBaseUrl: form.elements.cimAuthBaseUrl.value.trim().replace(/\/$/, ''),
          username: form.elements.cimUsername.value.trim(), password: form.elements.cimPassword.value,
          modelId: form.elements.cimModelId.value ? Number(form.elements.cimModelId.value) : null,
          modelName: selectedModel?.value ? selectedModel.textContent.replace(/ \(#\d+\)$/, '') : '',
          connectTimeoutMs: Number(form.elements.cimConnectTimeoutMs.value),
          readTimeoutMs: Number(form.elements.cimReadTimeoutMs.value),
          trustUntrustedCertificates: form.elements.cimTrustUntrustedCertificates.checked
        }
      };
    }
    if (type === 'postgres') return {
      host: form.elements.postgresHost.value.trim(), port: Number(form.elements.postgresPort.value),
      database: form.elements.postgresDatabase.value.trim(), schema: form.elements.postgresSchema.value.trim(),
      username: form.elements.postgresUsername.value.trim(), password: form.elements.postgresPassword.value
    };
    return {
      host: form.elements.redisHost.value.trim(), port: Number(form.elements.redisPort.value),
      database: Number(form.elements.redisDatabase.value), username: form.elements.redisUsername.value.trim(),
      password: form.elements.redisPassword.value, timeoutMs: Number(form.elements.redisTimeoutMs.value)
    };
  }

  function allPayload() {
    return { jena: sectionPayload('jena'), postgres: sectionPayload('postgres'), redis: sectionPayload('redis') };
  }

  function comparable(value) { return JSON.stringify(value); }
  function resetMessages() { loadError.hidden = true; saveMessage.hidden = true; }
  function resetAllStates() { ['jena', 'postgres', 'redis'].forEach(type => setSectionState(type, null, 'Не проверено')); }
  function setSectionState(type, success, text) {
    const state = form.querySelector(`[data-settings-section="${type}"] .connection-state`);
    state.className = `connection-state${success === true ? ' connection-state--success' : success === false ? ' connection-state--error' : ''}`;
    state.textContent = text;
  }
  function clearSecrets() { form.elements.postgresPassword.value = ''; form.elements.redisPassword.value = ''; form.elements.cimPassword.value = ''; }

  return {
    setRuntimeProfileId(profileId) {
      runtimeProfileId = profileId || null;
      if (collection) renderProfiles();
    },
    setCapabilities(features) {
      openButton.dataset.ownerRules = String(features.ownerRules === true);
      openButton.dataset.redisRules = String(features.redisRules === true);
    }
  };
}
