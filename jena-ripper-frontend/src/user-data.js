import { api } from './api.js';

const MIGRATION_KEY = 'jena-ripper.user-data-migrated.v1';
const LEGACY_KEYS = {
  sparqlTemplates: 'jena-ripper.sparql-templates.v1',
  sparqlHistory: 'jena-ripper.sparql-history.v2',
  redisTemplates: 'jena-ripper.redis.templates.v1',
  redisHistory: 'jena-ripper.redis.history.v1'
};
const COLLECTIONS = Object.keys(LEGACY_KEYS);
let data = empty();

export async function initializeUserData() {
  let loaded = normalize(await api.userData());
  if (localStorage.getItem(MIGRATION_KEY) !== 'true') {
    const legacy = Object.fromEntries(COLLECTIONS.map(name => [name, stored(LEGACY_KEYS[name])]));
    if (COLLECTIONS.some(name => legacy[name].length)) loaded = normalize(await api.migrateUserData(legacy));
    localStorage.setItem(MIGRATION_KEY, 'true');
  }
  data = loaded;
}

export const userDataRepository = {
  all(name) {
    return structuredClone(data[name] || []);
  },
  async replace(name, values) {
    if (!COLLECTIONS.includes(name)) throw new Error('Неизвестная коллекция пользовательских данных.');
    const previous = data;
    data = { ...data, [name]: structuredClone(values) };
    try {
      data = normalize(await api.saveUserDataCollection(name, values));
      return this.all(name);
    } catch (error) {
      data = previous;
      throw error;
    }
  }
};

function empty() {
  return { version: 1, sparqlTemplates: [], sparqlHistory: [], redisTemplates: [], redisHistory: [] };
}

function normalize(value) {
  const normalized = empty();
  COLLECTIONS.forEach(name => { normalized[name] = Array.isArray(value?.[name]) ? value[name] : []; });
  return normalized;
}

function stored(key) {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(value) ? value : [];
  } catch {
    return [];
  }
}
