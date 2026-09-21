const API_URL = (import.meta.env.VITE_API_URL || (import.meta.env.PROD ? window.location.origin : 'http://localhost:8082')).replace(/\/$/, '');
const JSON_HEADERS = Object.freeze({ 'Content-Type': 'application/json' });
const NO_CONTENT_STATUS = 204;
const APPLICATION_API_PATH = '/api/application';
const SPARQL_API_PATH = '/api/sparql';
const CONNECTIONS_API_PATH = '/api/settings/connections';
const PROFILES_API_PATH = `${CONNECTIONS_API_PATH}/profiles`;
const MODELS_API_PATH = '/api/models';
const RESTART_REQUEST_HEADER = 'X-Jena-Ripper-Restart';
const SHUTDOWN_REQUEST_HEADER = 'X-Jena-Ripper-Shutdown';
const UI_REQUEST_HEADER_VALUE = 'ui';

async function get(path, params = {}) {
  const url = new URL(`${API_URL}${path}`);
  Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, value));
  const response = await fetch(url);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || `Backend вернул ошибку ${response.status}`);
  }
  return response.json();
}

async function post(path, body, headers = {}) {
  let response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method: 'POST',
      headers: { ...JSON_HEADERS, ...headers },
      body: JSON.stringify(body)
    });
  } catch (cause) {
    const error = new Error('Backend Jena Ripper недоступен.');
    error.code = 'BACKEND_UNAVAILABLE';
    error.cause = cause;
    throw error;
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    const details = typeof payload.error === 'object' ? payload.error : null;
    const error = new Error(details?.message || payload.error || `Backend вернул ошибку ${response.status}`);
    error.details = details;
    throw error;
  }
  return response.json();
}

async function put(path, body) {
  let response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method: 'PUT',
      headers: JSON_HEADERS,
      body: JSON.stringify(body)
    });
  } catch (cause) {
    const error = new Error('Backend Jena Ripper недоступен.');
    error.code = 'BACKEND_UNAVAILABLE';
    error.cause = cause;
    throw error;
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `Backend вернул ошибку ${response.status}`);
  }
  return response.json();
}

async function patch(path, body) {
  let response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method: 'PATCH',
      headers: JSON_HEADERS,
      body: JSON.stringify(body)
    });
  } catch (cause) {
    const error = new Error('Backend Jena Ripper недоступен.');
    error.code = 'BACKEND_UNAVAILABLE';
    error.cause = cause;
    throw error;
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `Backend вернул ошибку ${response.status}`);
  }
  return response.json();
}

function withProfile(path, profileId) {
  if (!profileId) return path;
  const separator = path.includes('?') ? '&' : '?';
  return `${path}${separator}profileId=${encodeURIComponent(profileId)}`;
}

const USER_DATA_PATHS = {
  sparqlTemplates: '/api/user-data/sparql/templates',
  sparqlHistory: '/api/user-data/sparql/history',
  redisTemplates: '/api/user-data/redis/templates',
  redisHistory: '/api/user-data/redis/history'
};

async function del(path) {
  let response;
  try {
    response = await fetch(`${API_URL}${path}`, { method: 'DELETE' });
  } catch (cause) {
    const error = new Error('Backend Jena Ripper недоступен.');
    error.code = 'BACKEND_UNAVAILABLE';
    error.cause = cause;
    throw error;
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    const details = typeof payload.error === 'object' ? payload.error : payload.code ? payload : null;
    const error = new Error(details?.message || payload.message || payload.error || `Backend вернул ошибку ${response.status}`);
    error.details = details;
    throw error;
  }
  return response.status === NO_CONTENT_STATUS ? null : response.json();
}

async function upload(path, file) {
  const data = new FormData();
  data.append('file', file);
  let response;
  try {
    response = await fetch(`${API_URL}${path}`, { method: 'POST', body: data });
  } catch (cause) {
    const error = new Error('Backend Jena Ripper недоступен.');
    error.code = 'BACKEND_UNAVAILABLE';
    error.cause = cause;
    throw error;
  }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `Backend вернул ошибку ${response.status}`);
  }
  return response.json();
}

async function download(path) {
  const response = await fetch(`${API_URL}${path}`);
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `Backend вернул ошибку ${response.status}`);
  }
  const disposition = response.headers.get('Content-Disposition') || '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  return { blob: await response.blob(), fileName: encoded ? decodeURIComponent(encoded) : plain || 'profiles.json' };
}

