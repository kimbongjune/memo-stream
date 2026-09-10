'use strict';

const MAX_ATTACH_BYTES = 200 * 1024 * 1024;
const LONG_NOTE_CHARS = 500;
const TRASH_KEEP_DAYS = 30;
const NOTE_PAGE_SIZE = 200;
const PENDING_CAPTURE_MAX_AGE_MS = 60 * 1000;
const FILTER_LABELS = { image: '사진', video: '동영상', file: '파일' };

const state = {
  view: 'notes',
  folderId: null,
  search: { active: false, query: '', global: true, kind: '' },
  settings: {
    defaultFolderId: null,
    enterBehavior: 'send',
    theme: 'system',
    onboarded: false,
    lastExportAt: null,
    lastFolderId: null,
  },
  draftRefs: [],
  pageSize: NOTE_PAGE_SIZE,
};

const $ = (selector) => document.querySelector(selector);

const loadSettings = async () => {
  try {
    const { settings } = await chrome.storage.local.get('settings');
    if (settings) {
      Object.assign(state.settings, settings);
    }
  } catch (err) {
    console.debug('settings load skipped', err);
  }
};

const saveSettings = async () => {
  try {
    await chrome.storage.local.set({ settings: state.settings });
  } catch (err) {
    console.debug('settings save skipped', err);
  }
};

const applyTheme = () => {
  const preference = state.settings.theme;
  let dark = false;
  if (preference === 'dark') {
    dark = true;
  } else if (preference === 'light') {
    dark = false;
  } else {
    dark = window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
};

window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  if (state.settings.theme === 'system') {
    applyTheme();
  }
});

let toastTimer = null;

const showToast = (message) => {
  const toast = $('#toast');
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 2500);
};

const showDialog = ({ title, message, buttons, input }) =>
  new Promise((resolve) => {
    const overlay = $('#dialog-overlay');
    const dialog = $('#dialog');
    dialog.innerHTML = '';

    const heading = document.createElement('h2');
    heading.textContent = title;
    dialog.appendChild(heading);

    if (message) {
      const paragraph = document.createElement('p');
      paragraph.textContent = message;
      dialog.appendChild(paragraph);
    }

    let field = null;
    if (input) {
      field = document.createElement('input');
      field.type = 'text';
      field.value = input.value || '';
      field.placeholder = input.placeholder || '';
      dialog.appendChild(field);
    }

    const actions = document.createElement('div');
    actions.className = 'dialog-actions';
    for (const button of buttons) {
      const element = document.createElement('button');
      let className = 'btn';
      if (button.primary) {
        className += ' primary';
      }
      if (button.danger) {
        className += ' danger';
      }
      element.className = className;
      element.textContent = button.label;
      element.addEventListener('click', () => {
        overlay.classList.add('hidden');
        resolve({ value: button.value, input: field ? field.value : null });
      });
      actions.appendChild(element);
    }
    dialog.appendChild(actions);

    overlay.classList.remove('hidden');
    if (!field) {
      return;
    }
    field.focus();
    field.select();
    field.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        const primary = buttons.find((button) => button.primary) || buttons[buttons.length - 1];
        overlay.classList.add('hidden');
        resolve({ value: primary.value, input: field.value });
        return;
      }
      if (event.key === 'Escape') {
        overlay.classList.add('hidden');
        resolve({ value: null, input: null });
      }
    });
  });

const ICONS = {
  pin: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 3h6l1 7 2 2H6l2-2 1-7z"/></svg>',
  edit: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>',
  trash: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 6V4h4v2"/></svg>',
  copy: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>',
  more: '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>',
  back: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>',
  folder: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>',
};

const miniIconBtn = (icon, onClick, danger) => {
  const button = document.createElement('button');
  let className = 'mini-btn';
  if (danger) {
    className += ' danger';
  }
  button.className = className;
  button.innerHTML = ICONS[icon] || '';
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    onClick();
  });
  return button;
};

const closeMenus = () => {
  $('#folder-menu').classList.add('hidden');
  $('#note-menu').classList.add('hidden');
};

const domainOf = (url) => {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch (err) {
    return url;
  }
};

const timeLabel = (timestamp) => {
  if (!timestamp) {
    return '';
  }
  const date = new Date(timestamp);
  const now = new Date();
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  const clock = `${hours}:${minutes}`;
  if (date.toDateString() === now.toDateString()) {
    return clock;
  }
  const yesterday = new Date(now.getTime() - 86400000);
  if (date.toDateString() === yesterday.toDateString()) {
    return `어제 ${clock}`;
  }
  return `${date.getMonth() + 1}/${date.getDate()} ${clock}`;
};

const daysAgoLabel = (timestamp) => {
  const days = Math.floor((Date.now() - timestamp) / 86400000);
  if (days <= 0) {
    return '오늘';
  }
  if (days === 1) {
    return '어제';
  }
  return `${days}일 전`;
};

const dateLabel = (key) => {
  if (key === localDateKey(Date.now())) {
    return '오늘';
  }
  if (key === localDateKey(Date.now() - 86400000)) {
    return '어제';
  }
  return key;
};

const collectBlobIds = (content) => {
  const matches = [...(content || '').matchAll(/blob:(\d+)/g)];
  return [...new Set(matches.map((match) => Number(match[1])))];
};

const ATTACH_RE = /!?\[[^\]]*\]\(blob:(\d+)\)[ \t]*/g;

const splitAttachments = (content) => {
  const refs = [];
  const stripped = (content || '').replace(ATTACH_RE, (match, id) => {
    refs.push({ id: Number(id), md: match.trim() });
    return '';
  });
  const text = stripped
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text, refs };
};

const joinAttachments = (text, refs) => {
  const body = (text || '').trim();
  const tail = (refs || []).map((ref) => ref.md).join(' ');
  if (!tail) {
    return body;
  }
  if (!body) {
    return tail;
  }
  return `${body}\n\n${tail}`;
};

const renderAttachChips = async (container, refs, onRemove) => {
  container.innerHTML = '';
  container.classList.toggle('hidden', refs.length === 0);

  for (const ref of refs) {
    const record = await getBlob(ref.id);
    const chip = document.createElement('div');
    chip.className = 'attach-chip';

    if (record && record.blob && (record.mime || '').startsWith('image/')) {
      const image = document.createElement('img');
      const source = record.thumb || record.blob;
      image.src = blobUrlFor(`${record.thumb ? 'thumb' : 'blob'}:${ref.id}`, source);
      image.alt = '';
      chip.appendChild(image);
    } else {
      const name = document.createElement('span');
      name.className = 'attach-name';
      name.textContent = (record && record.name) || `첨부 ${ref.id}`;
      chip.appendChild(name);
    }

    const remove = document.createElement('button');
    remove.className = 'attach-x';
    remove.title = '첨부 제거';
    remove.textContent = '×';
    remove.addEventListener('click', () => onRemove(ref));
    chip.appendChild(remove);
    container.appendChild(chip);
  }
};

const renderDraftChips = () =>
  renderAttachChips($('#attach-strip'), state.draftRefs, async (ref) => {
    state.draftRefs = state.draftRefs.filter((item) => item !== ref);
    await decrementRefCount(ref.id);
    await renderDraftChips();
  });

const updateHeader = () => {
  const nameEl = $('#folder-name');
  const folderBtn = $('#folder-btn');
  if (state.view === 'settings') {
    nameEl.textContent = '설정';
    folderBtn.classList.add('back-mode');
    return;
  }
  if (state.view === 'trash') {
    nameEl.textContent = '휴지통';
    folderBtn.classList.add('back-mode');
    return;
  }
  folderBtn.classList.remove('back-mode');
  if (state.folderId == null) {
    nameEl.textContent = '전체';
    return;
  }
  getFolder(state.folderId).then((folder) => {
    nameEl.textContent = folder ? folder.name : '전체';
  });
};

const render = async () => {
  if (lastRenderedView === 'notes' && state.view !== 'notes') {
    savedTop = $('#main').scrollTop;
  }
  lastRenderedView = state.view;

  updateHeader();
  $('#note-list').classList.toggle('hidden', state.view !== 'notes');
  $('#settings-view').classList.toggle('hidden', state.view !== 'settings');
  $('#trash-view').classList.toggle('hidden', state.view !== 'trash');
  $('#input-area').classList.toggle('hidden', state.view !== 'notes');

  if (state.view === 'notes') {
    await renderNotes();
    return;
  }
  if (state.view === 'settings') {
    await renderSettings();
    return;
  }
  await renderTrash();
};

