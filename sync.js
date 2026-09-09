'use strict';

const SYNC_DEBOUNCE_MS = 2000;
const SYNC_PURGE_CHUNK = 200;

let syncRunning = false;
let syncTimer = null;
let purgeSaveTimer = null;

const syncConf = () => {
  const conf = state.settings.sb;
  if (conf && conf.url && conf.key && conf.enabled !== false) {
    return conf;
  }
  return null;
};

const rowVersion = (row) => row.updatedAt || row.editedAt || row.createdAt || 0;

const syncNow = async (onProgress = () => {}) => {
  const conf = syncConf();
  if (!conf || syncRunning) {
    return null;
  }
  syncRunning = true;
  try {
    await syncPurges(conf, onProgress);
    const pulled = await syncPull(conf, onProgress);
    const pushed = await syncPush(conf, onProgress);
    conf.lastSyncedAt = Date.now();
    conf.lastError = null;
    await saveSettings();
    if (pulled > 0 && state.view === 'notes') {
      await renderNotes();
    }
    return { pulled, pushed };
  } catch (err) {
    console.error('sync failed', err);
    conf.lastError = String(err.message || err);
    await saveSettings();
    throw err;
  } finally {
    syncRunning = false;
  }
};

const syncSoon = (delay = SYNC_DEBOUNCE_MS) => {
  if (!syncConf()) {
    return;
  }
  clearTimeout(syncTimer);
  syncTimer = setTimeout(() => {
    syncNow().catch(() => {});
  }, delay);
};

notePurgedHook = (note) => {
  if (!state.settings.purgeQueue) {
    state.settings.purgeQueue = [];
  }
  const queue = state.settings.purgeQueue;
  if (!queue.includes(note.createdAt)) {
    queue.push(note.createdAt);
  }
  clearTimeout(purgeSaveTimer);
  purgeSaveTimer = setTimeout(() => {
    saveSettings();
    syncSoon();
  }, 500);
};

const syncPurges = async (conf, onProgress) => {
  const queue = state.settings.purgeQueue || [];
  if (!queue.length) {
    return 0;
  }
  onProgress('삭제 반영 중...');
  const purgedAt = Date.now();
  for (let offset = 0; offset < queue.length; offset += SYNC_PURGE_CHUNK) {
    const chunk = queue.slice(offset, offset + SYNC_PURGE_CHUNK);
    await sbRest(conf, `/notes?created_at=in.(${chunk.join(',')})`, {
      method: 'DELETE',
      headers: { Prefer: 'return=minimal' },
    });
    const rows = chunk.map((createdAt) => ({ created_at: createdAt, purged_at: purgedAt }));
    await sbUpsert(conf, 'purges', rows);
  }
  state.settings.purgeQueue = [];
  return queue.length;
};

const syncCleanupRemoteBlobs = async (onProgress = () => {}) => {
  const conf = syncConf();
  if (!conf) {
    return 0;
  }
  const response = await fetch(`${conf.url}/storage/v1/object/list/${SB_BUCKET}`, {
    method: 'POST',
    headers: sbHeaders(conf, { 'Content-Type': 'application/json' }),
    body: JSON.stringify({ prefix: 'blobs/', limit: 10000, offset: 0 }),
  });
  if (!response.ok) {
    throw new Error(`storage list ${response.status}`);
  }
  const objects = await response.json();

  let removed = 0;
  for (const object of objects || []) {
    const sha = object.name;
    onProgress(`원격 첨부 확인 중... (${removed})`);
    const filter = encodeURIComponent(JSON.stringify([{ sha }]));
    const refs = await sbRest(conf, `/notes?select=created_at&limit=1&blobs=cs.${filter}`);
    if (refs && refs.length) {
      continue;
    }
    await fetch(`${conf.url}/storage/v1/object/${SB_BUCKET}/blobs/${sha}`, {
      method: 'DELETE',
      headers: sbHeaders(conf),
    });
    conf.uploaded = (conf.uploaded || []).filter((item) => item !== sha);
    removed++;
  }
  await saveSettings();
  return removed;
};

