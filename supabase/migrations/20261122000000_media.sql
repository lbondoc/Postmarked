-- MVP milestone 2: photos and videos in a trip.
--
-- Files live in the private Storage bucket "media" at <trip id>/<media id>/<file>:
--   photo: original (the untouched camera file), display.jpg, thumb.jpg
--   video: video.mp4 (a copy capped at 1080p; the original stays on the phone), poster.jpg, thumb.jpg
-- A row in public.media is added only after its files are uploaded, so a row never points
-- at missing files.

create table public.media (
  -- Generated on the phone, so a retried upload reuses the same id and folder.
  id uuid primary key,
  trip_id uuid not null references public.trips on delete cascade,
  contributor uuid not null default auth.uid() references public.profiles on delete cascade,
  kind text not null check (kind in ('photo', 'video')),
  captured_at timestamptz not null,
  -- The local time zone where it was taken, when the camera recorded it (minutes east of UTC).
  utc_offset_minutes smallint check (utc_offset_minutes between -840 and 840),
  location geography(Point, 4326),
  -- Fingerprint of the original file: the same file can only be added to a trip once,
  -- even when several friends have a copy of it.
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null default now(),
  unique (trip_id, sha256)
);
create index media_trip_captured on public.media (trip_id, captured_at);

alter table public.media enable row level security;

create policy "members see the trip's media" on public.media
  for select to authenticated using (public.my_trip_role(trip_id) is not null);
create policy "owner and editors add media" on public.media
  for insert to authenticated
  with check (public.my_trip_role(trip_id) in ('owner', 'editor') and contributor = auth.uid());
create policy "members remove their own media" on public.media
  for delete to authenticated using (contributor = auth.uid());

revoke all on public.media from anon, authenticated;
grant select, delete on public.media to authenticated;
-- No contributor column here: it always defaults to the signed-in user.
grant insert (id, trip_id, kind, captured_at, utc_offset_minutes, location, sha256) on public.media to authenticated;

-- Storage -------------------------------------------------------------------------------

insert into storage.buckets (id, name, public, allowed_mime_types)
values ('media', 'media', false, array['image/*', 'video/mp4']);

-- The trip id at the start of a storage path, or null when the path is not one of ours.
create function public.trip_of_path(p_name text) returns uuid
language sql immutable set search_path = '' as $$
  select case when p_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/'
              then split_part(p_name, '/', 1)::uuid end
$$;

-- Only the file names listed at the top of this migration can be created.
create function public.is_media_path(p_name text) returns boolean
language sql immutable set search_path = '' as $$
  select p_name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(original|display\.jpg|thumb\.jpg|video\.mp4|poster\.jpg)$'
$$;

create policy "media: members read" on storage.objects
  for select to authenticated
  using (bucket_id = 'media' and public.my_trip_role(public.trip_of_path(name)) is not null);
create policy "media: owner and editors upload" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'media' and public.is_media_path(name)
              and public.my_trip_role(public.trip_of_path(name)) in ('owner', 'editor'));
-- Re-uploading a file after an interrupted upload overwrites it, which counts as an update.
create policy "media: uploader replaces own files" on storage.objects
  for update to authenticated
  using (bucket_id = 'media' and owner_id = auth.uid()::text)
  with check (bucket_id = 'media' and public.is_media_path(name)
              and public.my_trip_role(public.trip_of_path(name)) in ('owner', 'editor'));
-- The owner can also remove everyone's files, which deleting the trip needs.
create policy "media: uploader or trip owner deletes" on storage.objects
  for delete to authenticated
  using (bucket_id = 'media'
         and (owner_id = auth.uid()::text or public.my_trip_role(public.trip_of_path(name)) = 'owner'));

revoke execute on function public.trip_of_path(text), public.is_media_path(text) from public, anon;
grant execute on function public.trip_of_path(text), public.is_media_path(text) to authenticated;