export const api = {
  dataset: () => get('/api/dataset'),
  status: () => get('/api/status'),
  applicationRuntime: () => get(`${APPLICATION_API_PATH}/runtime`),
  shutdownApplication: () => post(`${APPLICATION_API_PATH}/shutdown`, undefined,
    { [SHUTDOWN_REQUEST_HEADER]: UI_REQUEST_HEADER_VALUE }),
  restartApplication: () => post(`${APPLICATION_API_PATH}/restart`, undefined,
    { [RESTART_REQUEST_HEADER]: UI_REQUEST_HEADER_VALUE }),
  userData: () => get('/api/user-data'),
  migrateUserData: (data) => post('/api/user-data/migrate', data),
  saveUserDataCollection: (name, values) => put(USER_DATA_PATHS[name], values),
  node: (uri) => get('/api/nodes', { uri }),
  details: (uri) => get('/api/nodes/details', { uri }),
  ownerRules: (uri) => get('/api/nodes/owner-rules', { uri }),
  redisRules: (uri) => get('/api/nodes/redis-rules', { uri }),
  redisMetadata: () => get('/api/redis/metadata'),
  redisCommand: (command, args) => post('/api/redis/command', { command, args }),
  redisKey: (request) => post('/api/redis/key', request),
  neighbors: (uri) => get('/api/nodes/neighbors', { uri }),
  search: (query) => get('/api/search', { q: query }),
  metamodelGraph: () => get('/api/metamodel/graph'),
  metamodelAttributes: (classId) => get(`/api/metamodel/classes/${encodeURIComponent(classId)}/attributes`),
  sparql: (query, requestId, useOwnerRules = false) => post(`${SPARQL_API_PATH}/query`, { query, requestId, useOwnerRules }),
  cancelSparql: (requestId) => del(`${SPARQL_API_PATH}/query/${encodeURIComponent(requestId)}`),
  analyze: (query) => post(`${SPARQL_API_PATH}/analyze`, { query }),
  benchmark: (query, warmup = 1, runs = 5, useOwnerRules = false) => post(`${SPARQL_API_PATH}/benchmark`, { query, warmup, runs, useOwnerRules }),
  prefixes: () => get(`${SPARQL_API_PATH}/prefixes`),
  connectionSettings: () => get(CONNECTIONS_API_PATH),
  saveConnectionSettings: (settings) => put(CONNECTIONS_API_PATH, settings),
  connectionProfiles: () => get(PROFILES_API_PATH),
  createConnectionProfile: (request) => post(PROFILES_API_PATH, request),
  saveConnectionProfile: (profileId, settings) => put(`${PROFILES_API_PATH}/${encodeURIComponent(profileId)}`, settings),
  renameConnectionProfile: (profileId, name) => put(`${PROFILES_API_PATH}/${encodeURIComponent(profileId)}/name`, { name }),
  deleteConnectionProfile: (profileId) => del(`${PROFILES_API_PATH}/${encodeURIComponent(profileId)}`),
  activateConnectionProfile: (profileId) => post(`${PROFILES_API_PATH}/${encodeURIComponent(profileId)}/activate`),
  exportConnectionProfile: (profileId) => download(`${PROFILES_API_PATH}/${encodeURIComponent(profileId)}/export`),
  exportConnectionProfiles: () => download(`${PROFILES_API_PATH}/export`),
  importConnectionProfiles: (document, decisions = {}) => post(`${PROFILES_API_PATH}/import`, { document, decisions }),
  testJenaConnection: (settings, profileId) => post(withProfile(`${CONNECTIONS_API_PATH}/test-jena`, profileId), settings),
  cimModels: (settings, profileId) => post(withProfile(`${CONNECTIONS_API_PATH}/cim-models`, profileId), settings),
  uploadedModels: () => get(MODELS_API_PATH),
  uploadModel: (file) => upload(MODELS_API_PATH, file),
  renameUploadedModel: (id, name) => patch(`${MODELS_API_PATH}/${encodeURIComponent(id)}`, { name }),
  deleteUploadedModel: (id) => del(`${MODELS_API_PATH}/${encodeURIComponent(id)}`),
  testPostgresConnection: (settings, profileId) => post(withProfile(`${CONNECTIONS_API_PATH}/test-postgres`, profileId), settings),
  testRedisConnection: (settings, profileId) => post(withProfile(`${CONNECTIONS_API_PATH}/test-redis`, profileId), settings)
};
