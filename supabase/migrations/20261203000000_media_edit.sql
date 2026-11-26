-- Setting a photo's date or place by hand (SPEC §9.3), and photos added with no date in the file (§9.2).

alter table public.media
  add column date_by_hand boolean not null default false,
  add column location_by_hand boolean not null default false;

-- An upload whose file had no date says so: the person chose the day.
grant insert (date_by_hand, location_by_hand) on public.media to authenticated;

-- Changes a photo's date (and time zone) and place. Only the person who added it and the trip owner may. This is a
-- function rather than an update grant because "owner and editors fill in palettes" already lets every editor
-- update media rows, so a column grant would let any editor move anyone's photo.
-- p_lat and p_lng both null clears the place. Changing a value marks it as set by hand; clearing the place unmarks it.
create function public.edit_media(
  p_media uuid, p_captured_at timestamptz, p_utc_offset_minutes int, p_lat float8, p_lng float8
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  m public.media;
  v_location extensions.geography;
begin
  select * into m from public.media where id = p_media;
  if m.id is null or (m.contributor is distinct from auth.uid() and public.my_trip_role(m.trip_id) is distinct from 'owner') then
    raise exception 'Only the person who added it and the trip owner can change it' using errcode = '42501';
  end if;
  if p_captured_at is null then
    raise exception 'A photo needs a date' using errcode = '22023';
  end if;
  if (p_lat is null) <> (p_lng is null) then
    raise exception 'A place needs both lat and lng' using errcode = '22023';
  end if;
  if p_lat is not null then
    v_location := public._point(jsonb_build_object('lat', p_lat, 'lng', p_lng));
  end if;
  update public.media set
    captured_at = p_captured_at,
    utc_offset_minutes = p_utc_offset_minutes,
    date_by_hand = date_by_hand or captured_at is distinct from p_captured_at
                   or utc_offset_minutes is distinct from p_utc_offset_minutes,
    location = v_location,
    location_by_hand = case
      when v_location is null then false
      when location is null or not extensions.st_equals(location::extensions.geometry, v_location::extensions.geometry) then true
      else location_by_hand end
  where id = p_media;
end $$;

revoke execute on function public.edit_media(uuid, timestamptz, int, float8, float8) from public, anon;
grant execute on function public.edit_media(uuid, timestamptz, int, float8, float8) to authenticated;