const emptyStateText = () => {
  if (!state.search.active) {
    return '아직 메모가 없습니다. 아래에 첫 메모를 적어보세요.';
  }
  if (state.search.kind && !state.search.query.trim()) {
    return `${FILTER_LABELS[state.search.kind]}이(가) 있는 메모가 없습니다`;
  }
  return '검색 결과가 없습니다';
};

const renderNoteBubble = async (note, folderNames) => {
  const element = document.createElement('div');
  let className = 'note';
  if (note.pinned) {
    className += ' pinned';
  }
  element.className = className;
  element.dataset.id = note.id;

  const body = document.createElement('div');
  body.className = 'note-body';
  const { html } = await md.render(note.content);
  body.innerHTML = html;

  for (const anchor of body.querySelectorAll('a')) {
    if (anchor.hasAttribute('download')) {
      continue;
    }
    anchor.target = '_blank';
    anchor.rel = 'noopener';
  }

  const isLong = (note.content || '').length > LONG_NOTE_CHARS;
  if (isLong) {
    body.classList.add('collapsed');
  }
  element.appendChild(body);

  if (isLong) {
    const expand = document.createElement('button');
    expand.className = 'expand-btn';
    expand.textContent = '더보기';
    expand.addEventListener('click', () => {
      const collapsed = body.classList.toggle('collapsed');
      expand.textContent = collapsed ? '더보기' : '접기';
    });
    element.appendChild(expand);
  }

  if (note.sourceUrl) {
    const source = document.createElement('div');
    source.className = 'note-source';
    source.textContent = domainOf(note.sourceUrl);
    source.title = note.sourceUrl;
    element.appendChild(source);
  }

  const meta = document.createElement('div');
  meta.className = 'note-meta';
  if (folderNames && folderNames.size > 0) {
    const folderName = folderNames.get(note.folderId);
    if (folderName) {
      const tag = document.createElement('span');
      tag.className = 'note-folder-tag';
      tag.textContent = folderName;
      meta.appendChild(tag);
    }
  }
  const time = document.createElement('span');
  time.className = 'note-time';
  time.textContent = timeLabel(note.createdAt);
  meta.appendChild(time);
  element.appendChild(meta);

  const more = document.createElement('button');
  more.className = 'note-more';
  more.innerHTML = ICONS.more;
  more.title = '메모 메뉴';
  more.addEventListener('click', (event) => {
    event.stopPropagation();
    showNoteMenu(note, more);
  });
  element.appendChild(more);

  return element;
};

const groupByDate = (notes) => {
  const groups = new Map();
  for (const note of notes) {
    const key = localDateKey(note.createdAt);
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(note);
  }
  return groups;
};

let renderSeq = 0;

let editingNoteId = null;
let renderMissedWhileEditing = false;
let lastScopeKey = null;
let lastRenderedFolderId = null;
let lastRenderedView = null;
let stickBottom = true;
let savedTop = null;
let bubbleCache = new Map();

const trackScroll = () => {
  const main = $('#main');
  main.addEventListener('scroll', () => {
    if (state.view !== 'notes' || $('#note-list').classList.contains('hidden')) {
      return;
    }
    stickBottom = main.scrollHeight - main.scrollTop - main.clientHeight < 80;
  });
};

const stickToBottom = (main, seq) => {
  const apply = () => {
    if (seq !== renderSeq) {
      return;
    }
    main.scrollTop = main.scrollHeight;
    stickBottom = true;
  };
  apply();
  requestAnimationFrame(apply);
  for (const media of $('#note-list').querySelectorAll('img, video')) {
    media.addEventListener('load', apply, { once: true });
    media.addEventListener('loadedmetadata', apply, { once: true });
  }
};

const renderNotes = async (extraNote = null, stick = false) => {
  if (editingNoteId != null) {
    renderMissedWhileEditing = true;
    return;
  }
  const seq = ++renderSeq;
  const list = $('#note-list');
  const frame = document.createDocumentFragment();

  const { active, query, global, kind } = state.search;
  const scopeKey = JSON.stringify([state.folderId, active, query, global, kind]);
  if (scopeKey !== lastScopeKey) {
    lastScopeKey = scopeKey;
    state.pageSize = NOTE_PAGE_SIZE;
  }

  const main = $('#main');
  const folderChanged = state.folderId !== lastRenderedFolderId;
  lastRenderedFolderId = state.folderId;
  const goBottom = stick || folderChanged || stickBottom;

  const needle = query.trim();
  const searching = active && (needle.length > 0 || kind.length > 0);
  const scope = searching && global ? null : state.folderId;
  let notes = null;
  if (needle) {
    notes = await searchNotes(query, scope);
  } else {
    notes = await getNotes(scope);
  }
  if (searching && kind) {
    notes = await filterNotesByMedia(notes, kind);
  }
  if (extraNote) {
    notes = [...notes, extraNote];
  }
  if (seq !== renderSeq) {
    return;
  }

  const marked = searching && needle ? needle : '';
  const bubbleKey = (note) =>
    [
      note.updatedAt || 0,
      note.editedAt || 0,
      note.pinned ? 1 : 0,
      note.folderId,
      folderNames.get(note.folderId) || '',
      marked,
    ].join('|');
  const nextCache = new Map();
  const bubbleFor = async (note) => {
    const key = bubbleKey(note);
    const hit = bubbleCache.get(note.id);
    if (hit && hit.key === key) {
      nextCache.set(note.id, hit);
      return hit.element;
    }
    const element = await renderNoteBubble(note, folderNames);
    if (marked) {
      md.highlight(element.querySelector('.note-body'), marked);
    }
    nextCache.set(note.id, { key, element });
    return element;
  };

  const commit = () => {
    list.replaceChildren(frame);
    bubbleCache = nextCache;
  };

  if (notes.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.textContent = emptyStateText();
    frame.appendChild(empty);
    commit();
    return;
  }

  const ascending = (a, b) => (a.createdAt || 0) - (b.createdAt || 0);
  const pinned = notes.filter((note) => note.pinned).sort(ascending);
  const all = notes.filter((note) => !note.pinned).sort(ascending);
  const hidden = Math.max(0, all.length - state.pageSize);
  const rest = hidden > 0 ? all.slice(hidden) : all;

  const folderNames = new Map();
  if (state.folderId == null) {
    const folders = await getActiveFolders();
    for (const folder of folders) {
      folderNames.set(folder.id, folder.name);
    }
  }
  if (seq !== renderSeq) {
    return;
  }

  const addSeparator = (label) => {
    const separator = document.createElement('div');
    separator.className = 'date-sep';
    separator.textContent = label;
    frame.appendChild(separator);
  };

  const addGroups = async (groupNotes) => {
    for (const [key, dayNotes] of groupByDate(groupNotes)) {
      addSeparator(dateLabel(key));
      for (const note of dayNotes) {
        const bubble = await bubbleFor(note);
        if (seq !== renderSeq) {
          return false;
        }
        frame.appendChild(bubble);
      }
    }
    return true;
  };

  if (hidden > 0) {
    const more = document.createElement('button');
    more.className = 'expand-btn';
    more.textContent = `이전 메모 ${hidden}개 더 보기`;
    more.addEventListener('click', async () => {
      const anchor = main.scrollHeight - main.scrollTop;
      state.pageSize += NOTE_PAGE_SIZE;
      stickBottom = false;
      await renderNotes();
      main.scrollTop = main.scrollHeight - anchor;
    });
    frame.appendChild(more);
  }

  if (pinned.length > 0) {
    addSeparator('고정됨');
    for (const note of pinned) {
      const bubble = await bubbleFor(note);
      if (seq !== renderSeq) {
        return;
      }
      frame.appendChild(bubble);
    }
  }

  if (state.folderId == null && !searching) {
    const folderGroups = new Map();
    for (const note of rest) {
      const key = note.folderId != null ? note.folderId : '__none__';
      if (!folderGroups.has(key)) {
        folderGroups.set(key, []);
      }
      folderGroups.get(key).push(note);
    }
    for (const [key, groupNotes] of folderGroups) {
      let name = '알 수 없는 폴더';
      if (key !== '__none__') {
        name = folderNames.get(Number(key)) || '알 수 없는 폴더';
      }
      const header = document.createElement('div');
      header.className = 'folder-group-header';
      header.textContent = name;
      frame.appendChild(header);
      if (!(await addGroups(groupNotes))) {
        return;
      }
    }
  } else if (!(await addGroups(rest))) {
    return;
  }

  commit();

  if (savedTop != null) {
    main.scrollTop = savedTop;
    savedTop = null;
    return;
  }
  if (goBottom || extraNote) {
    stickToBottom(main, seq);
  }
};