const ensureLocalBlob = async (conf, meta) => {
  const existing = await findBlobBySha256(meta.sha);
  if (existing) {
    await incrementRefCount(existing.id);
    return existing.id;
  }
  try {
    const blob = await sbDownload(conf, `blobs/${meta.sha}`);
    return await db.blobs.add({
      sha256: meta.sha,
      blob,
      thumb: null,
      mime: meta.mime || blob.type || 'application/octet-stream',
      size: meta.size || blob.size,
      thumbSize: 0,
      width: meta.width || 0,
      height: meta.height || 0,
      name: meta.name || '',
      refCount: 1,
      createdAt: Date.now(),
    });
  } catch (err) {
    console.warn('attachment download failed', meta.sha, err);
    return null;
  }
};

const syncPullTombstones = async (conf, since) => {
  const tombs = await sbRest(conf, `/purges?purged_at=gt.${since}&order=purged_at.asc`).catch(
    () => null
  );
  let watermark = since;
  let changed = 0;
  for (const tomb of tombs || []) {
    watermark = Math.max(watermark, tomb.purged_at);
    const local = await db.notes.where('createdAt').equals(tomb.created_at).first();
    if (!local) {
      continue;
    }
    await db.notes.delete(local.id);
    for (const blobId of local.blobIds || []) {
      await decrementRefCount(blobId);
    }
    changed++;
  }
  return { watermark, changed };
};

const syncPullFolders = async (remote, folders, since) => {
  let watermark = since;
  let changed = 0;
  const claimed = new Set();

  for (const row of remote || []) {
    watermark = Math.max(watermark, row.updated_at);
    let local = folders.find((folder) => folder.createdAt === row.created_at);

    if (!local && since === 0) {
      local = folders.find(
        (folder) =>
          folder.name === row.name &&
          !claimed.has(folder.id) &&
          !(remote || []).some((other) => other.created_at === folder.createdAt)
      );
    }
    if (local) {
      claimed.add(local.id);
    }
    if (local && local.createdAt === row.created_at && rowVersion(local) >= row.updated_at) {
      continue;
    }

    const patch = {
      name: row.name,
      order: row.order || 0,
      pinned: !!row.pinned,
      createdAt: row.created_at,
      deletedAt: row.deleted_at || null,
      updatedAt: row.updated_at,
    };
    if (local) {
      await db.folders.update(local.id, patch);
      Object.assign(local, patch);
    } else {
      const id = await db.folders.add(patch);
      folders.push({ ...patch, id });
    }
    changed++;
  }
  return { watermark, changed };
};

const syncPullNotes = async (conf, remote, folders, since, onProgress) => {
  let watermark = since;
  let changed = 0;

  for (const row of remote || []) {
    watermark = Math.max(watermark, row.updated_at);
    const local = await db.notes.where('createdAt').equals(row.created_at).first();
    if (local && rowVersion(local) >= row.updated_at) {
      continue;
    }

    let content = row.content || '';
    const blobIds = [];
    for (const meta of row.blobs || []) {
      onProgress('첨부 받는 중...');
      const id = await ensureLocalBlob(conf, meta);
      if (id == null) {
        continue;
      }
      blobIds.push(id);
      content = content.split(`blob:${meta.sha})`).join(`blob:${id})`);
    }

    const folder = folders.find((item) => item.createdAt === row.folder_created_at);
    const patch = {
      folderId: folder ? folder.id : state.settings.defaultFolderId,
      content,
      blobIds,
      sourceUrl: row.source_url || null,
      sourceTitle: row.source_title || null,
      pinned: !!row.pinned,
      createdAt: row.created_at,
      editedAt: row.edited_at || null,
      deletedAt: row.deleted_at || null,
      updatedAt: row.updated_at,
    };
    if (local) {
      await db.notes.update(local.id, patch);
      for (const blobId of local.blobIds || []) {
        await decrementRefCount(blobId);
      }
    } else {
      await db.notes.add(patch);
    }
    changed++;
  }
  return { watermark, changed };
};

