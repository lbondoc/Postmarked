-- Editing a trip after it's made (SPEC §9.1).

-- Changing a trip's dates must not strand a day note outside them: the note would vanish from the gallery and could
-- no longer be edited (day_note_rules refuses days outside the dates). Saving is refused instead, naming the day,
-- so the person deletes or moves the note first. Nothing is deleted automatically.
create function public.trip_dates_keep_notes() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_day date;
begin
  select day into v_day from public.day_notes
  where trip_id = new.id and day not between new.start_date and new.end_date
  order by day limit 1;
  if v_day is not null then
    raise exception 'Day % (%) has a note. Delete or move it first.',
      v_day - old.start_date + 1, to_char(v_day, 'Dy DD Mon')
      using errcode = 'check_violation';
  end if;
  return new;
end $$;

create trigger trip_dates_keep_notes before update of start_date, end_date on public.trips
  for each row execute function public.trip_dates_keep_notes();

-- Saves the Edit trip form in one transaction: name, dates, start and end points ({"label", "lat", "lng"} or
-- null), and the regions ([{"label", "source", "geometry"}], replaced whole). Owner and editors only.
create function public.edit_trip(
  p_trip uuid, p_name text, p_start_date date, p_end_date date, p_regions jsonb,
  p_start jsonb default null, p_end jsonb default null
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if (public.my_trip_role(p_trip) in ('owner', 'editor')) is not true then
    raise exception 'Only the owner and editors can change the trip' using errcode = '42501';
  end if;
  update public.trips set
    name = trim(p_name), start_date = p_start_date, end_date = p_end_date,
    start_label = p_start ->> 'label', start_point = public._point(p_start),
    end_label = p_end ->> 'label', end_point = public._point(p_end)
  where id = p_trip;
  perform public._replace_regions(p_trip, p_regions);
end $$;

revoke execute on function public.trip_dates_keep_notes() from public, anon, authenticated;
revoke execute on function public.edit_trip(uuid, text, date, date, jsonb, jsonb, jsonb) from public, anon;
grant execute on function public.edit_trip(uuid, text, date, date, jsonb, jsonb, jsonb) to authenticated;
