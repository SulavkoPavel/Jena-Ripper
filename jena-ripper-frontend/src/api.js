const API_URL = (import.meta.env.VITE_API_URL || (import.meta.env.PROD ? window.location.origin : 'http://localhost:8082')).replace(/\/$/, '');

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
      headers: { 'Content-Type': 'application/json', ...headers },
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
      headers: { 'Content-Type': 'application/json' },
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
    const details = typeof payload.error === 'object' ? payload.error : null;
    const error = new Error(details?.message || payload.error || `Backend вернул ошибку ${response.status}`);
    error.details = details;
    throw error;
  }
  return response.json();
}

export const api = {
  dataset: () => get('/api/dataset'),
  status: () => get('/api/status'),
  applicationRuntime: () => get('/api/application/runtime'),
  shutdownApplication: () => post('/api/application/shutdown', undefined, { 'X-Jena-Ripper-Shutdown': 'ui' }),
  restartApplication: () => post('/api/application/restart', undefined, { 'X-Jena-Ripper-Restart': 'ui' }),
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
  sparql: (query, requestId, useOwnerRules = false) => post('/api/sparql/query', { query, requestId, useOwnerRules }),
  cancelSparql: (requestId) => del(`/api/sparql/query/${encodeURIComponent(requestId)}`),
  analyze: (query) => post('/api/sparql/analyze', { query }),
  benchmark: (query, warmup = 1, runs = 5, useOwnerRules = false) => post('/api/sparql/benchmark', { query, warmup, runs, useOwnerRules }),
  prefixes: () => get('/api/sparql/prefixes'),
  connectionSettings: () => get('/api/settings/connections'),
  saveConnectionSettings: (settings) => put('/api/settings/connections', settings),
  connectionProfiles: () => get('/api/settings/connections/profiles'),
  createConnectionProfile: (request) => post('/api/settings/connections/profiles', request),
  saveConnectionProfile: (profileId, settings) => put(`/api/settings/connections/profiles/${encodeURIComponent(profileId)}`, settings),
  renameConnectionProfile: (profileId, name) => put(`/api/settings/connections/profiles/${encodeURIComponent(profileId)}/name`, { name }),
  deleteConnectionProfile: (profileId) => del(`/api/settings/connections/profiles/${encodeURIComponent(profileId)}`),
  testJenaConnection: (settings, profileId) => post(withProfile('/api/settings/connections/test-jena', profileId), settings),
  cimModels: (settings, profileId) => post(withProfile('/api/settings/connections/cim-models', profileId), settings),
  testPostgresConnection: (settings, profileId) => post(withProfile('/api/settings/connections/test-postgres', profileId), settings),
  testRedisConnection: (settings, profileId) => post(withProfile('/api/settings/connections/test-redis', profileId), settings)
};