const renderFolderMenu = async () => {
  const list = $('#folder-list');
  list.innerHTML = '';
  const folders = await getActiveFolders();

  const allBtn = document.querySelector('#folder-menu [data-nav="all"]');
  const trashBtn = document.querySelector('#folder-menu [data-nav="trash"]');
  if (allBtn) {
    allBtn.classList.toggle('active', state.folderId == null && state.view === 'notes');
  }
  if (trashBtn) {
    trashBtn.classList.toggle('active', state.view === 'trash');
  }

  for (const folder of folders) {
    const item = document.createElement('div');
    item.className = 'folder-item';
    if (folder.pinned) {
      item.classList.add('pinned');
    }

    const button = document.createElement('button');
    let className = 'menu-item';
    if (state.folderId === folder.id && state.view === 'notes') {
      className += ' active';
    }
    button.className = className;
    if (folder.pinned) {
      const mark = document.createElement('span');
      mark.className = 'folder-pin-mark';
      mark.innerHTML = ICONS.pin;
      button.appendChild(mark);
    }
    const label = document.createElement('span');
    label.className = 'menu-label';
    label.textContent = folder.name;
    button.appendChild(label);
    button.addEventListener('click', () => {
      rememberFolder(folder.id);
      state.view = 'notes';
      closeSearch();
      closeMenus();
      render();
    });

    const actions = document.createElement('div');
    actions.className = 'folder-actions';
    actions.appendChild(
      miniIconBtn('pin', async () => {
        await setFolderPinned(folder.id, !folder.pinned);
        await renderFolderMenu();
      })
    );
    actions.appendChild(miniIconBtn('edit', () => renameFolderDialog(folder)));
    actions.appendChild(miniIconBtn('trash', () => deleteFolderDialog(folder), true));

    item.append(button, actions);
    list.appendChild(item);
  }
};

const toggleFolderMenu = async () => {
  const menu = $('#folder-menu');
  if (menu.classList.contains('hidden')) {
    await renderFolderMenu();
    menu.classList.remove('hidden');
    return;
  }
  menu.classList.add('hidden');
};

const createNewFolder = async () => {
  const input = $('#new-folder-input');
  const name = input.value.trim();
  if (!name) {
    return;
  }
  const id = await createFolder(name);
  input.value = '';
  rememberFolder(id);
  state.view = 'notes';
  closeMenus();
  await render();
  showToast('폴더를 만들었습니다');
  syncSoon();
};

const renameFolderDialog = async (folder) => {
  const result = await showDialog({
    title: '폴더 이름 변경',
    input: { value: folder.name, placeholder: '폴더 이름' },
    buttons: [
      { label: '취소', value: 'cancel' },
      { label: '변경', value: 'ok', primary: true },
    ],
  });
  if (result.value !== 'ok' || !result.input || !result.input.trim()) {
    return;
  }
  await renameFolder(folder.id, result.input);
  await render();
  await renderFolderMenu();
  syncSoon();
};

const deleteFolderDialog = async (folder) => {
  const result = await showDialog({
    title: `"${folder.name}" 폴더를 삭제할까요?`,
    message: '안에 있는 메모를 어떻게 할지 선택해 주세요.',
    buttons: [
      { label: '취소', value: 'cancel' },
      { label: 'Inbox로 이동', value: 'inbox' },
      { label: '메모도 함께 삭제', value: 'delete', danger: true },
    ],
  });
  if (result.value === 'cancel' || result.value == null) {
    return;
  }

  const notes = await getNotes(folder.id);

  const pickFallbackFolder = async () => {
    const current = state.settings.defaultFolderId;
    if (current != null && current !== folder.id) {
      return current;
    }
    const others = (await getActiveFolders()).filter((item) => item.id !== folder.id);
    if (others.length > 0) {
      return others[0].id;
    }
    return createFolder('Inbox');
  };

  if (result.value === 'inbox') {
    const inboxId = await pickFallbackFolder();
    for (const note of notes) {
      await moveNoteToFolder(note.id, inboxId);
    }
    showToast('메모를 Inbox로 옮겼습니다');
  } else {
    for (const note of notes) {
      await softDeleteNote(note.id);
    }
    showToast('폴더와 메모를 휴지통으로 옮겼습니다');
  }
  if (state.settings.defaultFolderId === folder.id) {
    state.settings.defaultFolderId = await pickFallbackFolder();
    await saveSettings();
  }
  await softDeleteFolder(folder.id);
  if (state.folderId === folder.id) {
    rememberFolder(null);
  }
  await render();
  await renderFolderMenu();
  syncSoon();
};

let menuContext = null;

const menuAddItem = (menu, label, icon, onClick, danger) => {
  const button = document.createElement('button');
  let className = 'menu-item';
  if (danger) {
    className += ' danger';
  }
  button.className = className;
  if (icon) {
    const wrapper = document.createElement('span');
    wrapper.innerHTML = ICONS[icon] || '';
    button.appendChild(wrapper);
  }
  const labelEl = document.createElement('span');
  labelEl.className = 'menu-label';
  labelEl.textContent = label;
  button.appendChild(labelEl);
  button.addEventListener('click', () => {
    closeMenus();
    onClick();
  });
  menu.appendChild(button);
};

const positionMenu = (menu, anchor) => {
  const rect = anchor.getBoundingClientRect();
  const width = menu.offsetWidth;
  const height = menu.offsetHeight;
  let left = rect.right - width;
  let top = rect.bottom + 4;
  if (left < 4) {
    left = 4;
  }
  if (top + height > window.innerHeight - 4) {
    top = Math.max(4, rect.top - height - 4);
  }
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
};

const renderMoveMenu = async () => {
  const { note } = menuContext;
  const menu = $('#note-menu');
  menu.innerHTML = '';

  menuAddItem(menu, '뒤로', 'back', () => renderNoteMenuMain());
  const divider = document.createElement('div');
  divider.className = 'menu-divider';
  menu.appendChild(divider);

  const folders = await getActiveFolders();
  for (const folder of folders) {
    const isCurrent = folder.id === note.folderId;
    let label = folder.name;
    if (isCurrent) {
      label = `${folder.name} (현재)`;
    }
    menuAddItem(menu, label, null, async () => {
      if (isCurrent) {
        return;
      }
      await moveNoteToFolder(note.id, folder.id);
      showToast(`"${folder.name}"(으)로 옮겼습니다`);
      await render();
      syncSoon();
    });
  }

  menu.classList.remove('hidden');
  positionMenu(menu, menuContext.anchor);
};

const renderNoteMenuMain = () => {
  const { note } = menuContext;
  const menu = $('#note-menu');
  menu.innerHTML = '';

  menuAddItem(menu, '복사', 'copy', () => copyNote(note));
  menuAddItem(menu, '편집', 'edit', () => startEdit(note));
  const pinLabel = note.pinned ? '고정 해제' : '고정';
  menuAddItem(menu, pinLabel, 'pin', () => togglePin(note));
  menuAddItem(menu, '폴더로 이동', 'folder', () => renderMoveMenu());
  menuAddItem(menu, '삭제', 'trash', () => deleteNote(note), true);

  menu.classList.remove('hidden');
  positionMenu(menu, menuContext.anchor);
};

const showNoteMenu = (note, anchor) => {
  menuContext = { note, anchor };
  renderNoteMenuMain();
};

const blobAsPng = async (id) => {
  const record = await getBlob(id);
  if (!record || !record.blob) {
    throw new Error(`blob ${id} missing`);
  }
  if (record.mime === 'image/png') {
    return record.blob;
  }
  const bitmap = await createImageBitmap(record.blob);
  try {
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    const png = await canvasToBlob(canvas, 'image/png');
    if (!png) {
      throw new Error('png conversion failed');
    }
    return png;
  } finally {
    bitmap.close();
  }
};

const blobAsDataUrl = async (id) => {
  const record = await getBlob(id);
  if (!record || !record.blob) {
    throw new Error(`blob ${id} missing`);
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(record.blob);
  });
};

