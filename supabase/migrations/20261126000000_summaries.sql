-- MVP milestone 5: colour palettes and summaries.

-- Each photo's (or video poster's) main colours: [{"c": "#F2C230", "w": 0.41}, ...], largest first.
-- The phone works it out at upload. Photos uploaded earlier get theirs filled in later by any
-- owner or editor's phone, which is the only change to a media row this allows.
alter table public.media add column palette jsonb
  check (palette is null or (jsonb_typeof(palette) = 'array' and jsonb_array_length(palette) <= 8));
grant insert (palette) on public.media to authenticated;
grant update (palette) on public.media to authenticated;
create policy "owner and editors fill in palettes" on public.media
  for update to authenticated
  using (public.my_trip_role(trip_id) in ('owner', 'editor'))
  with check (public.my_trip_role(trip_id) in ('owner', 'editor'));

create table public.summaries (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references public.trips on delete cascade,
  created_by uuid default auth.uid() references public.profiles on delete set null,
  name text not null check (char_length(btrim(name)) between 1 and 80),
  layout text not null,
  mode text not null,
  -- One entry per slot: {"media": "<id>"} or {"text": "...", "bg": "#RRGGBB"}, plus "noGrade": true to skip harmonizing.
  tiles jsonb not null check (jsonb_typeof(tiles) = 'array' and octet_length(tiles::text) <= 20000),
  -- 0 = off, 1 = full strength.
  harmonize real not null default 0 check (harmonize between 0 and 1),
  -- The colours the summary is built around, as "#RRGGBB".
  colours jsonb not null default '[]' check (jsonb_typeof(colours) = 'array' and jsonb_array_length(colours) <= 8),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index summaries_trip on public.summaries (trip_id, updated_at desc);

alter table public.summaries enable row level security;

create policy "members see summaries" on public.summaries
  for select to authenticated using (public.my_trip_role(trip_id) is not null);
create policy "owner and editors make summaries" on public.summaries
  for insert to authenticated
  with check (public.my_trip_role(trip_id) in ('owner', 'editor') and created_by = auth.uid());
-- The maker or the trip owner may change or delete one; the maker must still be an owner or editor.
create policy "maker or owner edits a summary" on public.summaries
  for update to authenticated
  using ((created_by = auth.uid() and public.my_trip_role(trip_id) in ('owner', 'editor')) or public.my_trip_role(trip_id) = 'owner')
  with check (public.my_trip_role(trip_id) in ('owner', 'editor'));
create policy "maker or owner deletes a summary" on public.summaries
  for delete to authenticated
  using (created_by = auth.uid() or public.my_trip_role(trip_id) = 'owner');

revoke all on public.summaries from anon, authenticated;
grant select, delete on public.summaries to authenticated;
grant insert (trip_id, name, layout, mode, tiles, harmonize, colours) on public.summaries to authenticated;
grant update (name, layout, mode, tiles, harmonize, colours, updated_at) on public.summaries to authenticated;
