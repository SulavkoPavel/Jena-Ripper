import { api } from './api.js';
import { initProfileTransfer } from './profile-transfer.js';
import { RDF_SOURCE_TYPE } from './source-types.js';

const DEFAULT_CIM_CONNECT_TIMEOUT_MS = 5_000;
const DEFAULT_CIM_READ_TIMEOUT_MS = 30_000;
const BYTES_PER_UNIT = 1_024;

export function initConnectionSettings({ onSourceChanged = () => {}, onRestartRequired = async () => {} } = {}) {
  const dialog = document.querySelector('#connection-settings-dialog');
  const form = document.querySelector('#connection-settings-form');
  const openButton = document.querySelector('#settings-button');
  const headerProfileSelect = document.querySelector('#header-profile-select');
  const profileSelect = document.querySelector('#connection-profile');
  const activeBadge = document.querySelector('#active-profile-badge');
  const activateProfileButton = document.querySelector('#profile-use');
  const saveButton = document.querySelector('#settings-save');
  const dirtyLabel = document.querySelector('#settings-dirty');
  const loadError = document.querySelector('#settings-load-error');
  const saveMessage = document.querySelector('#settings-save-message');
  const profileDialog = document.querySelector('#profile-dialog');
  const profileForm = document.querySelector('#profile-form');
  const modelRenameDialog = document.querySelector('#uploaded-model-rename-dialog');
  const modelRenameForm = document.querySelector('#uploaded-model-rename-form');
  const uploadedModelSelect = form.elements.uploadedModelId;
  const uploadedModelFile = document.querySelector('#uploaded-model-file');
  let uploadedModels = [];
  let collection = null;
  let selectedId = null;
  let baseline = '';
  let profileDialogMode = 'create';
  let runtimeProfileId = null;
  let uploadedModelUsePending = false;

  openButton.addEventListener('click', open);
  headerProfileSelect.addEventListener('change', activateFromHeader);
  document.querySelector('#settings-close').addEventListener('click', requestClose);
  document.querySelector('#settings-cancel').addEventListener('click', requestClose);
  dialog.addEventListener('cancel', event => { event.preventDefault(); requestClose(); });
  dialog.addEventListener('close', clearSecrets);
  form.addEventListener('submit', save);
  form.addEventListener('input', onFormInput);
  form.elements.jenaSourceType.addEventListener('change', updateSourceMode);
  document.querySelector('#cim-load-models').addEventListener('click', loadCimModels);
  document.querySelector('#uploaded-model-upload').addEventListener('click', () => uploadedModelFile.click());
  uploadedModelFile.addEventListener('change', uploadModel);
  uploadedModelSelect.addEventListener('change', () => { clearUploadedModelDeleteError(); renderUploadedModelInfo(); updateDirty(); });
  document.querySelector('#uploaded-model-rename').addEventListener('click', openUploadedModelRenameDialog);
  document.querySelector('#uploaded-model-delete').addEventListener('click', deleteUploadedModel);
  document.querySelector('#uploaded-model-rename-cancel').addEventListener('click', () => modelRenameDialog.close());
  modelRenameForm.addEventListener('submit', renameUploadedModel);
  profileSelect.addEventListener('change', selectProfile);
  activateProfileButton.addEventListener('click', activateSelected);
  document.querySelector('#profile-create').addEventListener('click', () => openProfileDialog('create'));
  document.querySelector('#profile-rename').addEventListener('click', () => openProfileDialog('rename'));
  document.querySelector('#profile-delete').addEventListener('click', deleteProfile);
  document.querySelector('#profile-test-all').addEventListener('click', testAllConnections);
  document.querySelector('#profile-dialog-cancel').addEventListener('click', () => profileDialog.close());
  profileForm.addEventListener('submit', submitProfileDialog);
  form.querySelectorAll('[data-test-connection]').forEach(button => {
    button.addEventListener('click', () => testConnection(button.dataset.testConnection, button));
  });
  form.querySelectorAll('[data-toggle-password]').forEach(button => {
    button.addEventListener('click', () => togglePassword(button));
  });
  initProfileTransfer({
    getSelectedProfileId: () => selectedId,
    onImported: finishImport,
    onError: showSaveError
  });

  async function open() {
    resetMessages();
    openButton.disabled = true;
    try {
      [collection, uploadedModels] = await Promise.all([api.connectionProfiles(), api.uploadedModels()]);
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
      option.textContent = profile.name;
      option.selected = profile.id === selectedId;
      return option;
    }));
    const profile = selectedProfile();
    const runtime = profile?.id === runtimeProfileId;
    const active = profile?.active === true;
    activeBadge.hidden = false;
    activeBadge.classList.toggle('active-profile-badge--inactive', !active);
    activeBadge.textContent = active ? '● Используется сейчас' : 'Не используется';
    activateProfileButton.hidden = active;
    activateProfileButton.disabled = active;
    activateProfileButton.textContent = 'Сделать активным';
    const deleteButton = document.querySelector('#profile-delete');
    const onlyProfile = collection.profiles.length <= 1;
    deleteButton.disabled = onlyProfile || profile?.active || runtime;
    deleteButton.title = onlyProfile
      ? 'Нельзя удалить единственный профиль'
      : runtime ? 'Нельзя удалить профиль, используемый сейчас'
        : profile?.active ? 'Сначала выберите другой профиль' : 'Удалить профиль';
    renderHeaderProfiles();
  }

  function renderHeaderProfiles() {
    if (!collection) return;
    headerProfileSelect.replaceChildren(...collection.profiles.map(profile => {
      const option = document.createElement('option');
      option.value = profile.id;
      option.textContent = profile.name;
      option.selected = profile.active;
      return option;
    }));
    headerProfileSelect.disabled = collection.profiles.length < 2;
  }

  async function activateFromHeader(event) {
    const nextId = event.target.value;
    const previousId = collection?.activeProfileId;
    if (!nextId || nextId === previousId) return;
    if (!window.confirm('Сменить активный профиль и переподключить Jena Ripper?')) {
      headerProfileSelect.value = previousId;
      return;
    }
    headerProfileSelect.disabled = true;
    try {
      collection = await api.activateConnectionProfile(nextId);
      selectedId = nextId;
      renderProfiles();
      await onRestartRequired(nextId);
    } catch (error) {
      headerProfileSelect.value = previousId;
      window.alert(error.message);
    } finally {
      headerProfileSelect.disabled = false;
    }
  }

  async function activateSelected() {
    if (isDirty()) {
      showSaveError('Сначала сохраните изменения профиля, затем сделайте его активным.');
      return;
    }
    if (!selectedId || selectedProfile()?.active) return;
    if (!window.confirm('Сменить активный профиль и переподключить Jena Ripper?')) return;
    activateProfileButton.disabled = true;
    try {
      collection = await api.activateConnectionProfile(selectedId);
      renderProfiles();
      await onRestartRequired(selectedId);
    } catch (error) {
      showSaveError(error.message);
      renderProfiles();
    }
  }

  function finishImport(result) {
    collection = result.profiles;
    selectedId = collection.activeProfileId;
    renderProfiles();
    if (dialog.open) fill(selectedProfile());
    saveMessage.className = 'settings-message settings-message--success';
    saveMessage.textContent = `Импорт завершён: создано ${result.created}, обновлено ${result.updated}, пропущено ${result.skipped}.`;
    saveMessage.hidden = false;
  }

  function showSaveError(message) {
    saveMessage.className = 'settings-message settings-message--error';
    saveMessage.textContent = message;
    saveMessage.hidden = false;
  }

  function togglePassword(button) {
    const input = form.elements[button.dataset.togglePassword];
    const visible = input.type === 'text';
    input.type = visible ? 'password' : 'text';
    button.textContent = visible ? '◉' : '⊘';
    button.setAttribute('aria-label', visible ? 'Показать пароль' : 'Скрыть пароль');
  }

  async function testAllConnections() {
    const button = document.querySelector('#profile-test-all');
    if (!form.reportValidity()) return;
    button.disabled = true;
    button.textContent = 'Проверяем…';
    const payload = allPayload();
    const checks = [
      ['jena', () => api.testJenaConnection(payload.jena, selectedId)],
      ['redis', () => api.testRedisConnection(payload.redis, selectedId)]
    ];
    if (payload.jena.sourceType !== RDF_SOURCE_TYPE.CIM_API) {
      checks.splice(1, 0, ['postgres', () => api.testPostgresConnection(payload.postgres, selectedId)]);
    }
    await Promise.all(checks.map(async ([type, check]) => {
      setSectionState(type, null, 'Проверяем…');
      try {
        const response = await check();
        setSectionState(type, true, response?.message ? `● ${response.message}` : '● Доступен');
      } catch (error) {
        setSectionState(type, false, `✕ ${error.message}`);
      }
    }));
    button.disabled = false;
    button.textContent = 'Проверить подключения';
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
    clearUploadedModelDeleteError();
    form.elements.jenaSourceType.value = profile.jena.sourceType || RDF_SOURCE_TYPE.LOCAL_TDB2;
    form.elements.jenaType.value = profile.jena.type;
    form.elements.jenaPath.value = profile.jena.path;
    const cim = profile.jena.cimApi || {};
    form.elements.cimBaseUrl.value = cim.baseUrl || '';
    form.elements.cimAuthBaseUrl.value = cim.authBaseUrl && cim.authBaseUrl !== cim.baseUrl ? cim.authBaseUrl : '';
    form.elements.cimUsername.value = cim.username || '';
    form.elements.cimConnectTimeoutMs.value = cim.connectTimeoutMs || DEFAULT_CIM_CONNECT_TIMEOUT_MS;
    form.elements.cimReadTimeoutMs.value = cim.readTimeoutMs || DEFAULT_CIM_READ_TIMEOUT_MS;
    form.elements.cimTrustUntrustedCertificates.checked = cim.trustUntrustedCertificates !== false;
    setModelOptions(cim.modelId, cim.modelName);
    setUploadedModelOptions(profile.jena.uploadedModelId);
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
    const sourceType = form.elements.jenaSourceType.value;
    const remote = sourceType === RDF_SOURCE_TYPE.CIM_API;
    const file = sourceType === RDF_SOURCE_TYPE.FILE;
    form.querySelectorAll('[data-source-fields="local"]').forEach(element => { element.hidden = remote || file; });
    form.querySelectorAll('[data-source-fields="cim"]').forEach(element => { element.hidden = !remote; });
    form.querySelectorAll('[data-source-fields="file"]').forEach(element => { element.hidden = !file; });
    const postgresSection = form.querySelector('[data-settings-section="postgres"]');
    postgresSection.hidden = remote;
    postgresSection.querySelectorAll('input, select, button').forEach(element => { element.disabled = remote; });
    form.querySelector('[data-settings-section="redis"]').hidden = false;
    document.querySelector('#cim-managed-connections').hidden = !remote;
    document.querySelector('#jena-source-description').textContent = remote
      ? 'Удалённый Dataset через бизнес-API CIM App'
      : file ? 'Загруженная CIM RDF/XML FULL-модель' : 'Локальный Apache Jena TDB2';
    const testButton = form.querySelector('[data-test-connection="jena"]');
    testButton.textContent = remote ? 'Войти и проверить' : 'Проверить';
    document.querySelector('#jena-test-row').hidden = file;
    clearUploadedModelDeleteError();
    updateDirty();
  }

  async function loadUploadedModels(preferredId = uploadedModelSelect.value) {
    try {
      uploadedModels = await api.uploadedModels();
      setUploadedModelOptions(preferredId);
    } catch (error) {
      setSectionState('jena', false, error.message);
    }
  }

  function setUploadedModelOptions(preferredId) {
    clearUploadedModelDeleteError();
    const current = preferredId || uploadedModelSelect.value;
    uploadedModelSelect.replaceChildren(...uploadedModels.map(model => {
      const option = document.createElement('option');
      option.value = model.id;
      option.textContent = modelDisplayName(model);
      option.selected = model.id === current;
      return option;
    }));
    if (!uploadedModels.length) {
      const option = document.createElement('option');
      option.value = '';
      option.textContent = 'Нет загруженных моделей';
      uploadedModelSelect.append(option);
    } else if (!uploadedModels.some(model => model.id === current)) {
      uploadedModelSelect.value = uploadedModels[0].id;
    }
    renderUploadedModelInfo();
  }

  function renderUploadedModelInfo() {
    const model = uploadedModels.find(item => item.id === uploadedModelSelect.value);
    const info = document.querySelector('#uploaded-model-info');
    const name = document.querySelector('#uploaded-model-name');
    const summary = document.querySelector('#uploaded-model-summary');
    const date = document.querySelector('#uploaded-model-date');
    const status = document.querySelector('#uploaded-model-status');
    const useButton = document.querySelector('#uploaded-model-use');
    const renameButton = document.querySelector('#uploaded-model-rename');
    const deleteButton = document.querySelector('#uploaded-model-delete');
    info.hidden = false;
    if (!model) {
      name.textContent = 'Нет загруженных моделей';
      name.title = '';
      summary.textContent = 'Загрузите FULL CIM RDF/XML-модель';
      date.hidden = true;
      status.hidden = true;
      useButton.hidden = true;
      renameButton.hidden = true;
      deleteButton.hidden = true;
      document.querySelector('#uploaded-model-error').hidden = true;
      return;
    }
    name.textContent = model.originalFileName;
    name.title = model.originalFileName;
    summary.textContent = formatBytes(model.size);
    status.textContent = statusLabel(model.status);
    status.className = `uploaded-model-status uploaded-model-status--${model.status.toLowerCase()}`;
    status.hidden = false;
    date.textContent = `Загружена ${new Intl.DateTimeFormat('ru-RU', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(model.uploadedAt))}`;
    date.hidden = false;
    const error = document.querySelector('#uploaded-model-error');
    error.textContent = model.error || '';
    error.hidden = !model.error;
    useButton.hidden = false;
    const active = isSelectedModelActive(model);
    useButton.textContent = uploadedModelUsePending ? 'Используется…' : active ? '● Используется' : 'Использовать';
    useButton.disabled = uploadedModelUsePending || model.status !== 'READY' || active;
    useButton.classList.toggle('uploaded-model-use--active', active);
    useButton.setAttribute('aria-busy', String(uploadedModelUsePending));
    deleteButton.hidden = false;
    deleteButton.textContent = 'Удалить';
    deleteButton.disabled = model.status === 'PROCESSING';
    deleteButton.setAttribute('aria-busy', 'false');
    renameButton.hidden = false;
    renameButton.textContent = 'Переименовать';
    renameButton.disabled = model.status === 'PROCESSING';
  }

  function isSelectedModelActive(model) {
    const profile = selectedProfile();
    return profile?.active === true
      && profile.jena.sourceType === RDF_SOURCE_TYPE.FILE
      && profile.jena.uploadedModelId === model.id;
  }

  function openUploadedModelRenameDialog() {
    const model = uploadedModels.find(item => item.id === uploadedModelSelect.value);
    if (!model) return;
    modelRenameForm.elements.name.value = modelDisplayName(model);
    const error = document.querySelector('#uploaded-model-rename-error');
    error.textContent = '';
    error.hidden = true;
    modelRenameDialog.showModal();
    modelRenameForm.elements.name.focus();
    modelRenameForm.elements.name.select();
  }

  async function renameUploadedModel(event) {
    event.preventDefault();
    const model = uploadedModels.find(item => item.id === uploadedModelSelect.value);
    if (!model) return;
    const submit = document.querySelector('#uploaded-model-rename-submit');
    const errorBox = document.querySelector('#uploaded-model-rename-error');
    submit.disabled = true;
    submit.textContent = 'Сохранение…';
    submit.setAttribute('aria-busy', 'true');
    try {
      const renamed = await api.renameUploadedModel(model.id, modelRenameForm.elements.name.value);
      await loadUploadedModels(renamed.id);
      modelRenameDialog.close();
    } catch (error) {
      errorBox.textContent = error.message;
      errorBox.hidden = false;
    } finally {
      submit.disabled = false;
      submit.textContent = 'Сохранить';
      submit.setAttribute('aria-busy', 'false');
    }
  }

  async function uploadModel() {
    const file = uploadedModelFile.files[0];
    if (!file) return;
    const button = document.querySelector('#uploaded-model-upload');
    button.disabled = true;
    button.textContent = 'Загрузка и обработка…';
    button.setAttribute('aria-busy', 'true');
    setSectionState('jena', null, 'Обрабатываем FULL-модель…');
    try {
      const model = await api.uploadModel(file);
      await loadUploadedModels(model.id);
      setSectionState('jena', true, '● Модель готова');
      updateDirty();
    } catch (error) {
      await loadUploadedModels();
      setSectionState('jena', false, error.message);
    } finally {
      uploadedModelFile.value = '';
      button.disabled = false;
      button.textContent = '+ Загрузить новую';
      button.setAttribute('aria-busy', 'false');
    }
  }

  async function deleteUploadedModel() {
    const model = uploadedModels.find(item => item.id === uploadedModelSelect.value);
    if (!model || !window.confirm(`Удалить модель «${modelDisplayName(model)}» и её Dataset?`)) return;
    clearUploadedModelDeleteError();
    const button = document.querySelector('#uploaded-model-delete');
    button.disabled = true;
    button.textContent = 'Удаление…';
    button.setAttribute('aria-busy', 'true');
    try {
      await api.deleteUploadedModel(model.id);
      await loadUploadedModels();
      updateDirty();
    } catch (error) {
      showUploadedModelDeleteError(error);
    } finally {
      button.setAttribute('aria-busy', 'false');
      renderUploadedModelInfo();
    }
  }

  function showUploadedModelDeleteError(error) {
    const alert = document.querySelector('#uploaded-model-delete-alert');
    const profiles = error.details?.profiles || [];
    const names = profiles.map(profile => profile.name).filter(Boolean);
    const subject = names.length === 1
      ? `профилем «${names[0]}»`
      : names.length > 1 ? `профилями ${names.map(name => `«${name}»`).join(', ')}` : 'профилем подключения';
    document.querySelector('#uploaded-model-delete-message').textContent = error.details?.code === 'MODEL_IN_USE'
      ? `Эта модель сейчас используется ${subject}. Сначала выберите другой источник или другую модель и сохраните профиль.`
      : error.message;
    alert.hidden = false;
  }

  function clearUploadedModelDeleteError() {
    const alert = document.querySelector('#uploaded-model-delete-alert');
    if (alert) alert.hidden = true;
  }

  function formatBytes(value) {
    if (value < BYTES_PER_UNIT) return `${value} Б`;
    const units = ['КБ', 'МБ', 'ГБ', 'ТБ'];
    let amount = value / BYTES_PER_UNIT;
    let unit = 0;
    while (amount >= BYTES_PER_UNIT && unit < units.length - 1) {
      amount /= BYTES_PER_UNIT;
      unit += 1;
    }
    return `${amount.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} ${units[unit]}`;
  }

  function statusLabel(status) {
    return { PROCESSING: 'Обрабатывается', READY: 'Готова', ERROR: 'Ошибка' }[status] || status;
  }

  function modelDisplayName(model) {
    if (model.name?.trim()) return model.name.trim();
    return model.originalFileName.replace(/\.(xml|rdf)$/i, '');
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
    saveButton.disabled = !dirty;
    saveButton.textContent = 'Сохранить';
    dirtyLabel.hidden = !dirty;
    const active = selectedProfile()?.active === true;
    activateProfileButton.disabled = active || dirty;
    activateProfileButton.title = dirty ? 'Сначала сохраните изменения профиля' : '';
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
    if (!isDirty() || !form.reportValidity()) return;
    const payload = allPayload();
    const datasetChanged = profile && (profile.jena.sourceType !== payload.jena.sourceType
      || profile.jena.type !== payload.jena.type || profile.jena.path !== payload.jena.path
      || profile.jena.cimApi?.modelId !== payload.jena.cimApi?.modelId
      || profile.jena.uploadedModelId !== payload.jena.uploadedModelId);
    if (profile?.active && !window.confirm('Изменения активного профиля автоматически переподключат Jena Ripper. Продолжить?')) return;
    const modelUseRequested = event.submitter?.id === 'uploaded-model-use';
    if (modelUseRequested) {
      uploadedModelUsePending = true;
      renderUploadedModelInfo();
    }
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
      if (response.restartRequired) {
        saveMessage.textContent = 'Применяем профиль подключения…';
        await onRestartRequired(selectedId);
      } else if (datasetChanged && profile?.active) {
        onSourceChanged();
      }
    } catch (error) {
      saveMessage.className = 'settings-message settings-message--error';
      saveMessage.textContent = error.message;
      saveMessage.hidden = false;
      updateDirty();
    } finally {
      uploadedModelUsePending = false;
      updateDirty();
      renderUploadedModelInfo();
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
        uploadedModelId: form.elements.jenaSourceType.value === RDF_SOURCE_TYPE.FILE
          ? uploadedModelSelect.value || null : null,
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
    setProfiles(profiles) {
      collection = profiles;
      if (!selectedId) selectedId = profiles.activeProfileId;
      renderHeaderProfiles();
    },
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