const noteAsHtml = async (text, imageIds) => {
  const escape = (value) =>
    String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const images = [];
  for (const id of imageIds) {
    try {
      images.push(`<img src="${await blobAsDataUrl(id)}">`);
    } catch (err) {
      console.debug('skipped broken reference', id, err);
    }
  }
  let head = '';
  if (text) {
    head = escape(text).replace(/\n/g, '<br>');
    if (images.length) {
      head += '<br>';
    }
  }
  return new Blob([head + images.join('')], { type: 'text/html' });
};

const copyNote = async (note) => {
  const content = note.content || '';
  const { text } = splitAttachments(content);
  const mediaIds = [...content.matchAll(/!\[[^\]]*\]\(blob:(\d+)\)/g)].map((m) => Number(m[1]));
  const records = await getBlobsByIds(mediaIds);
  const imageIds = records
    .filter((record) => (record.mime || '').startsWith('image/'))
    .map((record) => record.id);

  try {
    if (imageIds.length === 0 || !navigator.clipboard.write || !window.ClipboardItem) {
      await navigator.clipboard.writeText(content);
    } else {
      const parts = {
        'text/plain': new Blob([text], { type: 'text/plain' }),
        'text/html': noteAsHtml(text, imageIds),
      };
      if (imageIds.length === 1) {
        parts['image/png'] = blobAsPng(imageIds[0]);
      }
      await navigator.clipboard.write([new ClipboardItem(parts)]);
    }
    showToast('복사했습니다');
  } catch (err) {
    console.error('copyNote failed', err);
    try {
      await navigator.clipboard.writeText(content);
      showToast('텍스트만 복사했습니다');
    } catch (fallbackErr) {
      console.error('clipboard unavailable', fallbackErr);
      showToast('복사에 실패했습니다');
    }
  }
};

const togglePin = async (note) => {
  await setNotePinned(note.id, !note.pinned);
  await renderNotes();
  syncSoon();
};

const deleteNote = async (note) => {
  await softDeleteNote(note.id);
  showToast('휴지통으로 옮겼습니다');
  await renderNotes();
  syncSoon();
};

const startEdit = (note) => {
  const element = document.querySelector(`.note[data-id="${note.id}"]`);
  if (!element) {
    return;
  }
  const body = element.querySelector('.note-body');
  if (!body) {
    return;
  }
  const originalHtml = body.innerHTML;
  editingNoteId = note.id;
  renderMissedWhileEditing = false;

  const endEdit = async () => {
    editingNoteId = null;
    if (renderMissedWhileEditing) {
      renderMissedWhileEditing = false;
      await renderNotes();
      return true;
    }
    return false;
  };
  const split = splitAttachments(note.content);
  let refs = split.refs;

  body.innerHTML = '';
  const strip = document.createElement('div');
  strip.className = 'attach-strip note-edit-attach hidden';
  body.appendChild(strip);

  const drawStrip = () =>
    renderAttachChips(strip, refs, (ref) => {
      refs = refs.filter((item) => item !== ref);
      drawStrip();
    });
  drawStrip();

  const textarea = document.createElement('textarea');
  textarea.className = 'note-edit';
  textarea.value = split.text;
  body.appendChild(textarea);

  const editTarget = {
    input: textarea,
    push: (ref) => {
      refs = [...refs, ref];
    },
    refresh: drawStrip,
  };
  textarea.addEventListener('keydown', (event) => {
    if (handleEditorKeys(textarea, event)) {
      return;
    }
    if (event.key !== 'Enter') {
      return;
    }
    const composing = event.isComposing || event.keyCode === 229;
    const sendMode = state.settings.enterBehavior === 'send';
    const wantSave = sendMode ? !event.shiftKey : event.shiftKey;
    if (wantSave) {
      event.preventDefault();
      if (!composing) {
        commit();
      }
      return;
    }
    handleEditorEnter(textarea, event);
  });
  textarea.addEventListener('paste', (event) => handleComposerPaste(textarea, event, editTarget));
  textarea.addEventListener('drop', (event) => {
    const files = filesFromDataTransfer(event.dataTransfer);
    if (files.length > 0) {
      event.preventDefault();
      attachFiles(files, editTarget);
    }
  });
  textarea.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  });
  textarea.addEventListener('dragenter', (event) => {
    event.preventDefault();
  });

  const actions = document.createElement('div');
  actions.className = 'note-edit-actions';

  const cancel = document.createElement('button');
  cancel.className = 'btn';
  cancel.textContent = '취소';
  cancel.addEventListener('click', async () => {
    if (!(await endEdit())) {
      body.innerHTML = originalHtml;
    }
  });

  const commit = async () => {
    const content = joinAttachments(textarea.value, refs);
    const blobIds = collectBlobIds(content);
    await updateNote(note.id, { content, blobIds, editedAt: Date.now() });
    for (const ref of split.refs) {
      if (!blobIds.includes(ref.id)) {
        await decrementRefCount(ref.id);
      }
    }
    showToast('저장했습니다');
    await endEdit();
    await renderNotes();
    syncSoon();
  };

  const save = document.createElement('button');
  save.className = 'btn primary';
  save.textContent = '저장';
  save.addEventListener('click', commit);

  actions.append(cancel, save);
  body.appendChild(actions);

  textarea.focus();
  textarea.setSelectionRange(textarea.value.length, textarea.value.length);
};

const sendNote = async () => {
  const input = $('#note-input');
  const typed = input.value;
  const refs = state.draftRefs;
  if (!typed.trim() && refs.length === 0) {
    return;
  }

  const content = joinAttachments(typed, refs);
  const blobIds = collectBlobIds(content);
  let folderId = state.folderId;
  if (folderId == null) {
    folderId = await defaultFolderId();
  }

  input.value = '';
  state.draftRefs = [];
  await renderDraftChips();
  autoResize(input);

  const tempNote = {
    id: `temp-${Date.now()}`,
    folderId,
    content,
    blobIds,
    pinned: false,
    createdAt: Date.now(),
    sourceUrl: null,
    sourceTitle: null,
  };
  await renderNotes(tempNote);

  try {
    await createNote({ folderId, content, blobIds });
    await renderNotes(null, true);
    syncSoon();
  } catch (err) {
    console.error('sendNote failed', err);
    input.value = typed;
    state.draftRefs = refs;
    await renderDraftChips();
    autoResize(input);
    showToast('메모 저장에 실패했습니다');
    await renderNotes();
  }
};

const defaultFolderId = async () => {
  const current = state.settings.defaultFolderId;
  if (current != null) {
    const folder = await getFolder(current);
    if (folder && folder.deletedAt == null) {
      return current;
    }
  }
  const fallback = await ensureInbox();
  state.settings.defaultFolderId = fallback;
  await saveSettings();
  return fallback;
};

const handleCapture = async (message) => {
  const folderId = await defaultFolderId();
  let content = '';
  if (message.kind === 'selection' && message.text) {
    const quoted = String(message.text)
      .split('\n')
      .map((line) => `> ${line}`)
      .join('\n');
    const source = [message.title, message.url].filter(Boolean).join(' ');
    if (source) {
      content = `${quoted}\n\n> 출처: ${source}`;
    } else {
      content = quoted;
    }
  } else {
    content = [message.title || '제목 없는 페이지', message.url || ''].join('\n');
  }

  await createNote({
    folderId,
    content,
    sourceUrl: message.url || null,
    sourceTitle: message.title || null,
  });

  if (state.view === 'notes') {
    await renderNotes();
  }
  showToast('메모로 저장했습니다');
  syncSoon();

  try {
    const { pendingCapture } = await chrome.storage.session.get('pendingCapture');
    if (pendingCapture && pendingCapture.at === message.at) {
      await chrome.storage.session.remove('pendingCapture');
    }
  } catch (err) {
    console.debug('pendingCapture cleanup skipped', err);
  }
};

const processPendingCapture = async () => {
  try {
    const { pendingCapture } = await chrome.storage.session.get('pendingCapture');
    if (!pendingCapture) {
      return;
    }
    if (Date.now() - (pendingCapture.at || 0) > PENDING_CAPTURE_MAX_AGE_MS) {
      await chrome.storage.session.remove('pendingCapture');
      return;
    }
    await chrome.storage.session.remove('pendingCapture');
    await handleCapture(pendingCapture);
  } catch (err) {
    console.debug('pendingCapture skipped', err);
  }
};

const autoResize = (input) => {
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
};

