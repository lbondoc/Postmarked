-- MVP milestone 6: songs on a trip. Stored as links plus what the link preview said, so any
-- member can open them in their own music app; nothing is streamed through Postmarked.

create table public.songs (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references public.trips on delete cascade,
  added_by uuid default auth.uid() references public.profiles on delete set null,
  provider text not null check (provider in ('spotify')),
  url text not null check (url ~ '^https://open\.spotify\.com/(track|album|playlist|episode)/[A-Za-z0-9]{22}$'),
  title text not null check (char_length(btrim(title)) between 1 and 200),
  artist text check (char_length(artist) <= 200),
  artwork_url text check (artwork_url ~ '^https://[a-z0-9.-]+\.(scdn\.co|spotifycdn\.com)/'),
  -- The day of the trip it goes with; null for the whole trip.
  day date,
  created_at timestamptz not null default now()
);
create index songs_trip on public.songs (trip_id, created_at);

alter table public.songs enable row level security;

create policy "members see songs" on public.songs
  for select to authenticated using (public.my_trip_role(trip_id) is not null);
create policy "owner and editors add songs" on public.songs
  for insert to authenticated
  with check (public.my_trip_role(trip_id) in ('owner', 'editor') and added_by = auth.uid());
create policy "adder or owner removes a song" on public.songs
  for delete to authenticated
  using (added_by = auth.uid() or public.my_trip_role(trip_id) = 'owner');

revoke all on public.songs from anon, authenticated;
grant select, delete on public.songs to authenticated;
grant insert (trip_id, provider, url, title, artist, artwork_url, day) on public.songs to authenticated;
