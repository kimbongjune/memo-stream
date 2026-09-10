'use strict';

const SYNC_DEBOUNCE_MS = 2000;
const SYNC_PURGE_CHUNK = 200;
const SYNC_REMOTE_LIST_LIMIT = 10000;

let syncRunning = false;
let syncTimer = null;
let purgeSaveTimer = null;
const uploadedShas = new Set();

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
  if (!note.uid) {
    return;
  }
  if (!state.settings.purgeQueue) {
    state.settings.purgeQueue = [];
  }
  const queue = state.settings.purgeQueue;
  if (!queue.includes(note.uid)) {
    queue.push(note.uid);
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
    const list = chunk.map((uid) => `"${uid}"`).join(',');
    await sbRest(conf, `/notes?uid=in.(${list})`, {
      method: 'DELETE',
      headers: { Prefer: 'return=minimal' },
    });
    const rows = chunk.map((uid) => ({ uid, purged_at: purgedAt }));
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
    body: JSON.stringify({ prefix: 'blobs/', limit: SYNC_REMOTE_LIST_LIMIT, offset: 0 }),
  });
  if (!response.ok) {
    throw new Error(`storage list ${response.status}`);
  }
  const objects = await response.json();
  if ((objects || []).length >= SYNC_REMOTE_LIST_LIMIT) {
    console.warn(`원격 첨부가 ${SYNC_REMOTE_LIST_LIMIT}개를 넘어 이번 정리에서는 앞부분만 봅니다.`);
  }

  onProgress('참조 확인 중...');
  const rows = (await sbRest(conf, '/notes?select=blobs')) || [];
  const referenced = new Set();
  for (const row of rows) {
    for (const meta of row.blobs || []) {
      if (meta && meta.sha) {
        referenced.add(meta.sha);
      }
    }
  }

  let removed = 0;
  for (const object of objects || []) {
    const sha = object.name;
    if (referenced.has(sha)) {
      continue;
    }
    onProgress(`원격 첨부 정리 중... (${removed})`);
    await fetch(`${conf.url}/storage/v1/object/${SB_BUCKET}/blobs/${sha}`, {
      method: 'DELETE',
      headers: sbHeaders(conf),
    });
    uploadedShas.delete(sha);
    removed++;
  }
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
    const local = await db.notes.where('uid').equals(tomb.uid).first();
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
    let local = folders.find((folder) => folder.uid === row.uid);

    if (!local && since === 0) {
      local = folders.find(
        (folder) =>
          folder.name === row.name &&
          !claimed.has(folder.id) &&
          !(remote || []).some((other) => other.uid === folder.uid)
      );
    }
    if (local) {
      claimed.add(local.id);
    }
    if (local && local.uid === row.uid && rowVersion(local) >= row.updated_at) {
      continue;
    }

    const patch = {
      uid: row.uid,
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
  let stalled = 0;

  for (const row of remote || []) {
    const local = await db.notes.where('uid').equals(row.uid).first();
    if (local && !local.pullPending && rowVersion(local) >= row.updated_at) {
      watermark = Math.max(watermark, row.updated_at);
      continue;
    }

    let content = row.content || '';
    const blobIds = [];
    let missedBlob = false;
    for (const meta of row.blobs || []) {
      onProgress('첨부 받는 중...');
      const id = await ensureLocalBlob(conf, meta);
      if (id == null) {
        missedBlob = true;
        continue;
      }
      blobIds.push(id);
      content = content.split(`blob:${meta.sha})`).join(`blob:${id})`);
    }

    if (missedBlob) {
      stalled++;
    } else {
      watermark = Math.max(watermark, row.updated_at);
    }

    const folder = folders.find((item) => item.uid === row.folder_uid);
    const patch = {
      uid: row.uid,
      folderId: folder ? folder.id : await ensureInbox(),
      content,
      blobIds,
      sourceUrl: row.source_url || null,
      sourceTitle: row.source_title || null,
      pinned: !!row.pinned,
      createdAt: row.created_at,
      editedAt: row.edited_at || null,
      deletedAt: row.deleted_at || null,
      updatedAt: row.updated_at,
      pullPending: missedBlob ? true : null,
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
  if (stalled > 0) {
    console.warn(`${stalled}건은 첨부를 못 받아 워터마크를 미뤘습니다. 다음 동기화에서 재시도합니다.`);
  }
  return { watermark, changed };
};

const syncPull = async (conf, onProgress) => {
  onProgress('받는 중...');
  const sinceFolders = conf.lastPullFolders || 0;
  const sinceNotes = conf.lastPullNotes || 0;
  const sincePurges = conf.lastPullPurges || 0;

  const [remoteFolders, remoteNotes] = await Promise.all([
    sbRest(conf, `/folders?updated_at=gt.${sinceFolders}&order=updated_at.asc`),
    sbRest(conf, `/notes?updated_at=gt.${sinceNotes}&order=updated_at.asc`),
  ]);

  const tombs = await syncPullTombstones(conf, sincePurges);
  const folders = await db.folders.toArray();
  const folderResult = await syncPullFolders(remoteFolders, folders, sinceFolders);
  const noteResult = await syncPullNotes(conf, remoteNotes, folders, sinceNotes, onProgress);

  conf.lastPullPurges = tombs.watermark;
  conf.lastPullFolders = folderResult.watermark;
  conf.lastPullNotes = noteResult.watermark;
  return tombs.changed + folderResult.changed + noteResult.changed;
};

const syncPushBlobs = async (conf, note, onProgress) => {
  let content = note.content || '';
  const metas = [];

  for (const id of note.blobIds || []) {
    const record = await getBlob(id);
    if (!record || !record.blob) {
      const token = new RegExp(String.raw`!?\[[^\]]*\]\(blob:${id}\)[ \t]*`, 'g');
      content = content.replace(token, '');
      continue;
    }
    if (!uploadedShas.has(record.sha256)) {
      const exists = await sbObjectExists(conf, `blobs/${record.sha256}`);
      if (!exists) {
        onProgress(`첨부 올리는 중... ${formatBytes(record.size || record.blob.size)}`);
        await sbUpload(conf, `blobs/${record.sha256}`, record.blob);
      }
      uploadedShas.add(record.sha256);
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
  const notes = (await db.notes.toArray()).filter(
    (row) => rowVersion(row) > since && !row.pullPending
  );
  if (!folders.length && !notes.length) {
    conf.lastPushAt = startedAt;
    return 0;
  }

  onProgress('보내는 중...');
  const folderRows = folders.map((folder) => ({
    uid: folder.uid,
    name: folder.name,
    order: folder.order || 0,
    pinned: !!folder.pinned,
    created_at: folder.createdAt,
    deleted_at: folder.deletedAt || null,
    updated_at: rowVersion(folder),
  }));
  await sbUpsert(conf, 'folders', folderRows);

  const noteRows = [];

  for (const note of notes) {
    let folder = null;
    if (note.folderId != null) {
      folder = await db.folders.get(note.folderId);
    }
    const { content, metas } = await syncPushBlobs(conf, note, onProgress);
    noteRows.push({
      uid: note.uid,
      folder_uid: folder ? folder.uid : null,
      content,
      blobs: metas,
      source_url: note.sourceUrl || null,
      source_title: note.sourceTitle || null,
      pinned: !!note.pinned,
      created_at: note.createdAt,
      edited_at: note.editedAt || null,
      deleted_at: note.deletedAt || null,
      updated_at: rowVersion(note),
    });
  }

  await sbUpsert(conf, 'notes', noteRows);
  conf.lastPushAt = startedAt;
  return noteRows.length + folderRows.length;
};