const replaceRange = (input, start, end, text, selectStart = null, selectEnd = null) => {
  input.focus();
  input.setRangeText(text, start, end, 'end');
  if (selectStart != null) {
    input.setSelectionRange(selectStart, selectEnd == null ? selectStart : selectEnd);
  }
  input.dispatchEvent(new Event('input', { bubbles: true }));
  autoResize(input);
};

const insertTextAtCursor = (input, text) => {
  replaceRange(input, input.selectionStart, input.selectionEnd, text);
};

const toggleWrap = (input, marker) => {
  const start = input.selectionStart;
  const end = input.selectionEnd;
  const value = input.value;
  const selected = value.slice(start, end);
  const before = value.slice(Math.max(0, start - marker.length), start);
  const after = value.slice(end, end + marker.length);

  if (before === marker && after === marker) {
    replaceRange(
      input,
      start - marker.length,
      end + marker.length,
      selected,
      start - marker.length,
      end - marker.length
    );
    return;
  }
  replaceRange(
    input,
    start,
    end,
    marker + selected + marker,
    start + marker.length,
    end + marker.length
  );
};

const insertLink = (input) => {
  const start = input.selectionStart;
  const end = input.selectionEnd;
  const selected = input.value.slice(start, end) || '링크 텍스트';
  const text = `[${selected}](url)`;
  const urlStart = start + selected.length + 3;
  replaceRange(input, start, end, text, urlStart, urlStart + 3);
};

const indentLines = (input, direction) => {
  const start = input.selectionStart;
  const end = input.selectionEnd;
  const value = input.value;

  const lineStart = value.lastIndexOf('\n', start - 1) + 1;
  let lineEnd = value.indexOf('\n', end);
  if (lineEnd === -1) {
    lineEnd = value.length;
  }

  const lines = value.slice(lineStart, lineEnd).split('\n');
  let updated = null;
  if (direction > 0) {
    updated = lines.map((line) => `  ${line}`);
  } else {
    updated = lines.map((line) => line.replace(/^ {1,2}/, ''));
  }
  const block = updated.join('\n');
  const delta = block.length - (lineEnd - lineStart);

  let shiftStart = delta;
  let shiftEnd = delta;
  if (direction > 0) {
    shiftStart = 2;
    shiftEnd = 2 * lines.length;
  }
  const limit = value.length + delta;
  const nextStart = Math.max(lineStart, Math.min(limit, start + shiftStart));
  const nextEnd = Math.max(lineStart, Math.min(limit, end + shiftEnd));
  replaceRange(input, lineStart, lineEnd, block, nextStart, nextEnd);
};

const handleListEnter = (input) => {
  const caret = input.selectionStart;
  if (caret !== input.selectionEnd) {
    return false;
  }
  const value = input.value;
  const lineStart = value.lastIndexOf('\n', caret - 1) + 1;
  const line = value.slice(lineStart, caret);
  const match = line.match(/^(\s*)([-*+]|\d+[.)])\s(.*)$/);
  if (!match) {
    return false;
  }

  const [, indent, marker, rest] = match;
  if (rest.trim() === '') {
    replaceRange(input, lineStart, caret, '');
    return true;
  }

  let nextMarker = marker;
  const numbered = marker.match(/^(\d+)([.)])$/);
  if (numbered) {
    nextMarker = `${parseInt(numbered[1], 10) + 1}${numbered[2]}`;
  }
  replaceRange(input, caret, caret, `\n${indent}${nextMarker} `);
  return true;
};

const filesFromDataTransfer = (dataTransfer) => {
  if (!dataTransfer) {
    return [];
  }
  const files = Array.from(dataTransfer.files || []);
  if (files.length > 0) {
    return files;
  }
  return Array.from(dataTransfer.items || [])
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter(Boolean);
};

const storeAttachment = async (file, status) => {
  let isVideo = (file.type || '').startsWith('video/');
  let isImage = (file.type || '').startsWith('image/');
  let loop = false;

  if (isImage && (await isAnimatedImage(file))) {
    isImage = false;
    isVideo = true;
    loop = true;
  }

  let source = file;
  if (isVideo && state.settings.compressVideo !== false) {
    try {
      source = await compressVideo(file, (percent) => {
        status.textContent = `영상 압축 중... ${percent}% (파일 크기에 따라 시간이 걸립니다)`;
      });
    } catch (err) {
      console.warn('video compression skipped', err);
      status.textContent = '압축을 건너뛰고 원본으로 저장합니다';
      source = file;
    }
  }

  let id = null;
  try {
    if (isImage) {
      id = (await processImageFile(source)).id;
    } else {
      id = (await processBinaryFile(source)).id;
    }
    if (loop) {
      await db.blobs.update(id, { loop: true });
    }
  } catch (err) {
    if (!isImage) {
      throw err;
    }
    console.warn('image decode failed, storing as-is', file.name, err);
    isImage = false;
    id = (await processBinaryFile(source)).id;
  }

  const label = (file.name || `file-${id}`).replace(/[[\]]/g, '');
  if (isImage) {
    return { id, md: `![](blob:${id})` };
  }
  if (isVideo) {
    return { id, md: `![${label}](blob:${id})` };
  }
  return { id, md: `[${label}](blob:${id})` };
};

const attachFiles = async (files, target = null) => {
  const status = $('#input-status');
  const input = target ? target.input : $('#note-input');
  const push = target ? target.push : (ref) => state.draftRefs.push(ref);
  const refresh = target ? target.refresh : renderDraftChips;
  status.classList.remove('hidden');
  let done = 0;
  let skipped = 0;
  let lastProblem = '';

  for (const [index, file] of [...files].entries()) {
    status.textContent = `첨부 저장 중... (${index + 1}/${files.length})`;
    if (file.size > MAX_ATTACH_BYTES) {
      const name = file.name || '파일';
      lastProblem = `${name}: ${formatBytes(MAX_ATTACH_BYTES)}를 넘어 건너뜁니다`;
      skipped++;
      continue;
    }
    try {
      push(await storeAttachment(file, status));
      done++;
    } catch (err) {
      console.error('attachFiles failed', file && file.name, err);
      lastProblem = '첨부 저장에 실패했습니다';
      skipped++;
    }
  }

  await refresh();
  input.focus();
  status.textContent = skipped > 0 ? lastProblem : `첨부 ${done}개 저장됨`;
  setTimeout(() => status.classList.add('hidden'), skipped > 0 ? 2500 : 1500);
};

const handleEditorKeys = (input, event) => {
  const mod = event.metaKey || event.ctrlKey;

  if (mod && !event.shiftKey && event.key.toLowerCase() === 'b') {
    event.preventDefault();
    toggleWrap(input, '**');
    return true;
  }
  if (mod && !event.shiftKey && event.key.toLowerCase() === 'i') {
    event.preventDefault();
    toggleWrap(input, '*');
    return true;
  }
  if (mod && !event.shiftKey && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    insertLink(input);
    return true;
  }
  if (event.key === 'Escape') {
    input.blur();
    return true;
  }
  if (event.key === 'Tab') {
    if (!input.value) {
      return true;
    }
    event.preventDefault();
    indentLines(input, event.shiftKey ? -1 : 1);
    return true;
  }
  return false;
};

const handleEditorEnter = (input, event) => {
  if (event.key !== 'Enter') {
    return;
  }
  if (event.isComposing || event.keyCode === 229) {
    return;
  }
  if (handleListEnter(input)) {
    event.preventDefault();
  }
};

const handleComposerKeydown = (input, event) => {
  if (handleEditorKeys(input, event)) {
    return;
  }
  if (event.key !== 'Enter') {
    return;
  }

  const composing = event.isComposing || event.keyCode === 229;
  const sendMode = state.settings.enterBehavior === 'send';
  const wantSend = sendMode ? !event.shiftKey : event.shiftKey;

  if (wantSend) {
    event.preventDefault();
    if (!composing) {
      sendNote();
    }
    return;
  }
  handleEditorEnter(input, event);
};

const handleComposerPaste = (input, event, target = null) => {
  const dataTransfer = event.clipboardData;
  if (!dataTransfer) {
    return;
  }
  const files = filesFromDataTransfer(dataTransfer);
  const images = files.filter((file) => (file.type || '').startsWith('image/'));
  const text = dataTransfer.getData('text/plain') || '';

  if (images.length > 0) {
    event.preventDefault();
    attachFiles(images, target);
    return;
  }
  if (files.length > 0 && !text.trim()) {
    event.preventDefault();
    attachFiles(files, target);
    return;
  }

  const trimmed = text.trim();
  if (!/^https?:\/\/\S+$/.test(trimmed)) {
    return;
  }
  const start = input.selectionStart;
  const end = input.selectionEnd;
  if (end <= start) {
    return;
  }
  event.preventDefault();
  const selected = input.value.slice(start, end);
  insertTextAtCursor(input, `[${selected}](${trimmed})`);
};

