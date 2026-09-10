/* 회귀 확인. `node test.js`
 *
 * 브라우저 없이 검증할 수 있는 것만 본다. 정규식과 판정 규칙은 파일에서 직접
 * 읽어 비교하고, eval은 쓰지 않는다.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const marked = require('./lib/marked.min.js');

const read = (path) => fs.readFileSync(path, 'utf8');
const sources = {
  db: read('db.js'),
  markdown: read('markdown.js'),
  sidepanel: read('sidepanel.js'),
  storage: read('storage.js'),
  supabase: read('supabase.js'),
  sync: read('sync.js'),
  offscreen: read('offscreen.js'),
  video: read('video.js'),
  html: read('sidepanel.html'),
};

/* --- 1. 첨부 렌더 --- */

const parser = new marked.Marked({ gfm: true, breaks: true });
const REF_RE = /(?:src|href)="blob:(\d+)"/g;

const imageHtml = parser.parse('![](blob:3) ');
const linkHtml = parser.parse('[report.pdf](blob:7)');
assert.deepStrictEqual([...imageHtml.matchAll(REF_RE)].map((m) => m[1]), ['3'], '이미지 참조 수집');
assert.deepStrictEqual([...linkHtml.matchAll(REF_RE)].map((m) => m[1]), ['7'], '첨부 링크 참조 수집');

// DOMPurify 기본값은 blob 스킴을 자른다. 이것이 이미지가 빈 칸으로 보이던 원인이었다.
const PURIFY_DEFAULT =
  /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|sms|cid|xmpp):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;
const objectUrl = 'blob:chrome-extension://abcdef/1234-5678';
assert.ok(!PURIFY_DEFAULT.test(objectUrl), '전제: 기본 정책은 blob을 거른다');
assert.ok(/xmpp\|blob\)/.test(sources.markdown), 'blob 스킴을 허용 목록에 넣어야 한다');
assert.ok(!/javascript/i.test(sources.markdown.match(/const BLOB_URI_OK[^;]+;/)[0]), 'javascript 스킴은 허용하지 않는다');

// 동영상은 이미지 문법으로 저장하고 렌더에서 video로 바꾼다.
const videoHtml = parser.parse('![clip.mp4](blob:5)');
assert.ok(/<img[^>]*src="blob:5"[^>]*>/.test(videoHtml), '전제: 동영상도 img 태그로 나온다');
const swapped = videoHtml.replace(new RegExp('<img[^>]*src="blob:5"[^>]*>', 'g'), '<video></video>');
assert.ok(swapped.includes('<video') && !swapped.includes('<img'), '동영상 참조가 재생기로 바뀐다');
const mixed = parser.parse('![](blob:3)\n\n![clip.mp4](blob:5)');
const onlyVideo = mixed.replace(new RegExp('<img[^>]*src="blob:5"[^>]*>', 'g'), '<video></video>');
assert.ok(onlyVideo.includes('src="blob:3"'), '다른 참조는 건드리지 않는다');

// 저장 파일명. 붙여넣은 이미지에는 이름이 없다.
const fileNameOf = (record, id) => {
  if (record.name) {
    return record.name;
  }
  const mime = record.mime || 'application/octet-stream';
  return `memo-${id}.${mime.split('/')[1].split('+')[0]}`;
};
assert.strictEqual(fileNameOf({ mime: 'image/webp', name: '' }, 3), 'memo-3.webp', '이름이 없으면 mime으로');
assert.strictEqual(fileNameOf({ mime: 'video/mp4', name: 'clip.mp4' }, 5), 'clip.mp4', '이름이 있으면 그대로');
assert.strictEqual(fileNameOf({ mime: 'image/svg+xml', name: '' }, 9), 'memo-9.svg', 'svg+xml 처리');
// 이미지는 webp로 변환해 저장하므로 확장자를 맞춰야 한다.
const webpName = (name) => (name ? `${name.replace(/\.[^.]+$/, '')}.webp` : '');
assert.strictEqual(webpName('photo.png'), 'photo.webp', '확장자가 실제 바이트와 맞아야 한다');

/* --- 2. Enter 키 --- */

const route = ({ enterBehavior, shiftKey = false, composing = false }) => {
  const wantSend = enterBehavior === 'send' ? !shiftKey : shiftKey;
  if (wantSend) {
    return composing ? 'swallow' : 'send';
  }
  return composing ? 'ime' : 'newline';
};
assert.strictEqual(route({ enterBehavior: 'send' }), 'send', '전송 모드의 Enter');
assert.strictEqual(route({ enterBehavior: 'send', shiftKey: true }), 'newline', '전송 모드의 Shift+Enter');
assert.strictEqual(route({ enterBehavior: 'send', composing: true }), 'swallow', '조합 중에는 줄바꿈을 만들지 않는다');
assert.strictEqual(route({ enterBehavior: 'newline' }), 'newline', '줄바꿈 모드의 Enter');
assert.strictEqual(route({ enterBehavior: 'newline', shiftKey: true }), 'send', '줄바꿈 모드의 Shift+Enter');

