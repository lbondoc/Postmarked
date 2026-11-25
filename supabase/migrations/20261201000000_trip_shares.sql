-- Share links: anyone with the link sees a trip read-only, with no sign-in (SPEC §7).
-- The `share` Edge Function looks tokens up with the service role; through the API only the person who made a
-- link can read its token. Links are made and revoked through the two functions below, never by direct writes.

create extension if not exists pgcrypto with schema extensions;

create table public.trip_shares (
  -- 128 random bits in base64url (22 characters, no padding).
  token text primary key check (token ~ '^[A-Za-z0-9_-]{22}$'),
  trip_id uuid not null references public.trips on delete cascade,
  created_by uuid not null references public.profiles on delete cascade,
  created_at timestamptz not null default now(),
  revoked_at timestamptz
);
-- At most one live link per trip.
create unique index trip_shares_one_live on public.trip_shares (trip_id) where revoked_at is null;

alter table public.trip_shares enable row level security;

create policy "the creator reads their links" on public.trip_shares
  for select to authenticated using (created_by = auth.uid());

revoke all on public.trip_shares from anon, authenticated;
grant select on public.trip_shares to authenticated;

-- Turns the trip's share link on, replacing any live one with a new token, and returns the token.
create function public.create_share(p_trip uuid) returns text
language plpgsql security definer set search_path = '' as $$
declare
  v_token text := rtrim(translate(encode(extensions.gen_random_bytes(16), 'base64'), '+/', '-_'), '=');
begin
  if public.my_trip_role(p_trip) is distinct from 'owner' and public.my_trip_role(p_trip) is distinct from 'editor' then
    raise exception 'Only the owner and editors can share the trip' using errcode = '42501';
  end if;
  update public.trip_shares set revoked_at = now() where trip_id = p_trip and revoked_at is null;
  insert into public.trip_shares (token, trip_id, created_by) values (v_token, p_trip, auth.uid());
  return v_token;
end $$;

-- Turns the trip's share link off. Any editor can, whoever made the link.
create function public.revoke_share(p_trip uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if public.my_trip_role(p_trip) is distinct from 'owner' and public.my_trip_role(p_trip) is distinct from 'editor' then
    raise exception 'Only the owner and editors can stop sharing the trip' using errcode = '42501';
  end if;
  update public.trip_shares set revoked_at = now() where trip_id = p_trip and revoked_at is null;
end $$;

-- Whether the trip has a live share link, for its members (the token itself stays with its creator).
create function public.share_is_on(p_trip uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select public.my_trip_role(p_trip) is not null
     and exists (select 1 from public.trip_shares where trip_id = p_trip and revoked_at is null)
$$;

revoke execute on function public.create_share(uuid), public.revoke_share(uuid), public.share_is_on(uuid) from public, anon;
grant execute on function public.create_share(uuid), public.revoke_share(uuid), public.share_is_on(uuid) to authenticated;