const bindInputEvents = () => {
  const input = $('#note-input');

  input.addEventListener('input', () => autoResize(input));
  input.addEventListener('keydown', (event) => handleComposerKeydown(input, event));
  input.addEventListener('paste', (event) => handleComposerPaste(input, event));

  input.addEventListener('drop', (event) => {
    const files = filesFromDataTransfer(event.dataTransfer);
    if (files.length > 0) {
      event.preventDefault();
      attachFiles(files);
    }
  });
  input.addEventListener('dragover', (event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  });
  input.addEventListener('dragenter', (event) => {
    event.preventDefault();
  });
};

const settingRow = (label, control) => {
  const row = document.createElement('div');
  row.className = 'setting-row';
  const labelEl = document.createElement('label');
  labelEl.textContent = label;
  row.appendChild(labelEl);
  row.appendChild(control);
  return row;
};

const selectEl = (options, value, onChange) => {
  const select = document.createElement('select');
  for (const option of options) {
    const element = document.createElement('option');
    element.value = String(option.value);
    element.textContent = option.label;
    select.appendChild(element);
  }
  select.value = String(value);
  if (select.selectedIndex < 0) {
    select.selectedIndex = 0;
  }
  select.addEventListener('change', () => onChange(select.value));
  return select;
};

const usageRow = (label, value) => {
  const row = document.createElement('div');
  row.className = 'usage-row';
  const labelEl = document.createElement('span');
  labelEl.textContent = label;
  const valueEl = document.createElement('span');
  valueEl.className = 'val';
  valueEl.textContent = value;
  row.append(labelEl, valueEl);
  return row;
};

const renderRemoteUsage = (section, conf) => {
  const remote = document.createElement('div');
  remote.className = 'remote-usage';

  const title = document.createElement('div');
  title.className = 'remote-usage-title';
  title.textContent = `서버 (Supabase ${sbPlan(conf).label})`;
  remote.appendChild(title);

  const loading = document.createElement('div');
  loading.className = 'usage-row';
  loading.textContent = '불러오는 중...';
  remote.appendChild(loading);
  section.appendChild(remote);

  const quotaRow = (label, used, limit, extra) => {
    let percent = 0;
    if (limit > 0) {
      percent = Math.min(100, (used / limit) * 100);
    }
    const bar = document.createElement('div');
    bar.className = 'usage-bar';
    const fill = document.createElement('div');
    fill.className = 'usage-bar-fill';
    fill.style.width = `${percent}%`;
    if (percent >= 80) {
      fill.classList.add('warn');
    }
    bar.appendChild(fill);

    let percentText = '0%';
    if (percent > 0 && percent < 1) {
      percentText = '<1%';
    } else if (percent >= 1) {
      percentText = `${Math.round(percent)}%`;
    }
    const label2 = extra ? `${label} (${extra})` : label;
    const row = usageRow(label2, `${formatBytes(used)} / ${formatBytes(limit)} · ${percentText}`);
    remote.append(row, bar);
  };

  sbUsage(conf).then((stats) => {
    remote.removeChild(loading);
    if (!stats) {
      remote.appendChild(usageRow('조회 함수가 없습니다. 다시 연결하면 만들어집니다.', ''));
      return;
    }
    const plan = sbPlan(conf);
    let noteBytes = stats.db_bytes || 0;
    if (stats.notes_bytes != null) {
      noteBytes = stats.notes_bytes;
    }
    quotaRow('데이터베이스', noteBytes, plan.db, `메모 ${stats.note_count || 0}`);
    quotaRow(
      '첨부 저장소',
      stats.storage_bytes || 0,
      plan.storage,
      `파일 ${stats.storage_count || 0}`
    );
  });
};

const renderStorageSection = async () => {
  const stats = await getUsageStats();
  const section = document.createElement('div');
  section.className = 'settings-section';

  const heading = document.createElement('h3');
  heading.textContent = '저장 공간';
  section.appendChild(heading);

  const bar = document.createElement('div');
  bar.className = 'usage-bar';
  const fill = document.createElement('div');
  fill.className = 'usage-bar-fill';
  let percent = 0;
  if (stats.quota > 0) {
    percent = Math.min(100, (stats.usage / stats.quota) * 100);
  }
  fill.style.width = `${Math.max(2, percent)}%`;
  bar.appendChild(fill);
  section.appendChild(bar);

  const total = document.createElement('div');
  total.className = 'usage-total';
  total.textContent = `${formatBytes(stats.usage)} 사용 중`;
  section.appendChild(total);

  section.appendChild(usageRow(`텍스트 메모 (${stats.noteCount})`, formatBytes(stats.textBytes)));
  section.appendChild(usageRow(`이미지 (${stats.imageCount})`, formatBytes(stats.imageBytes)));
  section.appendChild(usageRow(`동영상 (${stats.videoCount})`, formatBytes(stats.videoBytes)));
  section.appendChild(usageRow(`파일 (${stats.fileCount})`, formatBytes(stats.fileBytes)));
  section.appendChild(usageRow('썸네일', formatBytes(stats.thumbBytes)));

  const trashRow = document.createElement('div');
  trashRow.className = 'usage-row trash-row';
  const trashLabel = document.createElement('span');
  trashLabel.textContent = `휴지통 (${stats.trashNoteCount})`;
  const trashValue = document.createElement('span');
  trashValue.className = 'val';
  trashValue.textContent = formatBytes(stats.trashBytes);
  const emptyBtn = document.createElement('button');
  emptyBtn.className = 'trash-empty-btn';
  emptyBtn.textContent = '비우기';
  emptyBtn.addEventListener('click', emptyTrashDialog);
  trashRow.append(trashLabel, trashValue, emptyBtn);
  section.appendChild(trashRow);

  const saved = document.createElement('div');
  saved.className = 'usage-saved';
  const savedText = document.createElement('span');
  savedText.textContent = '중복 제거로 아낀 용량 ';
  const savedValue = document.createElement('strong');
  savedValue.textContent = formatBytes(stats.savedBytes);
  saved.append(savedText, savedValue);
  section.appendChild(saved);

  const actions = document.createElement('div');
  actions.className = 'settings-actions';
  const exportBtn = document.createElement('button');
  exportBtn.className = 'btn primary';
  exportBtn.textContent = '내보내기';
  exportBtn.addEventListener('click', doExport);
  const importBtn = document.createElement('button');
  importBtn.className = 'btn';
  importBtn.textContent = '가져오기';
  importBtn.addEventListener('click', doImport);
  const cleanupBtn = document.createElement('button');
  cleanupBtn.className = 'btn';
  cleanupBtn.textContent = '정리';
  cleanupBtn.addEventListener('click', doCleanup);
  actions.append(exportBtn, importBtn, cleanupBtn);
  section.appendChild(actions);

  if (state.settings.lastExportAt) {
    const last = document.createElement('div');
    last.className = 'last-export';
    last.textContent = `마지막 내보내기: ${daysAgoLabel(state.settings.lastExportAt)}`;
    section.appendChild(last);
  }

  const conf = syncConf();
  if (conf) {
    renderRemoteUsage(section, conf);
  }
  return section;
};

const renderGeneralSection = async () => {
  const section = document.createElement('div');
  section.className = 'settings-section';
  const heading = document.createElement('h3');
  heading.textContent = '일반';
  section.appendChild(heading);

  const folders = await getActiveFolders();
  const currentDefault = await defaultFolderId();
  section.appendChild(
    settingRow(
      '기본 폴더',
      selectEl(
        folders.map((folder) => ({ value: folder.id, label: folder.name })),
        currentDefault,
        async (value) => {
          state.settings.defaultFolderId = Number(value);
          await saveSettings();
        }
      )
    )
  );

  section.appendChild(
    settingRow(
      'Enter 키',
      selectEl(
        [
          { value: 'send', label: 'Enter: 전송' },
          { value: 'newline', label: 'Enter: 줄바꿈' },
        ],
        state.settings.enterBehavior,
        async (value) => {
          state.settings.enterBehavior = value;
          await saveSettings();
        }
      )
    )
  );

  section.appendChild(
    settingRow(
      '영상 압축',
      selectEl(
        [
          { value: 'on', label: '켬 (720p)' },
          { value: 'off', label: '끔 (원본)' },
        ],
        state.settings.compressVideo === false ? 'off' : 'on',
        async (value) => {
          state.settings.compressVideo = value === 'on';
          await saveSettings();
        }
      )
    )
  );

  section.appendChild(
    settingRow(
      '테마',
      selectEl(
        [
          { value: 'system', label: '시스템' },
          { value: 'light', label: '라이트' },
          { value: 'dark', label: '다크' },
        ],
        state.settings.theme,
        async (value) => {
          state.settings.theme = value;
          await saveSettings();
          applyTheme();
        }
      )
    )
  );

  return section;
};

