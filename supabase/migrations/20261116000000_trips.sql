-- MVP milestone 1: profiles, trips with regions, members with roles, invites.
--
-- How access works: every table has row-level security (RLS) on, so a query only ever
-- returns rows a policy allows for the signed-in user. Writes that need validation
-- (creating a trip, changing regions, invites) are only possible through the functions
-- at the bottom, never by inserting rows directly.

create extension if not exists postgis with schema extensions;

create type public.trip_role as enum ('owner', 'editor', 'viewer');

-- Tables ---------------------------------------------------------------------------

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  display_name text not null check (char_length(display_name) between 1 and 60),
  created_at timestamptz not null default now()
);

create table public.trips (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 80),
  start_date date not null,
  end_date date not null,
  -- Optional: where the drive began and ended. geography = lat/lng on the globe, distances in metres.
  start_label text,
  start_point geography(Point, 4326),
  end_label text,
  end_point geography(Point, 4326),
  theme_id text not null default 'auto_palette',
  theme_overrides jsonb not null default '{}',
  created_at timestamptz not null default now(),
  constraint trip_dates_in_order check (end_date >= start_date)
);

-- A trip's location: one or more areas (a city, a park, a state, or a hand-drawn shape).
create table public.trip_regions (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references public.trips on delete cascade,
  label text not null check (char_length(label) between 1 and 120),
  source text not null check (source in ('place', 'drawn')),
  geom geography(MultiPolygon, 4326) not null
);
create index trip_regions_trip_id on public.trip_regions (trip_id);
create index trip_regions_geom on public.trip_regions using gist (geom);

-- shortcut: if the owner deletes their account the trip is left without an owner,
-- add ownership transfer when account deletion is built.
create table public.trip_members (
  trip_id uuid not null references public.trips on delete cascade,
  user_id uuid not null references public.profiles on delete cascade,
  role public.trip_role not null,
  joined_at timestamptz not null default now(),
  primary key (trip_id, user_id)
);
create index trip_members_user_id on public.trip_members (user_id);
create unique index one_owner_per_trip on public.trip_members (trip_id) where role = 'owner';

-- Only a hash of the invite token is stored, so a database leak cannot be used to join trips.
create table public.trip_invites (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references public.trips on delete cascade,
  token_hash bytea not null unique,
  role public.trip_role not null check (role <> 'owner'),
  created_by uuid references public.profiles on delete set null,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  max_uses int check (max_uses > 0),
  use_count int not null default 0
);
create index trip_invites_trip_id on public.trip_invites (trip_id);

-- Every new account gets a profile ---------------------------------------------------

create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, left(coalesce(
    nullif(trim(new.raw_user_meta_data ->> 'name'), ''),
    nullif(split_part(new.email, '@', 1), ''),
    'Traveler'), 60));
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
for each row execute function public.handle_new_user();

-- Helpers the policies use ------------------------------------------------------------
-- "security definer" runs the function with its owner's rights, which lets a policy on
-- trip_members look at trip_members without recursing into itself.

create function public.my_trip_role(p_trip uuid) returns public.trip_role
language sql stable security definer set search_path = '' as $$
  select role from public.trip_members where trip_id = p_trip and user_id = auth.uid()
$$;

create function public.shares_trip_with(p_user uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.trip_members me
    join public.trip_members them using (trip_id)
    where me.user_id = auth.uid() and them.user_id = p_user)
$$;

-- Location columns come out of the API as unreadable binary, so these expose them as GeoJSON.
-- A function taking a table row acts as an extra column: select=label,geojson
create function public.geojson(r public.trip_regions) returns jsonb
language sql stable as $$ select extensions.st_asgeojson(r.geom)::jsonb $$;
create function public.start_geojson(t public.trips) returns jsonb
language sql stable as $$ select extensions.st_asgeojson(t.start_point)::jsonb $$;
create function public.end_geojson(t public.trips) returns jsonb
language sql stable as $$ select extensions.st_asgeojson(t.end_point)::jsonb $$;

-- Row-level security --------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.trips enable row level security;
alter table public.trip_regions enable row level security;
alter table public.trip_members enable row level security;
alter table public.trip_invites enable row level security;

create policy "see yourself and people you share a trip with" on public.profiles
  for select to authenticated using (id = auth.uid() or public.shares_trip_with(id));
create policy "edit your own profile" on public.profiles
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

create policy "members see the trip" on public.trips
  for select to authenticated using (public.my_trip_role(id) is not null);
