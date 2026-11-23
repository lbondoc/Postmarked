-- A note per day of a trip, shown at the top of that day in the gallery (SPEC §6).

create table public.day_notes (
  trip_id uuid not null references public.trips on delete cascade,
  day date not null,
  body text not null check (char_length(body) <= 4000),
  updated_by uuid default auth.uid() references public.profiles on delete set null,
  updated_at timestamptz not null default now(),
  primary key (trip_id, day)
);

-- A check constraint can't look at the trip, so a trigger keeps the day within the trip's dates. It also makes
-- the newest edit win: edits can arrive late from a phone that was offline (the outbox), and one older than
-- what is saved is skipped instead of overwriting it. Who wrote it is always the caller.
create function public.day_note_rules() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_start date;
  v_end date;
begin
  select start_date, end_date into v_start, v_end from public.trips where id = new.trip_id;
  if new.day not between v_start and v_end then
    raise exception 'That day is outside the trip''s dates.' using errcode = 'check_violation';
  end if;
  if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
    return null; -- an older edit: keep the newer one already saved
  end if;
  new.updated_by := auth.uid();
  return new;
end $$;

create trigger day_note_rules before insert or update on public.day_notes
  for each row execute function public.day_note_rules();

alter table public.day_notes enable row level security;

create policy "members read day notes" on public.day_notes
  for select to authenticated using (public.my_trip_role(trip_id) is not null);
create policy "owner and editors add day notes" on public.day_notes
  for insert to authenticated with check (public.my_trip_role(trip_id) in ('owner', 'editor'));
create policy "owner and editors edit day notes" on public.day_notes
  for update to authenticated
  using (public.my_trip_role(trip_id) in ('owner', 'editor'))
  with check (public.my_trip_role(trip_id) in ('owner', 'editor'));
create policy "owner and editors delete day notes" on public.day_notes
  for delete to authenticated using (public.my_trip_role(trip_id) in ('owner', 'editor'));

revoke all on public.day_notes from anon, authenticated;
grant select, delete on public.day_notes to authenticated;
-- The app upserts by (trip_id, day), sending its own updated_at so the newest edit wins.
grant insert (trip_id, day, body, updated_at) on public.day_notes to authenticated;
grant update (body, updated_at) on public.day_notes to authenticated;
revoke execute on function public.day_note_rules() from public, anon, authenticated;
