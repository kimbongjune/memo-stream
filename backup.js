'use strict';

const sanitizeFileName = (name) => {
  const cleaned = (name || '').replace(/[\\/:*?"<>|\n\r]/g, '_').trim();
  return cleaned || 'untitled';
};

const localDateKey = (timestamp) => {
  const date = new Date(timestamp);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

const mimeExt = (mime) => {
  const table = {
    'image/png': 'png',
    'image/jpeg': 'jpg',
    'image/gif': 'gif',
    'image/webp': 'webp',
    'video/mp4': 'mp4',
    'video/webm': 'webm',
  };
  return table[mime] || 'bin';
};

const COMPRESSED_MIME = /^(image|video|audio)\//;

const zipAsync = (files) =>
  new Promise((resolve, reject) => {
    fflate.zip(files, { level: 6 }, (err, data) => (err ? reject(err) : resolve(data)));
  });

const unzipAsync = (bytes) =>
  new Promise((resolve, reject) => {
    fflate.unzip(bytes, (err, files) => (err ? reject(err) : resolve(files)));
  });

const exportZip = async () => {
  const folders = await getActiveFolders();
  const allNotes = await db.notes.toArray();
  const activeNotes = allNotes.filter((note) => note.deletedAt == null);
  const blobs = await db.blobs.toArray();
  const blobById = new Map(blobs.map((record) => [record.id, record]));

  const prefixCount = new Map();
  for (const record of blobs) {
    const base = record.sha256.slice(0, 8);
    prefixCount.set(base, (prefixCount.get(base) || 0) + 1);
  }
  const usedNames = new Set();
  const assetNames = new Map();
  for (const record of blobs) {
    let base = record.sha256.slice(0, 8);
    if (prefixCount.get(base) > 1) {
      base = record.sha256.slice(0, 16);
    }
    const ext = mimeExt(record.mime);
    let name = `${base}.${ext}`;
    let counter = 2;
    while (usedNames.has(name)) {
      name = `${base}-${counter}.${ext}`;
      counter++;
    }
    usedNames.add(name);
    assetNames.set(record.id, name);
  }

  const files = {};

  for (const record of blobs) {
    if (!record.blob) {
      continue;
    }
    const buffer = await record.blob.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    const key = `assets/${assetNames.get(record.id)}`;
    files[key] = COMPRESSED_MIME.test(record.mime || '') ? [bytes, { level: 0 }] : bytes;
  }

  const notesByFolder = new Map();
  for (const note of activeNotes) {
    if (!notesByFolder.has(note.folderId)) {
      notesByFolder.set(note.folderId, []);
    }
    notesByFolder.get(note.folderId).push(note);
  }

  for (const folder of folders) {
    const folderNotes = notesByFolder.get(folder.id) || [];
    if (folderNotes.length === 0) {
      continue;
    }
    const dir = sanitizeFileName(folder.name);

    const byDay = new Map();
    for (const note of folderNotes) {
      const day = localDateKey(note.createdAt);
      if (!byDay.has(day)) {
        byDay.set(day, []);
      }
      byDay.get(day).push(note);
    }

    for (const [day, dayNotes] of byDay) {
      dayNotes.sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0));
      let markdown = '';
      for (const note of dayNotes) {
        let content = note.content || '';
        content = content.replace(/blob:(\d+)/g, (match, idText) => {
          const record = blobById.get(Number(idText));
          if (!record) {
            return match;
          }
          return `../assets/${assetNames.get(record.id)}`;
        });
        if (note.sourceUrl) {
          const label = '출처';
          const source = `${note.sourceTitle || ''} ${note.sourceUrl}`.trim();
          content += `\n\n> ${label}: ${source}\n`;
        }
        markdown += `${content.trim()}\n\n---\n\n`;
      }
      files[`${dir}/${day}.md`] = textEncoder.encode(markdown);
    }
  }

  const backup = {
    app: 'memo-stream',
    version: 1,
    exportedAt: new Date().toISOString(),
    folders: folders.map((folder) => ({
      id: folder.id,
      name: folder.name,
      order: folder.order,
      pinned: folder.pinned,
      createdAt: folder.createdAt,
    })),
    notes: activeNotes.map((note) => ({
      id: note.id,
      folderId: note.folderId,
      content: note.content,
      blobIds: note.blobIds || [],
      sourceUrl: note.sourceUrl,
      sourceTitle: note.sourceTitle,
      pinned: note.pinned,
      createdAt: note.createdAt,
      editedAt: note.editedAt,
    })),
    blobs: blobs.map((record) => ({
      id: record.id,
      sha256: record.sha256,
      mime: record.mime,
      name: record.name || '',
      size: record.size,
      thumbSize: record.thumbSize,
      width: record.width,
      height: record.height,
      refCount: record.refCount,
      createdAt: record.createdAt,
    })),
  };
  files['backup.json'] = textEncoder.encode(JSON.stringify(backup));

  const data = await zipAsync(files);
  return { data, name: `memo-export-${localDateKey(Date.now())}.zip` };
};

const unzipData = (data) => {
  let bytes = data;
  if (!(data instanceof Uint8Array)) {
    bytes = new Uint8Array(data);
  }
  return unzipAsync(bytes);
};

const findAssetKey = (files, sha256) => {
  const short = (sha256 || '').slice(0, 8);
  const long = (sha256 || '').slice(0, 16);
  for (const key of Object.keys(files)) {
    if (!key.startsWith('assets/')) {
      continue;
    }
    const base = key.slice('assets/'.length).split('.')[0];
    if (base === short || base === long || base.startsWith(`${short}-`)) {
      return key;
    }
  }
  return null;
};

const importFromBackup = async (backup, files) => {
  let blobsImported = 0;
  let blobsReused = 0;
  const blobIdMap = new Map();

  for (const entry of backup.blobs || []) {
    const assetKey = findAssetKey(files, entry.sha256);
    if (!assetKey) {
      continue;
    }
    const mime = entry.mime || 'application/octet-stream';
    const blob = new Blob([files[assetKey]], { type: mime });
    const result = await findOrInsertBlob({
      blob,
      mime,
      thumb: null,
      name: entry.name || '',
      size: entry.size || blob.size,
      thumbSize: 0,
      width: entry.width || 0,
      height: entry.height || 0,
    });
    blobIdMap.set(entry.id, result.id);
    if (result.reused) {
      blobsReused++;
    } else {
      blobsImported++;
    }
  }

  const existingFolders = (await db.folders.toArray()).filter((row) => row.deletedAt == null);
  const folderIdMap = new Map();
  let foldersCreated = 0;
  for (const folder of backup.folders || []) {
    const found = existingFolders.find((row) => row.name === folder.name);
    if (found) {
      folderIdMap.set(folder.id, found.id);
      continue;
    }
    const createdAt = folder.createdAt || Date.now();
    const id = await db.folders.add({
      uid: newUid(),
      name: folder.name,
      order: folder.order || 0,
      pinned: !!folder.pinned,
      createdAt,
      deletedAt: null,
      updatedAt: Date.now(),
    });
    existingFolders.push({ id, name: folder.name, deletedAt: null });
    folderIdMap.set(folder.id, id);
    foldersCreated++;
  }

  let notesCreated = 0;
  for (const note of backup.notes || []) {
    let folderId = folderIdMap.get(note.folderId);
    if (folderId == null) {
      folderId = await ensureInbox();
    }
    const blobIds = (note.blobIds || [])
      .map((oldId) => blobIdMap.get(oldId))
      .filter((id) => id != null);
    const content = (note.content || '').replace(/blob:(\d+)/g, (match, idText) => {
      const newId = blobIdMap.get(Number(idText));
      if (newId == null) {
        return match;
      }
      return `blob:${newId}`;
    });
    await db.notes.add({
      uid: newUid(),
      folderId,
      content,
      blobIds,
      sourceUrl: note.sourceUrl || null,
      sourceTitle: note.sourceTitle || null,
      pinned: !!note.pinned,
      createdAt: note.createdAt || Date.now(),
      editedAt: note.editedAt || null,
      deletedAt: null,
      updatedAt: Date.now(),
    });
    for (const id of blobIds) {
      await incrementRefCount(id);
    }
    notesCreated++;
  }

  for (const id of blobIdMap.values()) {
    await decrementRefCount(id);
  }

  return {
    folders: foldersCreated,
    notes: notesCreated,
    blobs: blobsImported,
    reusedBlobs: blobsReused,
  };
};

const importFromMarkdown = async (files) => {
  const keys = Object.keys(files);
  const isImageFile = (key) => /\.(png|jpe?g|gif|webp)$/i.test(key);
  const mimeByExt = {
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    gif: 'image/gif',
    webp: 'image/webp',
  };

  const imageMap = new Map();
  let blobsImported = 0;
  let blobsReused = 0;
  for (const key of keys) {
    if (!isImageFile(key)) {
      continue;
    }
    const fileName = key.split('/').pop().toLowerCase();
    if (imageMap.has(fileName)) {
      continue;
    }
    const ext = fileName.split('.').pop();
    const mime = mimeByExt[ext] || 'application/octet-stream';
    const blob = new Blob([files[key]], { type: mime });
    const result = await findOrInsertBlob({
      blob,
      mime,
      thumb: null,
      name: fileName,
      size: blob.size,
      thumbSize: 0,
      width: 0,
      height: 0,
    });
    imageMap.set(fileName, result.id);
    if (result.reused) {
      blobsReused++;
    } else {
      blobsImported++;
    }
  }

  let foldersCreated = 0;
  let notesCreated = 0;
  const folderCache = new Map();

  const getFolderForDir = async (dir) => {
    if (!dir || dir === 'assets') {
      return ensureInbox();
    }
    if (folderCache.has(dir)) {
      return folderCache.get(dir);
    }
    const existing = (await db.folders.toArray()).filter((folder) => folder.deletedAt == null);
    const found = existing.find((folder) => folder.name === dir);
    let folderId = null;
    if (found) {
      folderId = found.id;
    } else {
      folderId = await createFolder(dir);
      foldersCreated++;
    }
    folderCache.set(dir, folderId);
    return folderId;
  };

  for (const key of keys) {
    if (!/\.md$/i.test(key)) {
      continue;
    }
    if (key.startsWith('assets/')) {
      continue;
    }
    const parts = key.split('/');
    let dir = '';
    if (parts.length > 1) {
      dir = parts[0];
    }
    const folderId = await getFolderForDir(dir);

    const raw = textDecoder.decode(files[key]);
    const usedIds = new Set();
    const content = raw.replace(/!\[[^\]]*\]\(([^)]+)\)/g, (match, ref) => {
      const cleanRef = ref.trim().replace(/^\.?\//, '');
      const fileName = cleanRef.split('/').pop().toLowerCase();
      const id = imageMap.get(fileName);
      if (id == null) {
        return match;
      }
      usedIds.add(id);
      return `![](blob:${id})`;
    });

    const now = Date.now();
    await db.notes.add({
      uid: newUid(),
      folderId,
      content,
      blobIds: [...usedIds],
      sourceUrl: null,
      sourceTitle: null,
      pinned: false,
      createdAt: now,
      editedAt: null,
      deletedAt: null,
      updatedAt: now,
    });
    for (const id of usedIds) {
      await incrementRefCount(id);
    }
    notesCreated++;
  }

  for (const id of imageMap.values()) {
    await decrementRefCount(id);
  }

  return {
    folders: foldersCreated,
    notes: notesCreated,
    blobs: blobsImported,
    reusedBlobs: blobsReused,
  };
};

const importFromZip = async (data) => {
  const files = await unzipData(data);
  if (files['backup.json']) {
    const backup = JSON.parse(textDecoder.decode(files['backup.json']));
    return importFromBackup(backup, files);
  }
  return importFromMarkdown(files);
};