const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s(.*)$/;
assert.ok(LIST_RE.test('- 항목'), '목록 줄 인식');
assert.ok(LIST_RE.test('  2) 항목'), '번호 목록 인식');
assert.ok(!LIST_RE.test('-항목'), '공백이 없으면 목록이 아니다');

/* --- 3. 첨부 분리와 합치기 --- */

const ATTACH_RE = /!?\[[^\]]*\]\(blob:(\d+)\)[ \t]*/g;
assert.ok(sources.sidepanel.includes(String(ATTACH_RE.source)), '본문의 정규식과 같아야 한다');

const splitAttachments = (content) => {
  const refs = [];
  const stripped = (content || '').replace(ATTACH_RE, (match, id) => {
    refs.push({ id: Number(id), md: match.trim() });
    return '';
  });
  const text = stripped.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim();
  return { text, refs };
};
const joinAttachments = (text, refs) => {
  const body = (text || '').trim();
  const tail = (refs || []).map((ref) => ref.md).join(' ');
  if (!tail) {
    return body;
  }
  return body ? `${body}\n\n${tail}` : tail;
};

let split = splitAttachments('회의 메모\n\n![](blob:3) [a.pdf](blob:7)');
assert.strictEqual(split.text, '회의 메모', '본문에 첨부 토큰이 남으면 안 된다');
assert.deepStrictEqual(split.refs.map((r) => r.id), [3, 7], '이미지와 파일 둘 다 잡는다');
assert.strictEqual(
  joinAttachments(split.text, split.refs),
  '회의 메모\n\n![](blob:3) [a.pdf](blob:7)',
  '분리한 뒤 다시 합치면 원래대로'
);
split = splitAttachments('![](blob:3)');
assert.strictEqual(split.text, '', '첨부만 있는 메모');
assert.strictEqual(joinAttachments('', split.refs), '![](blob:3)', '본문 없이 첨부만 전송');
split = splitAttachments('일반 [링크](https://example.com) 는 그대로');
assert.strictEqual(split.refs.length, 0, '일반 링크를 첨부로 잡으면 안 된다');

const IMAGE_REF_RE = /!\[[^\]]*\]\(blob:(\d+)\)/g;
assert.deepStrictEqual(
  [...'![](blob:3) [a.pdf](blob:7)'.matchAll(IMAGE_REF_RE)].map((m) => Number(m[1])),
  [3],
  '복사할 때 이미지 문법만 고른다'
);

/* --- 4. 첨부 종류 필터 --- */

