-- The parts of a Supabase project the migrations rely on, recreated for a plain local
-- Postgres so the tests can run without Supabase: the auth schema, the two API roles,
-- and Supabase's habit of granting everything in `public` to those roles by default
-- (which is what makes the explicit revokes in the migrations matter).

do $$ begin
  if not exists (select from pg_roles where rolname = 'anon') then create role anon nologin; end if;
  if not exists (select from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;

create schema auth;
create schema extensions;
create schema test;
grant usage on schema public, auth, extensions, test to anon, authenticated;

create table auth.users (
  id uuid primary key default gen_random_uuid(),
  email text,
  raw_user_meta_data jsonb
);

create function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claims', true)::jsonb ->> 'sub', '')::uuid
$$;

-- Supabase Storage keeps file records here; the storage server writes owner_id itself,
-- which the default below stands in for.
create schema storage;
create table storage.buckets (
  id text primary key,
  name text not null,
  public boolean default false,
  file_size_limit bigint,
  allowed_mime_types text[]
);
create table storage.objects (
  id uuid primary key default gen_random_uuid(),
  bucket_id text references storage.buckets,
  name text not null,
  owner_id text default auth.uid()::text,
  unique (bucket_id, name)
);
alter table storage.objects enable row level security;
grant usage on schema storage to anon, authenticated;
grant select, insert, update, delete on storage.objects to anon, authenticated;

alter default privileges in schema public grant all on tables to anon, authenticated;
alter default privileges in schema public grant execute on functions to anon, authenticated;

do $$ begin
  execute format('alter database %I set search_path = public, extensions', current_database());
end $$;

-- Test helpers ------------------------------------------------------------------------

-- Act as a signed-in user (or as a signed-out visitor when p_user is null) until the transaction ends.
create function test.act_as(p_user uuid) returns void language plpgsql as $$
begin
  if p_user is null then
    perform set_config('role', 'anon', true);
    perform set_config('request.jwt.claims', '{}', true);
  else
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claims', json_build_object('sub', p_user)::text, true);
  end if;
end $$;

-- True when the statement raises an error. Runs with the caller's role, so RLS still applies.
create function test.fails(p_sql text) returns boolean language plpgsql as $$
begin
  execute p_sql;
  return false;
exception when others then
  return true;
end $$;

-- A fresh invite token for p_trip, created as p_by; leaves the caller signed in as before.
create function test.invite(p_trip uuid, p_by uuid, p_role text) returns text language plpgsql as $$
declare
  v_caller text := current_setting('request.jwt.claims', true);
  v_token text;
begin
  perform test.act_as(p_by);
  v_token := public.create_invite(p_trip, p_role::public.trip_role);
  perform set_config('request.jwt.claims', v_caller, true);
  return v_token;
end $$;

-- Number of rows a statement touched or returned.
create function test.rows(p_sql text) returns bigint language plpgsql as $$
declare n bigint;
begin
  execute p_sql;
  get diagnostics n = row_count;
  return n;
end $$;
