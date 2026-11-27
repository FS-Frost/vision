// Persistencia de escaneos en IndexedDB.
// Stores: "meta" (ligero, para listar), "data" (geometría por supercelda) y
// "volume" (TSDF compacto, necesario para continuar un escaneo).

const DB_NAME = 'vision';
const DB_VERSION = 2;
let dbPromise = null;

function open() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        for (const name of ['meta', 'data', 'volume']) {
          if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, { keyPath: 'id' });
        }
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

function summarize(chunks) {
  let triangles = 0, bytes = 0;
  for (const c of chunks) {
    triangles += c.indices.length / 3;
    bytes += c.positions.byteLength + c.normals.byteLength + c.colors.byteLength + c.indices.byteLength;
  }
  return { triangles, bytes };
}

/**
 * Crea o sobrescribe un escaneo.
 * @param {{ id?: string, name: string, voxel?: number, hasColor: boolean,
 *           chunks: Array<{key, positions, normals, colors, indices}>, volume?: object, createdAt?: number }} scan
 * @returns {Promise<string>} id
 */
export async function saveScan({ id, name, voxel, hasColor, chunks, volume, createdAt, startPose, startPhoto, anchors }) {
  const db = await open();
  id = id ?? crypto.randomUUID();
  const meta = {
    id, name, voxel: voxel ?? null, hasColor: !!hasColor, hasVolume: !!volume,
    // pista de inicio: pose de la cámara al empezar (marco del escaneo) y foto pequeña de esa vista
    startPose: startPose ? Array.from(startPose) : null, startPhoto: startPhoto ?? null,
    // anclas: puntos marcados con la mirilla (marco del escaneo) + foto con cruz de cada uno
    anchors: (anchors ?? []).map((a) => ({ p: Array.from(a.p), photo: a.photo ?? null })),
    ...summarize(chunks), createdAt: createdAt ?? Date.now(), updatedAt: Date.now(),
  };
  const tx = db.transaction(['meta', 'data', 'volume'], 'readwrite');
  tx.objectStore('meta').put(meta);
  tx.objectStore('data').put({ id, chunks });
  if (volume) tx.objectStore('volume').put({ id, volume });
  else tx.objectStore('volume').delete(id);
  await done(tx);
  return id;
}

export async function listScans() {
  const db = await open();
  const all = await request(db.transaction('meta').objectStore('meta').getAll());
  return all.sort((a, b) => (b.updatedAt ?? b.createdAt) - (a.updatedAt ?? a.createdAt));
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

export async function loadVolume(id) {
  const db = await open();
  const rec = await request(db.transaction('volume').objectStore('volume').get(id));
  return rec?.volume ?? null;
}

export async function deleteScan(id) {
  const db = await open();
  const tx = db.transaction(['meta', 'data', 'volume'], 'readwrite');
  for (const s of ['meta', 'data', 'volume']) tx.objectStore(s).delete(id);
  await done(tx);
}