const renderSyncSection = () => {
  const section = document.createElement('div');
  section.className = 'settings-section';
  const heading = document.createElement('h3');
  heading.textContent = '동기화';
  section.appendChild(heading);

  const status = document.createElement('div');
  status.className = 'sync-status';
  const setStatus = (message, kind) => {
    status.textContent = message || '';
    status.className = kind ? `sync-status ${kind}` : 'sync-status';
  };

  const conf = state.settings.sb;
  if (!conf || !conf.url || !conf.key) {
    const help = document.createElement('p');
    help.className = 'sync-help';
    help.textContent = 'Supabase 액세스 토큰을 넣으면 프로젝트와 테이블을 자동으로 준비합니다. 다른 PC에서 같은 토큰을 넣으면 같은 곳에 연결됩니다.';
    section.appendChild(help);

    const row = document.createElement('div');
    row.className = 'sync-connect-row';
    const input = document.createElement('input');
    input.type = 'password';
    input.placeholder = 'sbp_ 로 시작하는 토큰';
    input.autocomplete = 'off';
    const button = document.createElement('button');
    button.className = 'btn primary';
    button.textContent = '연결';

    const connect = async () => {
      const token = input.value.trim();
      if (!token) {
        setStatus('토큰을 입력해 주세요.', 'error');
        return;
      }
      button.disabled = true;
      input.disabled = true;
      try {
        const { dbPass, ...provisioned } = await sbProvision(token, (message) =>
          setStatus(message)
        );
        state.settings.sb = { ...provisioned, enabled: true };
        await saveSettings();
        rtConnect();
        if (dbPass) {
          await showDialog({
            title: '데이터베이스 비밀번호',
            message:
              '방금 만든 Supabase 프로젝트의 Postgres 비밀번호입니다. 지금 한 번만 보여주고 어디에도 저장하지 않습니다. 필요하면 지금 복사해 두세요. 잃어버려도 Supabase 대시보드에서 재설정할 수 있습니다.',
            input: { value: dbPass },
            buttons: [{ label: '확인', value: 'ok', primary: true }],
          });
        }
        setStatus('연결됐습니다. 첫 동기화 중...');
        await syncNow((message) => setStatus(message));
        await renderSettings();
      } catch (err) {
        console.error('connect failed', err);
        setStatus(String(err.message || err), 'error');
        button.disabled = false;
        input.disabled = false;
      }
    };

    button.addEventListener('click', connect);
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        connect();
      }
    });

    row.append(input, button);
    section.append(row, status);
    return section;
  }

  const enabled = conf.enabled !== false;
  section.appendChild(
    settingRow(
      '동기화',
      selectEl(
        [
          { value: 'on', label: '켜짐' },
          { value: 'off', label: '꺼짐' },
        ],
        enabled ? 'on' : 'off',
        async (value) => {
          state.settings.sb.enabled = value === 'on';
          await saveSettings();
          if (state.settings.sb.enabled) {
            rtConnect();
          } else {
            rtStop();
          }
          await renderSettings();
        }
      )
    )
  );

  section.appendChild(
    settingRow(
      '요금제',
      selectEl(
        Object.entries(SB_PLANS).map(([value, plan]) => ({ value, label: plan.label })),
        conf.plan || 'free',
        async (value) => {
          state.settings.sb.plan = value;
          await saveSettings();
          await renderSettings();
        }
      )
    )
  );

  const lastSynced = conf.lastSyncedAt ? timeLabel(conf.lastSyncedAt) : '아직 없음';
  section.appendChild(usageRow('마지막 동기화', lastSynced));

  if (conf.lastError) {
    setStatus(conf.lastError, 'error');
  }

  const actions = document.createElement('div');
  actions.className = 'settings-actions';

  const syncBtn = document.createElement('button');
  syncBtn.className = 'btn primary';
  syncBtn.textContent = '지금 동기화';
  syncBtn.disabled = !enabled;
  syncBtn.addEventListener('click', async () => {
    syncBtn.disabled = true;
    try {
      const result = await syncNow((message) => setStatus(message));
      if (result) {
        setStatus(`받음 ${result.pulled}건, 보냄 ${result.pushed}건`);
      } else {
        setStatus('동기화가 꺼져 있습니다.');
      }
      await renderNotes();
    } catch (err) {
      setStatus(String(err.message || err), 'error');
    }
    syncBtn.disabled = false;
  });

  const disconnectBtn = document.createElement('button');
  disconnectBtn.className = 'btn danger';
  disconnectBtn.textContent = '연결 해제';
  disconnectBtn.addEventListener('click', async () => {
    const result = await showDialog({
      title: '연결 해제',
      message: '이 PC에서 키를 지웁니다. 서버의 메모는 그대로 남습니다.',
      buttons: [
        { label: '취소', value: null },
        { label: '해제', value: 'ok', danger: true },
      ],
    });
    if (result.value !== 'ok') {
      return;
    }
    delete state.settings.sb;
    await saveSettings();
    rtStop();
    await renderSettings();
  });

  actions.append(syncBtn, disconnectBtn);
  section.append(actions, status);
  return section;
};

const renderSettings = async () => {
  const view = $('#settings-view');
  view.innerHTML = '';
  view.appendChild(await renderStorageSection());
  view.appendChild(await renderGeneralSection());
  view.appendChild(renderSyncSection());
};

const revokeAfterDownload = async (downloadId, url) => {
  let settled = false;
  const finish = () => {
    if (settled) {
      return;
    }
    settled = true;
    chrome.downloads.onChanged.removeListener(onChanged);
    clearTimeout(backstop);
    URL.revokeObjectURL(url);
  };
  const onChanged = (delta) => {
    if (delta.id === downloadId && delta.state && delta.state.current !== 'in_progress') {
      finish();
    }
  };
  const backstop = setTimeout(finish, 60 * 60 * 1000);
  chrome.downloads.onChanged.addListener(onChanged);
  const [item] = await chrome.downloads.search({ id: downloadId });
  if (!item || item.state !== 'in_progress') {
    finish();
  }
};

const doExport = async () => {
  try {
    showToast('내보내기 파일을 만드는 중...');
    const { data, name } = await exportZip();
    const blob = new Blob([data], { type: 'application/zip' });
    const url = URL.createObjectURL(blob);
    if (chrome.downloads && chrome.downloads.download) {
      const downloadId = await chrome.downloads.download({ url, filename: name, saveAs: true });
      revokeAfterDownload(downloadId, url);
    } else {
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = name;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    }
    state.settings.lastExportAt = Date.now();
    await saveSettings();
    showToast('내보내기를 마쳤습니다');
    await renderSettings();
  } catch (err) {
    console.error('doExport failed', err);
    showToast('내보내기에 실패했습니다');
  }
};

const doImport = () => {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.zip,application/zip';
  input.addEventListener('change', async () => {
    const file = input.files && input.files[0];
    if (!file) {
      return;
    }
    try {
      showToast('가져오는 중...');
      const buffer = await file.arrayBuffer();
      const result = await importFromZip(new Uint8Array(buffer));
      showToast(`가져오기 완료: 메모 ${result.notes}개, 첨부 ${result.blobs}개 (병합 ${result.reusedBlobs}개)`);
      await render();
      syncSoon();
    } catch (err) {
      console.error('doImport failed', err);
      showToast('가져오기에 실패했습니다');
    }
  });
  input.click();
};

