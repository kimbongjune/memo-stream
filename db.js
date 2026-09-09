'use strict';

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

const db = new Dexie('memoStream');

db.version(1).stores({
  folders: '++id, order, deletedAt',
  notes: '++id, folderId, createdAt, deletedAt',
  blobs: '++id, &sha256',
});

let notePurgedHook = null;

const touch = (patch) => ({ ...patch, updatedAt: Date.now() });

const ensureInbox = async () => {
  const count = await db.folders.count();
  if (count === 0) {
    const now = Date.now();
    return db.folders.add({
      name: 'Inbox',
      order: 0,
      pinned: true,
      createdAt: now,
      deletedAt: null,
      updatedAt: now,
    });
  }
  const folders = await db.folders.toArray();
  const first = folders.find((folder) => folder.deletedAt == null);
  return first ? first.id : null;
};

const getActiveFolders = async () => {
  const folders = (await db.folders.toArray()).filter((folder) => folder.deletedAt == null);
  return folders.sort((a, b) => {
    if (!!b.pinned !== !!a.pinned) {
      return b.pinned ? 1 : -1;
    }
    if ((a.order || 0) !== (b.order || 0)) {
      return (a.order || 0) - (b.order || 0);
    }
    return a.name.localeCompare(b.name, 'ko');
  });
};

const getFolder = async (id) => {
  if (id == null) {
    return null;
  }
  return (await db.folders.get(id)) || null;
};

const createFolder = async (name) => {
  const folders = (await db.folders.toArray()).filter((folder) => folder.deletedAt == null);
  const maxOrder = folders.reduce((max, folder) => Math.max(max, folder.order || 0), -1);
  const now = Date.now();
  return db.folders.add({
    name: name.trim(),
    order: maxOrder + 1,
    pinned: false,
    createdAt: now,
    deletedAt: null,
    updatedAt: now,
  });
};

const renameFolder = (id, name) => db.folders.update(id, touch({ name: name.trim() }));

const setFolderPinned = (id, pinned) => db.folders.update(id, touch({ pinned: !!pinned }));

const softDeleteFolder = (id) => db.folders.update(id, touch({ deletedAt: Date.now() }));

const getNotes = async (folderId) => {
  let query = db.notes;
  if (folderId != null) {
    query = db.notes.where('folderId').equals(folderId);
  }
  const notes = await query.toArray();
  return notes
    .filter((note) => note.deletedAt == null)
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
};

const createNote = ({
  folderId,
  content,
  blobIds = [],
  sourceUrl = null,
  sourceTitle = null,
}) => {
  const now = Date.now();
  return db.notes.add({
    folderId,
    content: content || '',
    blobIds,
    sourceUrl,
    sourceTitle,
    pinned: false,
    createdAt: now,
    editedAt: null,
    deletedAt: null,
    updatedAt: now,
  });
};

const updateNote = (id, patch) => db.notes.update(id, touch(patch));

const setNotePinned = (id, pinned) => db.notes.update(id, touch({ pinned: !!pinned }));

const moveNoteToFolder = (id, folderId) => db.notes.update(id, touch({ folderId }));

const softDeleteNote = (id) => db.notes.update(id, touch({ deletedAt: Date.now() }));

const restoreNote = (id) => db.notes.update(id, touch({ deletedAt: null }));

const getTrash = async () => {
  const notes = await db.notes.toArray();
  return notes
    .filter((note) => note.deletedAt != null)
    .sort((a, b) => (b.deletedAt || 0) - (a.deletedAt || 0));
};

const purgeNote = async (id) => {
  const note = await db.notes.get(id);
  if (!note) {
    return;
  }
  await db.notes.delete(id);
  if (notePurgedHook) {
    notePurgedHook(note);
  }
  for (const blobId of note.blobIds || []) {
    await decrementRefCount(blobId);
  }
};

const cleanupOldTrash = async (days = 30) => {
  const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
  const trash = await getTrash();
  const stale = trash.filter((note) => (note.deletedAt || 0) < cutoff);
  for (const note of stale) {
    await purgeNote(note.id);
  }
  return stale.length;
};

const getBlob = async (id) => (await db.blobs.get(id)) || null;

const getBlobsByIds = async (ids) => {
  const found = [];
  for (const id of ids || []) {
    const record = await db.blobs.get(id);
    if (record) {
      found.push(record);
    }
  }
  return found;
};

const findBlobBySha256 = async (sha256) =>
  (await db.blobs.where('sha256').equals(sha256).first()) || null;

const incrementRefCount = async (id) => {
  const record = await db.blobs.get(id);
  if (record) {
    await db.blobs.update(id, { refCount: (record.refCount || 0) + 1 });
  }
};

const decrementRefCount = async (id) => {
  const record = await db.blobs.get(id);
  if (!record) {
    return;
  }
  const next = (record.refCount || 1) - 1;
  if (next <= 0) {
    await db.blobs.delete(id);
  } else {
    await db.blobs.update(id, { refCount: next });
  }
};

const emptyTrash = async () => {
  const trash = await getTrash();
  for (const note of trash) {
    await purgeNote(note.id);
  }
  return trash.length;
};

const searchNotes = async (query, folderId) => {
  const needle = (query || '').trim().toLowerCase();
  if (!needle) {
    return [];
  }
  let notes = null;
  if (folderId == null) {
    notes = await db.notes.toArray();
  } else {
    notes = await db.notes.where('folderId').equals(folderId).toArray();
  }
  return notes
    .filter((note) => note.deletedAt == null)
    .filter((note) => {
      if ((note.content || '').toLowerCase().includes(needle)) {
        return true;
      }
      if ((note.sourceTitle || '').toLowerCase().includes(needle)) {
        return true;
      }
      return (note.sourceUrl || '').toLowerCase().includes(needle);
    })
    .sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
};

const filterNotesByMedia = async (notes, kind) => {
  if (!kind) {
    return notes;
  }
  const blobs = await db.blobs.toArray();
  const mimes = new Map(blobs.map((record) => [record.id, record.mime || '']));
  const matches = (id) => {
    const mime = mimes.get(id);
    if (mime == null) {
      return false;
    }
    if (kind === 'image') {
      return mime.startsWith('image/');
    }
    if (kind === 'video') {
      return mime.startsWith('video/');
    }
    return !mime.startsWith('image/') && !mime.startsWith('video/');
  };
  return notes.filter((note) => (note.blobIds || []).some(matches));
};
