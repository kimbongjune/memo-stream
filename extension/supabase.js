'use strict';

const SB_MGMT = 'https://api.supabase.com/v1';
const SB_PROJECT_NAME = 'memo-stream';
const SB_REGION = 'ap-northeast-2';
const SB_BUCKET = 'memo';

const SB_SCHEMA = `
create table if not exists folders (
  uid        text primary key,
  name       text not null,
  "order"    int  default 0,
  pinned     boolean default false,
  created_at bigint not null,
  deleted_at bigint,
  updated_at bigint not null
);

create table if not exists notes (
  uid          text primary key,
  folder_uid   text,
  content      text  default '',
  blobs        jsonb default '[]'::jsonb,
  source_url   text,
  source_title text,
  pinned       boolean default false,
  created_at   bigint not null,
  edited_at    bigint,
  deleted_at   bigint,
  updated_at   bigint not null
);

-- 영구 삭제 표식. notes에서 행이 없어진 사실은 받기 쪽에서 알 수 없으므로
-- "이 uid는 영구 삭제됐다"를 따로 남긴다. 다른 PC가 이걸 보고 지운다.
create table if not exists purges (
  uid       text primary key,
  purged_at bigint not null
);

create index if not exists purges_purged_at_idx  on purges  (purged_at);
create index if not exists notes_updated_at_idx   on notes   (updated_at);
create index if not exists folders_updated_at_idx on folders (updated_at);

insert into storage.buckets (id, name, public)
values ('${SB_BUCKET}', '${SB_BUCKET}', false) on conflict (id) do nothing;

-- PostgREST로는 임의 SQL을 못 돌리므로 사용량 조회를 함수로 연다.
-- storage 스키마와 pg_database_size를 읽어야 해서 security definer가 필요하다.
create or replace function public.usage_stats()
returns json language sql security definer set search_path = public, storage as $fn$
  select json_build_object(
    'db_bytes',      pg_database_size(current_database()),
    -- 본문 바이트. 로컬의 텍스트 메모 표시와 같은 기준이라야 두 화면이 맞는다.
    -- pg_total_relation_size는 인덱스와 TOAST까지 더해서 메모 2개에 96KB가 나온다.
    'notes_bytes',   (select coalesce(sum(octet_length(content)), 0)
                        from public.notes where deleted_at is null),
    'storage_bytes', (select coalesce(sum((metadata->>'size')::bigint), 0)
                        from storage.objects where bucket_id = '${SB_BUCKET}'),
    'storage_count', (select count(*) from storage.objects where bucket_id = '${SB_BUCKET}'),
    'note_count',    (select count(*) from public.notes where deleted_at is null)
  );
$fn$;

-- definer 함수는 RLS를 타지 않으므로 anon에게 열어두면 안 된다.
revoke all on function public.usage_stats() from public, anon, authenticated;
grant execute on function public.usage_stats() to service_role;

-- 정책을 하나도 두지 않고 RLS만 켠다. anon 키로는 아무것도 열리지 않고
-- service_role 키만 통과한다.
alter table folders enable row level security;
alter table notes   enable row level security;
alter table purges  enable row level security;

alter publication supabase_realtime set table folders, notes, purges;

notify pgrst, 'reload schema';
`;

const SB_PLANS = {
  free: { label: '무료', db: 500 * 1024 * 1024, storage: 1024 * 1024 * 1024 },
  pro: { label: 'Pro', db: 8 * 1024 * 1024 * 1024, storage: 100 * 1024 * 1024 * 1024 },
};

const sbPlan = (conf) => SB_PLANS[(conf && conf.plan) || 'free'] || SB_PLANS.free;

const sbJson = async (response, label) => {
  if (!response.ok) {
    const body = (await response.text()).slice(0, 300);
    if (response.status === 401) {
      throw new Error('키가 올바르지 않습니다. 다시 확인해 주세요.');
    }
    if (response.status === 403) {
      throw new Error('토큰 권한이 부족합니다. full access(legacy) 토큰으로 다시 발급해 주세요.');
    }
    throw new Error(`${label} ${response.status}: ${body}`);
  }
  const text = await response.text();
  if (!text) {
    return null;
  }
  return JSON.parse(text);
};

const sbMgmt = async (token, path, options = {}) => {
  const response = await fetch(SB_MGMT + path, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
  });
  return sbJson(response, `Supabase ${path}`);
};

