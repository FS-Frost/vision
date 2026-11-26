// Persistencia de escaneos en IndexedDB.
// Dos stores: "meta" (ligero, para listar) y "data" (geometría por supercelda).

const DB_NAME = 'vision';
const DB_VERSION = 1;
let dbPromise = null;

function open() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('data')) db.createObjectStore('data', { keyPath: 'id' });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

function done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

function request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

/**
 * @param {{ name: string, voxel: number, hasColor: boolean, chunks: Array<{key, positions, normals, colors, indices}> }} scan
 * @returns {Promise<string>} id
 */
export async function saveScan({ name, voxel, hasColor, chunks }) {
  const db = await open();
  const id = crypto.randomUUID();
  let triangles = 0, bytes = 0;
  for (const c of chunks) {
    triangles += c.indices.length / 3;
    bytes += c.positions.byteLength + c.normals.byteLength + c.colors.byteLength + c.indices.byteLength;
  }
  const meta = { id, name, voxel, hasColor: !!hasColor, triangles, bytes, createdAt: Date.now() };
  const tx = db.transaction(['meta', 'data'], 'readwrite');
  tx.objectStore('meta').put(meta);
  tx.objectStore('data').put({ id, chunks });
  await done(tx);
  return id;
}

export async function listScans() {
  const db = await open();
  const all = await request(db.transaction('meta').objectStore('meta').getAll());
  return all.sort((a, b) => b.createdAt - a.createdAt);
}

export async function loadScan(id) {
  const db = await open();
  const tx = db.transaction(['meta', 'data']);
  const [meta, data] = await Promise.all([
    request(tx.objectStore('meta').get(id)),
    request(tx.objectStore('data').get(id)),
  ]);
  return meta && data ? { ...meta, chunks: data.chunks } : null;
}

export async function deleteScan(id) {
  const db = await open();
  const tx = db.transaction(['meta', 'data'], 'readwrite');
  tx.objectStore('meta').delete(id);
  tx.objectStore('data').delete(id);
  await done(tx);
}

export async function renameScan(id, name) {
  const db = await open();
  const tx = db.transaction('meta', 'readwrite');
  const store = tx.objectStore('meta');
  const meta = await request(store.get(id));
  if (meta) store.put({ ...meta, name });
  await done(tx);
}
