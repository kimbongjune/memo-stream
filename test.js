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

console.log('ok — 모든 체크 통과');