const sbProvision = async (token, onProgress = () => {}) => {
  onProgress('키 확인 중...');
  const orgs = await sbMgmt(token, '/organizations');
  if (!orgs || !orgs.length) {
    throw new Error('조직이 없습니다. Supabase에서 조직을 먼저 만들어 주세요.');
  }

  onProgress('프로젝트 확인 중...');
  const projects = (await sbMgmt(token, '/projects')) || [];
  let project = projects.find((item) => item.name === SB_PROJECT_NAME);

  let dbPass = null;
  if (!project) {
    dbPass = `${crypto.randomUUID()}aA1!`;
    onProgress('프로젝트 만드는 중... (1~2분 걸립니다)');
    project = await sbMgmt(token, '/projects', {
      method: 'POST',
      body: JSON.stringify({
        name: SB_PROJECT_NAME,
        organization_id: orgs[0].id,
        region: SB_REGION,
        db_pass: dbPass,
      }),
    });
  }

  const ref = project.id;
  let status = project.status;
  for (let attempt = 0; attempt < 90 && status !== 'ACTIVE_HEALTHY'; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 5000));
    const current = await sbMgmt(token, `/projects/${ref}`);
    status = current.status;
    onProgress(`프로젝트 준비 중... (${status})`);
  }
  if (status !== 'ACTIVE_HEALTHY') {
    throw new Error(`프로젝트가 아직 준비되지 않았습니다 (${status}). 잠시 후 다시 연결해 주세요.`);
  }

  onProgress('테이블 확인 중...');
  await sbMgmt(token, `/projects/${ref}/database/query`, {
    method: 'POST',
    body: JSON.stringify({ query: SB_SCHEMA }),
  });

  onProgress('키 받는 중...');
  const keys = (await sbMgmt(token, `/projects/${ref}/api-keys?reveal=true`)) || [];
  const serviceKey = keys.find((key) => key.name === 'service_role');
  if (!serviceKey || !serviceKey.api_key) {
    throw new Error('service_role 키를 받지 못했습니다.');
  }

  const conf = { url: `https://${ref}.supabase.co`, key: serviceKey.api_key, dbPass };

  onProgress('테이블 반영 기다리는 중...');
  let ready = false;
  for (let attempt = 0; attempt < 20 && !ready; attempt++) {
    ready = await sbRest(conf, '/notes?limit=0').then(
      () => true,
      () => false
    );
    if (!ready) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }
  if (!ready) {
    throw new Error('테이블이 아직 반영되지 않았습니다. 잠시 후 다시 연결해 주세요.');
  }

  return conf;
};

const sbHeaders = (conf, extra = {}) => ({
  apikey: conf.key,
  Authorization: `Bearer ${conf.key}`,
  ...extra,
});

const sbRest = async (conf, path, options = {}) => {
  const response = await fetch(`${conf.url}/rest/v1${path}`, {
    ...options,
    headers: sbHeaders(conf, { 'Content-Type': 'application/json', ...(options.headers || {}) }),
  });
  return sbJson(response, `REST ${path}`);
};

const sbUpsert = (conf, table, rows) => {
  if (!rows.length) {
    return Promise.resolve(null);
  }
  return sbRest(conf, `/${table}?on_conflict=uid`, {
    method: 'POST',
    headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
    body: JSON.stringify(rows),
  });
};

const sbObjectExists = async (conf, path) => {
  const response = await fetch(`${conf.url}/storage/v1/object/${SB_BUCKET}/${path}`, {
    method: 'HEAD',
    headers: sbHeaders(conf),
  });
  return response.ok;
};

const sbUpload = async (conf, path, blob) => {
  const response = await fetch(`${conf.url}/storage/v1/object/${SB_BUCKET}/${path}`, {
    method: 'POST',
    headers: sbHeaders(conf, {
      'x-upsert': 'true',
      'Content-Type': blob.type || 'application/octet-stream',
    }),
    body: blob,
  });
  if (!response.ok && response.status !== 409) {
    const body = (await response.text()).slice(0, 200);
    throw new Error(`upload ${path} ${response.status}: ${body}`);
  }
};

const sbDownload = async (conf, path) => {
  const response = await fetch(`${conf.url}/storage/v1/object/${SB_BUCKET}/${path}`, {
    headers: sbHeaders(conf),
  });
  if (!response.ok) {
    throw new Error(`download ${path} ${response.status}`);
  }
  return response.blob();
};

const sbUsage = async (conf) => {
  try {
    const stats = await sbRest(conf, '/rpc/usage_stats', { method: 'POST', body: '{}' });
    if (stats && typeof stats === 'object') {
      return stats;
    }
    return null;
  } catch (err) {
    console.warn('usage_stats unavailable', err);
    return null;
  }
};