create policy "owner and editors edit the trip" on public.trips
  for update to authenticated
  using (public.my_trip_role(id) in ('owner', 'editor'))
  with check (public.my_trip_role(id) in ('owner', 'editor'));
create policy "owner deletes the trip" on public.trips
  for delete to authenticated using (public.my_trip_role(id) = 'owner');

create policy "members see the regions" on public.trip_regions
  for select to authenticated using (public.my_trip_role(trip_id) is not null);

create policy "members see who is on the trip" on public.trip_members
  for select to authenticated using (public.my_trip_role(trip_id) is not null);
create policy "owner changes other members' roles" on public.trip_members
  for update to authenticated
  using (public.my_trip_role(trip_id) = 'owner' and user_id <> auth.uid())
  with check (public.my_trip_role(trip_id) = 'owner' and user_id <> auth.uid() and role <> 'owner');
create policy "members leave, owner removes others" on public.trip_members
  for delete to authenticated
  using ((user_id = auth.uid() and role <> 'owner')
      or (public.my_trip_role(trip_id) = 'owner' and user_id <> auth.uid()));

create policy "owner and editors see invites" on public.trip_invites
  for select to authenticated using (public.my_trip_role(trip_id) in ('owner', 'editor'));
create policy "owner and editors revoke invites" on public.trip_invites
  for delete to authenticated using (public.my_trip_role(trip_id) in ('owner', 'editor'));

-- Table privileges: start from nothing and grant exactly what the app does. RLS decides
-- which rows; these decide which operations and columns exist at all.

revoke all on public.profiles, public.trips, public.trip_regions, public.trip_members, public.trip_invites
  from anon, authenticated;
grant select on public.profiles, public.trips, public.trip_regions, public.trip_members, public.trip_invites
  to authenticated;
grant update (display_name) on public.profiles to authenticated;
grant update (name, start_date, end_date, start_label, start_point, end_label, end_point, theme_id, theme_overrides)
  on public.trips to authenticated;
grant update (role) on public.trip_members to authenticated;
grant delete on public.trips, public.trip_members, public.trip_invites to authenticated;

-- Functions the app calls ---------------------------------------------------------------

-- {"label": "...", "lat": 36.2, "lng": -121.7} -> a point, or null when not given.
create function public._point(p jsonb) returns extensions.geography
language plpgsql immutable set search_path = extensions as $$
declare
  v_lat float8 := (p ->> 'lat')::float8;
  v_lng float8 := (p ->> 'lng')::float8;
begin
  if p is null or jsonb_typeof(p) = 'null' then return null; end if;
  if v_lat is null or v_lng is null or v_lat not between -90 and 90 or v_lng not between -180 and 180 then
    raise exception 'A start or end point needs a valid lat and lng' using errcode = '22023';
  end if;
  return ST_SetSRID(ST_MakePoint(v_lng, v_lat), 4326)::geography;
end $$;

-- Replaces a trip's regions from [{"label", "source", "geometry": <GeoJSON polygon>}, ...].
create function public._replace_regions(p_trip uuid, p_regions jsonb) returns void
language plpgsql set search_path = extensions as $$
declare
  r jsonb;
  g geometry;
begin
  if jsonb_typeof(p_regions) is distinct from 'array' or jsonb_array_length(p_regions) not between 1 and 20 then
    raise exception 'A trip needs between 1 and 20 regions' using errcode = '22023';
  end if;
  delete from public.trip_regions where trip_id = p_trip;
  for r in select value from jsonb_array_elements(p_regions) loop
    -- MakeValid repairs shapes that cross themselves (easy to do when drawing with a finger);
    -- CollectionExtract(…, 3) keeps only the polygon parts.
    g := ST_CollectionExtract(ST_MakeValid(ST_GeomFromGeoJSON(r ->> 'geometry')), 3);
    if g is null or ST_IsEmpty(g) then
      raise exception 'Region "%" is not a polygon', coalesce(r ->> 'label', '?') using errcode = '22023';
    end if;
    if ST_NPoints(g) > 10000 then
      raise exception 'Region "%" is too detailed; simplify it first', r ->> 'label' using errcode = '22023';
    end if;
    if ST_XMin(g) < -180 or ST_XMax(g) > 180 or ST_YMin(g) < -90 or ST_YMax(g) > 90 then
      raise exception 'Region "%" has coordinates off the map', r ->> 'label' using errcode = '22023';
    end if;
    insert into public.trip_regions (trip_id, label, source, geom)
    values (p_trip, r ->> 'label', r ->> 'source', ST_Multi(g)::geography);
  end loop;
