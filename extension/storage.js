'use strict';

const utf8Length = (text) => textEncoder.encode(text || '').length;

const formatBytes = (bytes) => {
  if (bytes == null || isNaN(bytes)) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let index = -1;
  do {
    value /= 1024;
    index++;
  } while (value >= 1024 && index < units.length - 1);
  const rounded = value >= 100 ? Math.round(value) : value.toFixed(1);
  return `${rounded} ${units[index]}`;
};

const getUsageStats = async () => {
  let estimate = { usage: 0, quota: 0 };
  try {
    estimate = await navigator.storage.estimate();
  } catch (err) {
    console.debug('storage.estimate unavailable', err);
  }

  const blobs = await db.blobs.toArray();
  const notes = await db.notes.toArray();
  const activeNotes = notes.filter((note) => note.deletedAt == null);
  const trashNotes = notes.filter((note) => note.deletedAt != null);

  const textBytes = activeNotes.reduce((sum, note) => sum + utf8Length(note.content), 0);
  const trashTextBytes = trashNotes.reduce((sum, note) => sum + utf8Length(note.content), 0);

  let imageBytes = 0;
  let videoBytes = 0;
  let fileBytes = 0;
  let imageCount = 0;
  let videoCount = 0;
  let fileCount = 0;
  let thumbBytes = 0;
  for (const record of blobs) {
    const mime = record.mime || '';
    if (mime.startsWith('image/')) {
      imageBytes += record.size || 0;
      imageCount++;
    } else if (mime.startsWith('video/')) {
      videoBytes += record.size || 0;
      videoCount++;
    } else {
      fileBytes += record.size || 0;
      fileCount++;
    }
    thumbBytes += record.thumbSize || 0;
  }

  const activeRefs = new Map();
  const trashRefs = new Map();
  for (const note of activeNotes) {
    for (const id of note.blobIds || []) {
      activeRefs.set(id, (activeRefs.get(id) || 0) + 1);
    }
  }
  for (const note of trashNotes) {
    for (const id of note.blobIds || []) {
      trashRefs.set(id, (trashRefs.get(id) || 0) + 1);
    }
  }
  let trashBlobBytes = 0;
  for (const record of blobs) {
    const active = activeRefs.get(record.id) || 0;
    const trashed = trashRefs.get(record.id) || 0;
    if (active === 0 && trashed > 0) {
      trashBlobBytes += (record.size || 0) + (record.thumbSize || 0);
    }
  }

  let savedBytes = 0;
  for (const record of blobs) {
    const refCount = record.refCount || 1;
    if (refCount > 1) {
      savedBytes += (record.size || 0) * (refCount - 1);
    }
  }

  return {
    usage: estimate.usage || 0,
    quota: estimate.quota || 0,
    noteCount: activeNotes.length,
    trashNoteCount: trashNotes.length,
    blobCount: blobs.length,
    textBytes,
    imageBytes,
    videoBytes,
    fileBytes,
    imageCount,
    videoCount,
    fileCount,
    thumbBytes,
    trashBytes: trashTextBytes + trashBlobBytes,
    savedBytes,
  };
};

const cleanupOrphanBlobs = async () => {
  const notes = await db.notes.toArray();
  const counts = new Map();
  const hold = (id) => counts.set(id, (counts.get(id) || 0) + 1);
  for (const note of notes) {
    const ids = new Set(note.blobIds || []);
    for (const id of collectBlobIds(note.content)) {
      ids.add(id);
    }
    for (const id of ids) {
      hold(id);
    }
  }
  for (const ref of state.draftRefs || []) {
    hold(ref.id);
  }

  const blobs = await db.blobs.toArray();
  let removed = 0;
  for (const record of blobs) {
    const actual = counts.get(record.id) || 0;
    if (actual === 0) {
      await db.blobs.delete(record.id);
      removed++;
    } else if (actual !== record.refCount) {
      await db.blobs.update(record.id, { refCount: actual });
    }
  }
  return removed;
};

const regenerateThumbnails = async () => {
  const blobs = await db.blobs.toArray();
  let regenerated = 0;
  for (const record of blobs) {
    if (record.thumb) {
      continue;
    }
    if (!record.blob || !record.blob.type || !record.blob.type.startsWith('image/')) {
      continue;
    }
    try {
      const bitmap = await createImageBitmap(record.blob);
      let thumb = null;
      try {
        thumb = await makeThumbnail(bitmap);
      } finally {
        bitmap.close();
      }
      await db.blobs.update(record.id, { thumb, thumbSize: thumb ? thumb.size : 0 });
      regenerated++;
    } catch (err) {
      console.debug('thumbnail skipped', record.id, err);
    }
  }
  return regenerated;
};

const runFullCleanup = async () => {
  const orphans = await cleanupOrphanBlobs();
  const purged = await emptyTrash();
  const thumbs = await regenerateThumbnails();
  return { orphans, purged, thumbs };
};