assert.ok(sources.db.includes('const filterNotesByMedia'), 'filterNotesByMedia가 있어야 한다');
const mimes = new Map([[1, 'image/webp'], [2, 'video/mp4'], [3, 'application/pdf'], [4, 'image/svg+xml']]);
const filterByMedia = (notes, kind) => {
  if (!kind) {
    return notes;
  }
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
const notes = [
  { id: 'a', blobIds: [1] },
  { id: 'b', blobIds: [2] },
  { id: 'c', blobIds: [3] },
  { id: 'd', blobIds: [] },
  { id: 'e', blobIds: [1, 2] },
  { id: 'f', blobIds: [99] },
];
const filteredIds = (kind) => filterByMedia(notes, kind).map((note) => note.id);
assert.deepStrictEqual(filteredIds('image'), ['a', 'e'], '사진');
assert.deepStrictEqual(filteredIds('video'), ['b', 'e'], '동영상');
assert.deepStrictEqual(filteredIds('file'), ['c'], '파일에서 사진과 동영상은 빠진다');
assert.deepStrictEqual(filteredIds(''), notes.map((n) => n.id), '전체는 그대로 통과');
assert.ok(!filteredIds('file').includes('f'), '없는 첨부 참조를 파일로 세면 안 된다');

/* --- 5. 동기화 --- */

assert.ok(sources.sync.includes('const syncPull'), 'syncPull이 있어야 한다');
assert.ok(sources.sync.includes('const syncPush'), 'syncPush가 있어야 한다');

// 로컬 id와 sha 사이 치환. 닫는 괄호까지 넣어야 blob:3 이 blob:30 을 건드리지 않는다.
const toSha = (content, id, sha) => content.split(`blob:${id})`).join(`blob:${sha})`);
const toLocal = (content, sha, id) => content.split(`blob:${sha})`).join(`blob:${id})`);
const SHA = 'aa11bb22';
const sample = '![](blob:3) 그리고 ![](blob:30)';
assert.strictEqual(toSha(sample, 3, SHA), `![](blob:${SHA}) 그리고 ![](blob:30)`, 'blob:3 이 blob:30 을 건드리면 안 된다');
assert.strictEqual(toLocal(toSha(sample, 3, SHA), SHA, 3), sample, '왕복해도 원래대로');
assert.ok(
  sources.sync.includes('blob:${id})') && sources.sync.includes('blob:${meta.sha})'),
  '괄호를 뺀 치환이 남아 있으면 안 된다'
);

const rowVersion = (row) => row.updatedAt || row.editedAt || row.createdAt || 0;
assert.strictEqual(rowVersion({ createdAt: 100 }), 100, 'updatedAt이 없으면 createdAt');
assert.strictEqual(rowVersion({ createdAt: 100, editedAt: 200 }), 200, 'editedAt이 우선');
assert.strictEqual(rowVersion({ createdAt: 100, editedAt: 200, updatedAt: 300 }), 300, 'updatedAt이 최우선');
const remoteWins = (local, remoteUpdatedAt) => remoteUpdatedAt > rowVersion(local);
assert.ok(remoteWins({ updatedAt: 100 }, 200), '원격이 최신이면 덮어쓴다');
assert.ok(!remoteWins({ updatedAt: 300 }, 200), '로컬이 최신이면 유지');
assert.ok(!remoteWins({ updatedAt: 200 }, 200), '같으면 다시 쓰지 않는다');

// 변경 함수가 updatedAt을 갱신하지 않으면 그 변경은 서버로 올라가지 않는다.
for (const name of [
  'renameFolder',
  'setFolderPinned',
  'softDeleteFolder',
  'updateNote',
  'setNotePinned',
  'moveNoteToFolder',
  'softDeleteNote',
  'restoreNote',
]) {
  const start = sources.db.indexOf(`const ${name} = `);
  assert.ok(start > 0, `${name} 가 있어야 한다`);
  const body = sources.db.slice(start, sources.db.indexOf('\n\n', start));
  assert.ok(body.includes('touch('), `${name} 가 updatedAt을 갱신해야 한다`);
}

/* --- 6. 영구 삭제 전파 --- */

assert.ok(sources.db.includes('notePurgedHook(note)'), 'purgeNote가 표식 훅을 불러야 한다');
assert.ok(sources.sync.includes('notePurgedHook = '), 'sync가 훅을 채워야 한다');
assert.ok(sources.sync.includes("method: 'DELETE'"), '서버에서도 지워야 한다');
assert.ok(sources.supabase.includes('create table if not exists purges'), '표식 테이블이 있어야 한다');
assert.ok(sources.supabase.includes('alter table purges  enable row level security'), 'purges도 잠근다');
assert.ok(sources.sync.includes("sbUpsert(conf, 'purges'"), '영구 삭제 시 표식을 남긴다');
assert.ok(sources.sync.includes('/purges?purged_at=gt.'), '받을 때 표식을 확인한다');
// 목록은 연결 해제와 무관하게 남아야 재연결 시 되돌아오지 않는다.
assert.ok(sources.sync.includes('state.settings.purgeQueue'), '삭제 목록은 settings 최상위에 둔다');
assert.ok(!sources.sync.includes('conf.purgeQueue'), '목록을 sb 안에 두면 연결 해제 때 사라진다');

// 순서: 표식 → 폴더/메모 받기 → 보내기
const iPurge = sources.sync.indexOf('syncPurges(conf');
const iPull = sources.sync.indexOf('const pulled = await syncPull');
const iPush = sources.sync.indexOf('const pushed = await syncPush');
assert.ok(iPurge > 0 && iPurge < iPull && iPull < iPush, '삭제 → 받기 → 보내기 순서');

// 표식 처리에서 purgeNote를 부르면 두 PC가 표식을 주고받으며 끝나지 않는다.
const tombStart = sources.sync.indexOf('const syncPullTombstones');
const tombBlock = sources.sync.slice(tombStart, sources.sync.indexOf('\n};', tombStart));
assert.ok(tombBlock.includes('db.notes.delete'), '표식 처리는 로컬에서 직접 지운다');
assert.ok(!tombBlock.includes('purgeNote('), 'purgeNote를 부르면 표식이 무한히 오간다');
assert.ok(tombBlock.includes('decrementRefCount'), '첨부 참조도 함께 줄인다');

/* --- 7. 영상 압축 --- */

const manifest = JSON.parse(read('manifest.json'));
assert.ok(manifest.permissions.includes('offscreen'), 'offscreen 권한이 필요하다');
assert.ok(
  /wasm-unsafe-eval/.test(manifest.content_security_policy.extension_pages),
  'wasm-unsafe-eval이 없으면 ffmpeg가 뜨지 않는다'
);
for (const file of [
  'lib/ffmpeg/ffmpeg.js',
  'lib/ffmpeg/814.ffmpeg.js',
  'lib/ffmpeg/ffmpeg-core.js',
  'lib/ffmpeg/ffmpeg-core.wasm',
]) {
  assert.ok(fs.existsSync(file), `${file} 가 있어야 한다`);
}
assert.ok(sources.video.includes('URL.createObjectURL(file)'), '파일은 object URL로 넘긴다');
// classWorkerURL을 넘기면 모듈 워커가 되고 importScripts가 없어 코어 로딩이 실패한다.
assert.ok(!sources.offscreen.includes('classWorkerURL:'), 'classWorkerURL을 넘기면 코어를 읽지 못한다');

// scale 필터는 폭과 높이 둘 다 짝수로 내려야 한다. 실제 ffmpeg로 확인한 규칙이다.
const videoFilter = /'-vf', `([^`]+)`/.exec(sources.offscreen)[1];
assert.ok(/trunc\(min\(1,\$\{maxHeight\}\/ih\)\*iw\/2\)\*2/.test(videoFilter), '폭을 짝수로 내린다');
assert.ok(
  /trunc\(min\(\$\{maxHeight\},ih\)\/2\)\*2/.test(videoFilter),
  '높이도 짝수로 내려야 405p 원본에서 x264가 받는다'
);

/* --- 8. 코딩 규약 --- */

// 주석에 적어 둔 설명까지 규약 위반으로 잡히지 않도록 코드만 남긴다.
const stripComments = (src) =>
  src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

const jsFiles = fs.readdirSync('.').filter((name) => name.endsWith('.js') && name !== 'test.js');
for (const file of jsFiles) {
  const src = stripComments(read(file));
  assert.ok(!/\bdocument\.execCommand\b/.test(src), `${file}: execCommand는 폐기 대상이다`);
  assert.ok(!/\beval\s*\(/.test(src), `${file}: eval을 쓰지 않는다`);
  assert.ok(!/^\s*\/\*\*$/m.test(read(file)), `${file}: JSDoc 대신 일반 주석을 쓴다`);
  // 중괄호 없는 한 줄 분기
  const inlineBlock = src
    .split('\n')
    .findIndex((line) => /^\s*(if|for|while)\s*\(.*\)\s*[A-Za-z_$][^{]*$/.test(line));
  assert.strictEqual(inlineBlock, -1, `${file}:${inlineBlock + 1} 한 줄 분기는 쓰지 않는다`);
}
assert.ok(sources.sidepanel.includes('setRangeText'), 'execCommand 대체로 setRangeText를 쓴다');
assert.ok(sources.markdown.includes('class Markdown'), 'md는 객체 리터럴이 아니라 클래스여야 한다');

/* --- 8.5 첨부 참조 수명주기 ---
 *
 * 첨부는 refCount로 수명을 관리한다. 어느 한 곳이라도 규칙을 어기면 조용히
 * 데이터가 사라지거나 용량이 샌다. 실제로 겪은 것들만 못 박아 둔다. */

// 함수 본문만 떼어 본다. 파일 전체에서 찾으면 다른 함수의 코드에 걸린다.
const fnBody = (src, name) => {
  const start = src.indexOf(`const ${name} = `);
  assert.notStrictEqual(start, -1, `${name} 선언을 찾지 못했다`);
  const end = src.indexOf('\n};', start);
  assert.notStrictEqual(end, -1, `${name} 의 끝을 찾지 못했다`);
  return src.slice(start, end);
};

// 동기화가 기존 첨부를 재사용할 때 참조를 올리지 않으면, 한쪽 메모를 영구
// 삭제할 때 다른 메모의 첨부까지 지워진다.
assert.ok(
  /if \(existing\)[\s\S]*incrementRefCount/.test(fnBody(sources.sync, 'ensureLocalBlob')),
  '재사용하는 첨부는 참조 수를 올려야 한다'
);

// 원격 내용으로 덮어쓸 때 옛 첨부의 참조를 놓지 않으면 반대로 샌다.
assert.ok(
  /db\.notes\.update[\s\S]*decrementRefCount/.test(fnBody(sources.sync, 'syncPullNotes')),
  '덮어쓴 메모의 옛 첨부 참조를 놓아야 한다'
);

// 로컬 첨부가 사라진 메모를 그냥 보내면 본문의 blob:<로컬id>가 그대로 올라가
// 다른 PC에서 엉뚱한 첨부를 가리킨다.
assert.ok(
  /!record \|\| !record\.blob[\s\S]*content = content\.replace/.test(
    fnBody(sources.sync, 'syncPushBlobs')
  ),
  '첨부를 잃은 참조는 보내기 전에 본문에서 지워야 한다'
);

// refCount<=0인 레코드를 찾는 정리는 아무것도 못 지운다. decrementRefCount가
// 0이 되는 순간 이미 delete하므로 그런 레코드는 존재할 수 없다.
const cleanupBody = fnBody(sources.storage, 'cleanupOrphanBlobs');
assert.ok(!/refCount \|\| 0\) <= 0/.test(cleanupBody), '정리는 refCount<=0을 기준으로 삼으면 안 된다');
assert.ok(/note\.blobIds/.test(cleanupBody), '정리는 메모의 실제 참조를 세야 한다');

// blobIds가 비고 본문만 blob:N을 쓰는 메모가 있다. 예전 마크다운 볼트 가져오기가
// 그렇게 만들었다. 정리가 본문을 안 보면 그 메모의 이미지를 전부 지운다.
assert.ok(/collectBlobIds/.test(cleanupBody), '정리는 본문의 참조도 세야 한다');
assert.ok(
  !/blobIds: \[\],/.test(read('backup.js')),
  '가져오기는 본문에 넣은 참조를 blobIds에도 남겨야 한다'
);

// 내보내기 파일명은 한 번만 짓는다. 부르는 자리마다 새로 지으면 usedNames가
// 자라서 zip에 담은 이름과 .md의 참조가 어긋난다.
const backupSrc = read('backup.js');
assert.ok(/assetNames\.set\(/.test(backupSrc), '에셋 이름을 미리 확정해야 한다');
assert.ok(!/assetName\(record\)/.test(backupSrc), '이름 짓기를 두 번 부르면 안 된다');

/* --- 9. 하드코딩한 문구 ---
 *
 * 다국어를 걷어내면서 문구를 코드에 그대로 넣었다. 되살아나지 않게 막아 둔다. */

for (const file of [...jsFiles, 'sidepanel.html', 'manifest.json']) {
  const src = read(file);
  assert.ok(!/__MSG_/.test(src), `${file}: __MSG_ 참조가 남아 있다`);
  assert.ok(!/\bdata-i18n\b/.test(src), `${file}: data-i18n 속성이 남아 있다`);
  assert.ok(!/\bi18n\.(load|get|locale)\b/.test(src), `${file}: i18n 객체를 아직 쓴다`);
}
assert.ok(!fs.existsSync('_locales'), '_locales 는 없어야 한다');
assert.ok(!fs.existsSync('i18n.js'), 'i18n.js 는 없어야 한다');
assert.ok(manifest.default_locale === undefined, 'default_locale 은 없어야 한다');
assert.ok(/[가-힣]/.test(manifest.name), 'manifest 이름이 실제 문구여야 한다');

/* --- 10. 동기화 실제 동작 ---
 *
 * 여기까지는 소스를 정규식으로 훑는 검사뿐이라, uid 마이그레이션처럼 기본키가
 * 통째로 바뀌어도 전부 통과했다. 아래는 sync.js를 가짜 db/REST 위에서 실제로
 * 돌려 본다. */

const loadSync = (env) => {
  const names = Object.keys(env);
  const factory = new Function(
    ...names,
    sources.sync + '\nreturn { syncPull, syncPush, syncPullNotes, rowVersion };'
  );
  return factory(...names.map((name) => env[name]));
};

const fakeTable = (rows = []) => {
  let nextId = rows.reduce((max, row) => Math.max(max, row.id || 0), 0) + 1;
  return {
    rows,
    toArray: async () => rows.map((row) => ({ ...row })),
    get: async (id) => rows.find((row) => row.id === id),
    add: async (row) => {
      const id = nextId++;
      rows.push({ ...row, id });
      return id;
    },
    update: async (id, patch) => {
      const target = rows.find((row) => row.id === id);
      Object.assign(target, patch);
      return 1;
    },
    delete: async (id) => {
      rows.splice(rows.findIndex((row) => row.id === id), 1);
    },
    where: (field) => ({
      equals: (value) => ({
        first: async () => rows.find((row) => row[field] === value),
        toArray: async () => rows.filter((row) => row[field] === value),
      }),
    }),
  };
};

const syncEnv = ({ notes = [], folders = [], blobs = [], download, rest }) => {
  const db = { notes: fakeTable(notes), folders: fakeTable(folders), blobs: fakeTable(blobs) };
  const upserts = [];
  return {
    db,
    upserts,
    env: {
      db,
      state: { settings: { sb: {}, purgeQueue: [] }, view: 'settings' },
      saveSettings: async () => {},
      renderNotes: async () => {},
      ensureInbox: async () => 1,
      notePurgedHook: null,
      console: { warn() {}, error() {}, debug() {} },
      formatBytes: () => '0 B',
      SB_BUCKET: 'memo',
      sbHeaders: () => ({}),
      fetch: async () => ({ ok: true, json: async () => [] }),
      sbRest: async (conf, path) => (rest ? rest(path) : []),
      sbUpsert: async (conf, table, rows) => {
        upserts.push({ table, rows });
        return null;
      },
      sbObjectExists: async () => true,
      sbUpload: async () => {},
      sbDownload: download || (async () => new Blob([1])),
      getBlob: async (id) => db.blobs.rows.find((row) => row.id === id),
      findBlobBySha256: async (sha) => db.blobs.rows.find((row) => row.sha256 === sha),
      incrementRefCount: async () => {},
      decrementRefCount: async () => {},
    },
  };
};

const remoteNote = (over = {}) => ({
  uid: 'note-a',
  folder_uid: null,
  content: '본문',
  blobs: [],
  source_url: null,
  source_title: null,
  pinned: false,
  created_at: 1000,
  edited_at: null,
  deleted_at: null,
  updated_at: 5000,
  ...over,
});

(async () => {
  /* 첨부를 받지 못하면 워터마크를 올리면 안 된다. 올리면 updated_at=gt.<워터마크>
   * 쿼리에서 그 행이 영영 빠져 첨부가 이 기기에서 사라진다. */
  {
    const { db, env } = syncEnv({
      download: async () => {
        throw new Error('offline');
      },
    });
    const sync = loadSync(env);
    const row = remoteNote({ blobs: [{ sha: 'deadbeef', mime: 'image/webp', size: 1 }] });
    const result = await sync.syncPullNotes({}, [row], [], 0, () => {});

    assert.strictEqual(result.watermark, 0, '첨부 실패 행은 워터마크를 올리지 않는다');
    assert.strictEqual(db.notes.rows.length, 1, '본문은 그래도 저장한다');
    assert.strictEqual(db.notes.rows[0].pullPending, true, '재시도 표식을 남긴다');
  }

  /* 다음 회차에 같은 행이 다시 와도 건너뛰지 않고 재시도해야 한다. */
  {
    const { db, env } = syncEnv({
      notes: [{ id: 1, uid: 'note-a', updatedAt: 5000, pullPending: true, blobIds: [] }],
    });
    const sync = loadSync(env);
    const row = remoteNote({ blobs: [{ sha: 'deadbeef', mime: 'image/webp', size: 1 }] });
    const result = await sync.syncPullNotes({}, [row], [], 0, () => {});

    assert.strictEqual(result.changed, 1, 'pullPending 행은 버전이 같아도 다시 처리한다');
    assert.strictEqual(db.notes.rows[0].pullPending, null, '이번엔 받았으니 표식을 지운다');
    assert.strictEqual(result.watermark, 5000, '성공했으니 워터마크를 올린다');
  }

  /* 첨부가 빠진 행을 올리면 원격 blobs 목록을 빈 배열로 덮어써서
   * 다른 PC에서도 첨부가 사라진다. push에서 빼야 한다. */
  {
    const { env, upserts } = syncEnv({
      notes: [
        { id: 1, uid: 'ok', createdAt: 1, updatedAt: 100, blobIds: [], content: '' },
        { id: 2, uid: 'stalled', createdAt: 2, updatedAt: 100, blobIds: [], pullPending: true },
      ],
    });
    const sync = loadSync(env);
    await sync.syncPush({ lastPushAt: 0 }, () => {});

    const noteUpsert = upserts.find((item) => item.table === 'notes');
    assert.deepStrictEqual(
      noteUpsert.rows.map((row) => row.uid),
      ['ok'],
      'pullPending 메모는 올리지 않는다'
    );
  }

  /* 워터마크는 테이블마다 따로 간다. notes.updated_at은 글 쓴 기기 시계,
   * purges.purged_at은 삭제를 올린 기기 시계라 하나로 묶으면 어긋난다. */
  {
    const { env } = syncEnv({
      rest: (path) => {
        if (path.startsWith('/notes')) return [remoteNote({ updated_at: 9000 })];
        return [];
      },
    });
    const sync = loadSync(env);
    const conf = {};
    await sync.syncPull(conf, () => {});

    assert.strictEqual(conf.lastPullNotes, 9000, 'notes 워터마크는 올라간다');
    assert.strictEqual(conf.lastPullPurges, 0, 'purges 워터마크는 notes에 끌려가지 않는다');
    assert.strictEqual(conf.lastPullAt, undefined, '합쳐 쓰던 워터마크는 더 이상 없다');
  }

  /* uid가 기본키다. 밀리초를 키로 쓰면 같은 배치에 중복이 생겨
   * ON CONFLICT가 21000으로 죽고 동기화가 영구히 멈췄다. */
  assert.ok(/on_conflict=uid/.test(sources.supabase), 'upsert 충돌 기준은 uid다');
  assert.ok(
    !/on_conflict=created_at/.test(sources.supabase),
    'created_at을 충돌 기준으로 쓰면 안 된다'
  );
  assert.ok(/uid\s+text primary key/.test(sources.supabase), '원격 기본키는 uid다');
  for (const table of ['folders', 'notes']) {
    assert.ok(
      new RegExp(`create table if not exists ${table} \\(\\s*\\n\\s*uid`).test(sources.supabase),
      `${table} 는 uid로 시작해야 한다`
    );
  }

  const loadHash = () => {
    const stored = [];
    const factory = new Function(
      'db',
      'findBlobBySha256',
      'createImageBitmap',
      'document',
      read('hash.js') + '\nreturn { isAnimatedImage, processImageFile };'
    );
    const api = factory(
      {
        blobs: {
          add: async (rec) => {
            stored.push(rec);
            return stored.length;
          },
          update: async () => {},
          get: async () => null,
        },
      },
      async () => null,
      () => {
        throw new Error('createImageBitmap 을 부르면 첫 프레임만 남는다');
      },
      {}
    );
    return { stored, api };
  };

  const fakeFile = (type, head = []) => {
    const bytes = new Uint8Array(head);
    return {
      type,
      name: 'x',
      size: bytes.length,
      arrayBuffer: async () => bytes.buffer,
      slice: () => ({ arrayBuffer: async () => bytes.buffer }),
    };
  };

  const webpHead = (animated) => {
    const bytes = new Array(21).fill(0);
    'RIFF'.split('').forEach((c, i) => (bytes[i] = c.charCodeAt(0)));
    'WEBP'.split('').forEach((c, i) => (bytes[8 + i] = c.charCodeAt(0)));
    'VP8X'.split('').forEach((c, i) => (bytes[12 + i] = c.charCodeAt(0)));
    bytes[20] = animated ? 0x02 : 0x00;
    return bytes;
  };

  {
    const { api } = loadHash();
    const gif = (frames) => {
      const bytes = [];
      'GIF89a'.split('').forEach((c) => bytes.push(c.charCodeAt(0)));
      for (let n = 0; n < frames; n++) {
        bytes.push(0x21, 0xf9, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00);
      }
      return bytes;
    };

    assert.strictEqual(
      await api.isAnimatedImage(fakeFile('image/gif', gif(3))),
      true,
      '여러 프레임 GIF 는 움직이는 이미지다'
    );
    assert.strictEqual(
      await api.isAnimatedImage(fakeFile('image/gif', gif(1))),
      false,
      '한 프레임 GIF 는 평소대로 webp 로 줄인다'
    );
    assert.strictEqual(
      await api.isAnimatedImage(fakeFile('image/webp', webpHead(true))),
      true,
      '움직이는 WebP 를 잡는다'
    );
    assert.strictEqual(
      await api.isAnimatedImage(fakeFile('image/webp', webpHead(false))),
      false,
      '정지 WebP 는 평소대로 변환'
    );
    assert.strictEqual(
      await api.isAnimatedImage(fakeFile('image/png')),
      false,
      'PNG 는 평소대로 변환'
    );
  }

  {
    const panelSrc = read('sidepanel.js');
    const body = fnBody(panelSrc, 'storeAttachment');
    assert.ok(
      /isAnimatedImage\(file\)[\s\S]*isVideo = true/.test(body),
      '움직이는 이미지는 영상 경로로 보내 ffmpeg 로 돌린다'
    );
    assert.ok(
      /loop: true/.test(body),
      'GIF 에서 온 영상은 loop 로 표시해 둔다'
    );
    const mdSrc = read('markdown.js');
    assert.ok(
/loop autoplay muted playsinline/.test(mdSrc),
      'loop 영상은 컨트롤 없이 자동 반복 재생한다'
    );
    for (const attr of ['loop', 'autoplay', 'muted', 'playsinline']) {
      assert.ok(
        new RegExp(`ADD_ATTR:[^\\]]*'${attr}'`).test(mdSrc),
        `DOMPurify 가 ${attr} 를 지우면 안 된다`
      );
    }
    assert.ok(
      /'-pix_fmt', 'yuv420p'/.test(read('offscreen.js')),
      'GIF 변환 결과가 어디서나 재생되려면 yuv420p 가 필요하다'
    );
  }

  {
    const offscreen = read('offscreen.js');
    assert.ok(!/'in'|'out\.mp4'/.test(offscreen), 'ffmpeg 파일명을 고정하면 안 된다');
    assert.ok(/runExclusive/.test(offscreen), '압축 작업은 한 번에 하나만 돈다');
  }

  {
    const panel = read('sidepanel.js');
    assert.ok(
      /chrome\.downloads\.onChanged/.test(panel),
      '내보내기 URL 은 다운로드가 끝난 뒤 revoke 한다'
    );
    assert.ok(
      !/setTimeout\(\(\) => URL\.revokeObjectURL\(url\), 10000\);\n\s*state\.settings\.lastExportAt/.test(
        panel
      ),
      '10초 타이머로 revoke 하면 큰 파일이 끊긴다'
    );
    assert.ok(/NOTE_PAGE_SIZE/.test(panel), '메모 목록은 페이지 단위로 그린다');
    assert.ok(
      /if \(!input\.value\) \{\n\s*return;/.test(panel),
      '빈 작성창에서는 Tab 이 포커스를 넘겨야 한다'
    );
    assert.ok(/select\.selectedIndex < 0/.test(panel), 'option 이 없으면 빈칸으로 두지 않는다');
  }

  {
    const backupSrcAsync = read('backup.js');
    assert.ok(!/fflate\.(zip|unzip)Sync/.test(backupSrcAsync), 'zip 은 동기로 돌리지 않는다');
    assert.ok(/fflate\.zip\(/.test(backupSrcAsync), 'fflate 비동기 API 를 쓴다');
  }

  {
    const syncSrc = read('sync.js');
    assert.ok(!/conf\.uploaded/.test(syncSrc), 'uploaded 목록을 settings 에 쌓지 않는다');
    assert.ok(
      !/blobs=cs\./.test(syncSrc),
      '원격 첨부 정리는 오브젝트마다 쿼리하지 않는다'
    );
  }

  assert.ok(
    /where\('deletedAt'\)/.test(sources.db),
    'deletedAt 인덱스를 만들었으면 휴지통 조회에 써야 한다'
  );
  assert.ok(
    manifest.action.default_title === 'Memo Stream 열기',
    '툴바 제목은 실제 동작과 맞아야 한다'
  );

  {
    const panel = read('sidepanel.js');

    assert.ok(
      /addEventListener\('load', apply/.test(panel),
      '이미지가 늦게 로드되면 높이가 늘어나므로 그때 다시 맨 아래로 붙어야 한다'
    );
    const mdText = read('markdown.js');
    assert.strictEqual(
      (mdText.match(/URL\.createObjectURL/g) || []).length,
      1,
      '렌더마다 새 URL 을 만들면 같은 사진이 매번 다시 로드돼 깜빡인다'
    );
    assert.ok(/blobUrlCache\.get\(key\)/.test(mdText), '첨부 URL 은 blob 별로 캐시해 재사용한다');
    for (const fn of ['renderNoteBubble', 'renderAttachChips']) {
      assert.ok(
        !/createObjectURL/.test(fnBody(panel, fn)),
        `${fn} 이 따로 URL 을 만들면 캐시를 우회해 또 깜빡인다`
      );
    }
    assert.ok(
      !/main\.scrollTop = previousTop/.test(panel),
      '숨겨진 목록의 scrollTop 은 0 으로 눌리므로 그 값을 복원하면 안 된다'
    );
    assert.ok(
      /main\.addEventListener\('scroll'/.test(panel),
      '맨 아래에 있었는지는 렌더 순간이 아니라 스크롤 이벤트로 계속 추적한다'
    );
    assert.ok(
      /await renderNotes\(null, true\)/.test(fnBody(panel, 'sendNote')),
      '메모를 보내면 맨 아래로 내려가야 한다'
    );
    assert.ok(
      !/scrollKey = JSON\.stringify\(\[[^\]]*query/.test(panel),
      '검색어 한 글자마다 스크롤을 움직이면 안 된다'
    );
    assert.strictEqual(
      (panel.match(/search-toggle'\)\.classList\.remove\('active'\)/g) || []).length,
      1,
      '검색 닫기는 closeSearch 한 곳에서만 처리한다'
    );
    assert.ok(
      (panel.match(/closeSearch\(\)/g) || []).length >= 3,
      '검색을 닫는 모든 경로가 closeSearch 를 거쳐야 한다'
    );

    const mdSrc = read('markdown.js');
    assert.ok(
      /width="\$\{record\.width\}" height="\$\{record\.height\}"/.test(mdSrc),
      '이미지 크기를 미리 박아야 로드되며 레이아웃이 튀지 않는다'
    );
  }

  {
    const panel = read('sidepanel.js');
    const body = fnBody(panel, 'renderNotes');

    assert.ok(
      !/innerHTML/.test(body),
      '목록을 먼저 비우면 await 사이마다 빈 화면이 그려지고 스크롤이 0으로 튄다'
    );
    assert.ok(
      /replaceChildren\(frame\)/.test(body),
      '화면 밖에서 다 만든 뒤 한 번에 갈아끼워야 한다'
    );
    assert.strictEqual(
      (body.match(/list\.(appendChild|innerHTML|replaceChildren|append)/g) || []).length,
      1,
      '살아 있는 목록을 건드리는 지점은 갈아끼우는 한 번뿐이어야 한다'
    );
  }

  {
    const panel = read('sidepanel.js');
    const css = read('sidepanel.css');
    const body = fnBody(panel, 'renderNotes');

    assert.ok(
      !/scroll-behavior:\s*smooth/.test(css),
      'scroll-behavior: smooth 면 scrollTop 대입마다 화면이 애니메이션으로 굴러간다'
    );
    assert.ok(
      /bubbleCache\.get\(note\.id\)/.test(body),
      '안 바뀐 메모는 말풍선을 다시 만들지 않고 그대로 쓴다'
    );
    assert.ok(
      /folderNames\.get\(note\.folderId\)/.test(body),
      '폴더 이름이 바뀌면 캐시된 말풍선도 다시 만들어야 한다'
    );
    assert.ok(
      /const searching = active && \(needle\.length > 0/.test(body),
      '검색창만 열고 아무것도 안 쳤으면 목록이 바뀌면 안 된다'
    );
    assert.ok(
      /savedTop = \$\('#main'\)\.scrollTop/.test(fnBody(panel, 'render')),
      '설정으로 나갈 때 위치를 기억해야 돌아왔을 때 그 자리다'
    );
  }

  console.log('ok — 모든 체크 통과');
})().catch((err) => {
  console.error(err);
  process.exit(1);
});