end $$;

-- Creates the trip, its regions and the caller's owner membership in one transaction,
-- so a trip can never exist without a location or without an owner.
create function public.create_trip(
  p_name text, p_start_date date, p_end_date date, p_regions jsonb,
  p_start jsonb default null, p_end jsonb default null
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_trip uuid;
begin
  insert into public.trips (name, start_date, end_date, start_label, start_point, end_label, end_point)
  values (trim(p_name), p_start_date, p_end_date,
          p_start ->> 'label', public._point(p_start), p_end ->> 'label', public._point(p_end))
  returning id into v_trip;
  insert into public.trip_members (trip_id, user_id, role) values (v_trip, auth.uid(), 'owner');
  perform public._replace_regions(v_trip, p_regions);
  return v_trip;
end $$;

create function public.set_trip_regions(p_trip uuid, p_regions jsonb) returns void
language plpgsql security definer set search_path = '' as $$
begin
  -- "is not true" also catches null, which is what my_trip_role returns for a non-member.
  if (public.my_trip_role(p_trip) in ('owner', 'editor')) is not true then
    raise exception 'Only the owner and editors can change the trip location' using errcode = '42501';
  end if;
  perform public._replace_regions(p_trip, p_regions);
end $$;

-- Returns the invite token. It is shown once: only its hash is kept.
create function public.create_invite(
  p_trip uuid, p_role public.trip_role default 'editor', p_days int default 14, p_max_uses int default null
) returns text
language plpgsql security definer set search_path = '' as $$
declare
  -- Two random UUIDs back to back: 244 random bits, far beyond guessing.
  v_token text := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
begin
  if (public.my_trip_role(p_trip) in ('owner', 'editor')) is not true then
    raise exception 'Only the owner and editors can invite people' using errcode = '42501';
  end if;
  if p_role = 'owner' or p_days is null or p_days not between 1 and 90 then
    raise exception 'Invites are for editors or viewers and last 1 to 90 days' using errcode = '22023';
  end if;
  insert into public.trip_invites (trip_id, token_hash, role, created_by, expires_at, max_uses)
  values (p_trip, sha256(convert_to(v_token, 'UTF8')), p_role, auth.uid(),
          now() + make_interval(days => p_days), p_max_uses);
  return v_token;
end $$;

-- Joins the trip an invite token belongs to and returns the trip id.
create function public.join_trip(p_token text) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_invite public.trip_invites;
begin
  -- "for update" locks the invite so two people cannot both take the last use.
  select * into v_invite from public.trip_invites
  where token_hash = sha256(convert_to(p_token, 'UTF8')) for update;
  if not found then
    raise exception 'This invite link is not valid' using errcode = 'P0001';
  end if;
  if exists (select 1 from public.trip_members where trip_id = v_invite.trip_id and user_id = auth.uid()) then
    return v_invite.trip_id; -- opening the link again is harmless
  end if;
  if v_invite.expires_at <= now() or v_invite.use_count >= coalesce(v_invite.max_uses, 2147483647) then
    raise exception 'This invite link is not valid' using errcode = 'P0001';
  end if;
  insert into public.trip_members (trip_id, user_id, role) values (v_invite.trip_id, auth.uid(), v_invite.role);
  update public.trip_invites set use_count = use_count + 1 where id = v_invite.id;
  return v_invite.trip_id;
end $$;

-- Function privileges: nobody by default, then signed-in users get the public ones.
revoke execute on function
  public.handle_new_user(), public.my_trip_role(uuid), public.shares_trip_with(uuid),
  public._point(jsonb), public._replace_regions(uuid, jsonb),
  public.geojson(public.trip_regions), public.start_geojson(public.trips), public.end_geojson(public.trips),
  public.create_trip(text, date, date, jsonb, jsonb, jsonb), public.set_trip_regions(uuid, jsonb),
  public.create_invite(uuid, public.trip_role, int, int), public.join_trip(text)
  from public, anon, authenticated;
grant execute on function
  public.my_trip_role(uuid), public.shares_trip_with(uuid),
  public.geojson(public.trip_regions), public.start_geojson(public.trips), public.end_geojson(public.trips),
  public.create_trip(text, date, date, jsonb, jsonb, jsonb), public.set_trip_regions(uuid, jsonb),
  public.create_invite(uuid, public.trip_role, int, int), public.join_trip(text)
  to authenticated;
