-- A trip's cover photo, chosen in the viewer and shown on the trip list (SPEC §3.2).

-- (id, trip_id) is unique already because id is; declaring it lets a trip point at "this media, in this trip".
alter table public.media add constraint media_id_trip_id_key unique (id, trip_id);

-- The foreign key covers both columns, so only the trip's own media can be its cover. Deleting that media
-- clears the cover and nothing else: the column list after SET NULL needs Postgres 15+ (Supabase has it).
alter table public.trips add column cover_media_id uuid;
alter table public.trips add constraint trips_cover_in_trip foreign key (cover_media_id, id)
  references public.media (id, trip_id) on delete set null (cover_media_id);

-- Only the owner and editors may update a trip (its existing RLS policy); this lets them change the cover too.
grant update (cover_media_id) on public.trips to authenticated;