const syncPull = async (conf, onProgress) => {
  onProgress('받는 중...');
  const since = conf.lastPullAt || 0;
  const [remoteFolders, remoteNotes] = await Promise.all([
    sbRest(conf, `/folders?updated_at=gt.${since}&order=updated_at.asc`),
    sbRest(conf, `/notes?updated_at=gt.${since}&order=updated_at.asc`),
  ]);

  const tombs = await syncPullTombstones(conf, since);
  const folders = await db.folders.toArray();
  const folderResult = await syncPullFolders(remoteFolders, folders, since);
  const noteResult = await syncPullNotes(conf, remoteNotes, folders, since, onProgress);

  conf.lastPullAt = Math.max(tombs.watermark, folderResult.watermark, noteResult.watermark);
  return tombs.changed + folderResult.changed + noteResult.changed;
};

const syncPushBlobs = async (conf, note, uploaded, onProgress) => {
  let content = note.content || '';
  const metas = [];

  for (const id of note.blobIds || []) {
    const record = await getBlob(id);
    if (!record || !record.blob) {
      const token = new RegExp(String.raw`!?\[[^\]]*\]\(blob:${id}\)[ \t]*`, 'g');
      content = content.replace(token, '');
      continue;
    }
    if (!uploaded.has(record.sha256)) {
      const exists = await sbObjectExists(conf, `blobs/${record.sha256}`);
      if (!exists) {
        onProgress(`첨부 올리는 중... ${formatBytes(record.size || record.blob.size)}`);
        await sbUpload(conf, `blobs/${record.sha256}`, record.blob);
      }
      uploaded.add(record.sha256);
    }
    metas.push({
      sha: record.sha256,
      mime: record.mime,
      name: record.name || '',
      size: record.size,
      width: record.width || 0,
      height: record.height || 0,
    });
    content = content.split(`blob:${id})`).join(`blob:${record.sha256})`);
  }

  return { content, metas };
};

const syncPush = async (conf, onProgress) => {
  const since = conf.lastPushAt || 0;
  const startedAt = Date.now();

  const folders = (await db.folders.toArray()).filter((row) => rowVersion(row) > since);
  const notes = (await db.notes.toArray()).filter((row) => rowVersion(row) > since);
  if (!folders.length && !notes.length) {
    conf.lastPushAt = startedAt;
    return 0;
  }

  onProgress('보내는 중...');
  const folderRows = folders.map((folder) => ({
    created_at: folder.createdAt,
    name: folder.name,
    order: folder.order || 0,
    pinned: !!folder.pinned,
    deleted_at: folder.deletedAt || null,
    updated_at: rowVersion(folder),
  }));
  await sbUpsert(conf, 'folders', folderRows);

  const uploaded = new Set(conf.uploaded || []);
  const noteRows = [];

  for (const note of notes) {
    let folder = null;
    if (note.folderId != null) {
      folder = await db.folders.get(note.folderId);
    }
    const { content, metas } = await syncPushBlobs(conf, note, uploaded, onProgress);
    noteRows.push({
      created_at: note.createdAt,
      folder_created_at: folder ? folder.createdAt : null,
      content,
      blobs: metas,
      source_url: note.sourceUrl || null,
      source_title: note.sourceTitle || null,
      pinned: !!note.pinned,
      edited_at: note.editedAt || null,
      deleted_at: note.deletedAt || null,
      updated_at: rowVersion(note),
    });
  }

  await sbUpsert(conf, 'notes', noteRows);
  conf.uploaded = [...uploaded];
  conf.lastPushAt = startedAt;
  return noteRows.length + folderRows.length;
};