const doCleanup = async () => {
  const result = await showDialog({
    title: '정리',
    message: '참조가 끊긴 첨부 삭제, 휴지통 비우기, 썸네일 재생성을 실행합니다. 동기화 중이면 서버의 첨부도 함께 정리합니다. 되돌릴 수 없습니다.',
    buttons: [
      { label: '취소', value: 'cancel' },
      { label: '정리하기', value: 'ok', danger: true },
    ],
  });
  if (result.value !== 'ok') {
    return;
  }
  try {
    const summary = await runFullCleanup();
    let message = `정리 완료 (끊긴 첨부 ${summary.orphans}, 휴지통 ${summary.purged}, 썸네일 ${summary.thumbs})`;
    if (syncConf()) {
      await syncNow().catch(() => {});
      const removed = await syncCleanupRemoteBlobs();
      message += `, 서버 첨부 ${removed}`;
    }
    showToast(message);
    await renderSettings();
  } catch (err) {
    console.error('doCleanup failed', err);
    showToast('정리에 실패했습니다');
  }
};

const emptyTrashDialog = async () => {
  const result = await showDialog({
    title: '휴지통을 비울까요?',
    message: '휴지통의 메모가 모두 영구 삭제됩니다. 되돌릴 수 없습니다.',
    buttons: [
      { label: '취소', value: 'cancel' },
      { label: '비우기', value: 'ok', danger: true },
    ],
  });
  if (result.value !== 'ok') {
    return;
  }
  await emptyTrash();
  showToast('휴지통을 비웠습니다');
  await render();
  syncSoon();
};

const renderTrash = async () => {
  const view = $('#trash-view');
  view.innerHTML = '';

  const header = document.createElement('div');
  header.className = 'trash-header';
  const heading = document.createElement('h2');
  heading.textContent = '휴지통';
  const emptyBtn = document.createElement('button');
  emptyBtn.className = 'btn danger';
  emptyBtn.textContent = '휴지통 비우기';
  emptyBtn.addEventListener('click', emptyTrashDialog);
  header.append(heading, emptyBtn);
  view.appendChild(header);

  const trash = await getTrash();
  if (trash.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.textContent = '휴지통이 비어 있습니다';
    view.appendChild(empty);
    return;
  }

  const folders = await getActiveFolders();
  const folderNames = new Map(folders.map((folder) => [folder.id, folder.name]));

  for (const note of trash) {
    const item = document.createElement('div');
    item.className = 'trash-item';

    const preview = document.createElement('div');
    preview.className = 'trash-preview';
    const { text, refs } = splitAttachments(note.content);
    let summary = text.replace(/\s+/g, ' ').slice(0, 80);
    if (!summary) {
      summary = refs.length ? `첨부 ${refs.length}개` : '(빈 메모)';
    }
    preview.textContent = summary;
    item.appendChild(preview);

    const meta = document.createElement('div');
    meta.className = 'trash-meta';
    const folderName = folderNames.get(note.folderId) || '삭제된 폴더';
    meta.textContent = `${folderName} · ${timeLabel(note.deletedAt)} 삭제`;

    const actions = document.createElement('div');
    actions.className = 'trash-actions';

    const restoreBtn = document.createElement('button');
    restoreBtn.className = 'mini-link';
    restoreBtn.textContent = '복원';
    restoreBtn.addEventListener('click', async () => {
      await restoreNote(note.id);
      showToast('복원했습니다');
      await renderTrash();
      syncSoon();
    });

    const purgeBtn = document.createElement('button');
    purgeBtn.className = 'mini-link danger';
    purgeBtn.textContent = '삭제';
    purgeBtn.addEventListener('click', async () => {
      await purgeNote(note.id);
      showToast('영구 삭제했습니다');
      await renderTrash();
    });

    actions.append(restoreBtn, purgeBtn);
    meta.appendChild(actions);
    item.appendChild(meta);
    view.appendChild(item);
  }
};

const resetSearchFilter = () => {
  state.search.kind = '';
  for (const chip of document.querySelectorAll('#search-filters .filter-chip')) {
    chip.classList.toggle('active', chip.dataset.kind === '');
  }
};

const rememberFolder = (id) => {
  state.folderId = id;
  state.settings.lastFolderId = id;
  saveSettings();
};

const closeSearch = () => {
  state.search.active = false;
  state.search.query = '';
  resetSearchFilter();
  $('#search-input').value = '';
  $('#search-bar').classList.add('hidden');
  $('#search-toggle').classList.remove('active');
};

const toggleSearch = async () => {
  state.search.active = !state.search.active;
  $('#search-bar').classList.toggle('hidden', !state.search.active);
  $('#search-toggle').classList.toggle('active', state.search.active);
  if (state.search.active) {
    state.view = 'notes';
    await render();
    $('#search-input').focus();
    return;
  }
  closeSearch();
  if (state.view === 'notes') {
    await renderNotes();
  }
};

const bindEvents = () => {
  $('#folder-btn').addEventListener('click', (event) => {
    event.stopPropagation();
    if (state.view === 'settings' || state.view === 'trash') {
      state.view = 'notes';
      render();
      return;
    }
    toggleFolderMenu();
  });

  $('#search-toggle').addEventListener('click', () => toggleSearch());

  $('#settings-btn').addEventListener('click', () => {
    state.view = 'settings';
    closeMenus();
    render();
  });

  document.querySelector('#folder-menu [data-nav="all"]').addEventListener('click', () => {
    rememberFolder(null);
    state.view = 'notes';
    closeSearch();
    closeMenus();
    render();
  });

  document.querySelector('#folder-menu [data-nav="trash"]').addEventListener('click', () => {
    state.view = 'trash';
    closeMenus();
    render();
  });

  $('#new-folder-btn').addEventListener('click', createNewFolder);
  $('#new-folder-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      createNewFolder();
    }
  });

  let searchTimer = null;
  $('#search-input').addEventListener('input', (event) => {
    state.search.query = event.target.value;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      if (state.view === 'notes') {
        renderNotes();
      }
    }, 150);
  });

  $('#search-global').addEventListener('change', (event) => {
    state.search.global = event.target.checked;
    if (state.view === 'notes') {
      renderNotes();
    }
  });

  for (const chip of document.querySelectorAll('#search-filters .filter-chip')) {
    chip.addEventListener('click', () => {
      state.search.kind = chip.dataset.kind;
      for (const other of document.querySelectorAll('#search-filters .filter-chip')) {
        other.classList.toggle('active', other === chip);
      }
      if (state.view === 'notes') {
        renderNotes();
      }
    });
  }

  $('#search-close').addEventListener('click', () => {
    if (state.search.active) {
      toggleSearch();
    }
  });

  bindInputEvents();
  $('#send-btn').addEventListener('click', sendNote);

  document.addEventListener('click', (event) => {
    if (!event.target.closest('#folder-menu') && !event.target.closest('#folder-btn')) {
      $('#folder-menu').classList.add('hidden');
    }
    if (!event.target.closest('#note-menu') && !event.target.closest('.note-more')) {
      $('#note-menu').classList.add('hidden');
    }
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeMenus();
    }
  });

  $('#onboarding-ok').addEventListener('click', () => {
    $('#onboarding').classList.add('hidden');
    state.settings.onboarded = true;
    saveSettings();
  });

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || message.type !== 'capture') {
      return undefined;
    }
    handleCapture(message)
      .then(() => sendResponse({ ok: true }))
      .catch((err) => sendResponse({ ok: false, error: String(err) }));
    return true;
  });
};

const bindSyncTriggers = () => {
  syncNow().catch(() => {});
  rtConnect();

  window.addEventListener('focus', () => {
    const conf = state.settings.sb;
    if (!conf) {
      return;
    }
    if (!rtAlive()) {
      rtConnect();
    }
    if (Date.now() - (conf.lastSyncedAt || 0) > 60000) {
      syncNow().catch(() => {});
    }
  });

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      syncNow().catch(() => {});
    }
  });
};

const init = async () => {
  await loadSettings();
  const inboxId = await ensureInbox();
  if (state.settings.defaultFolderId == null) {
    state.settings.defaultFolderId = inboxId;
    await saveSettings();
  }

  const remembered = state.settings.lastFolderId;
  if (remembered != null) {
    const folder = await getFolder(remembered);
    state.folderId = folder && folder.deletedAt == null ? remembered : null;
  }

  applyTheme();
  bindEvents();
  trackScroll();
  await cleanupOldTrash(TRASH_KEEP_DAYS);
  await render();
  await processPendingCapture();

  if (navigator.storage && navigator.storage.persist) {
    navigator.storage.persist().catch(() => {});
  }
  if (!state.settings.onboarded) {
    $('#onboarding').classList.remove('hidden');
  }

  bindSyncTriggers();
};

document.addEventListener('DOMContentLoaded', init);
