-- Who can edit a trip, and that shortening its dates never strands a day note (SPEC §9.1).

insert into auth.users (id, email) values
  ('a8000000-0000-0000-0000-000000000001', 'e-owner@example.com'),
  ('b8000000-0000-0000-0000-000000000002', 'e-editor@example.com'),
  ('c8000000-0000-0000-0000-000000000003', 'e-viewer@example.com');

do $$
declare
  owner  uuid := 'a8000000-0000-0000-0000-000000000001';
  editor uuid := 'b8000000-0000-0000-0000-000000000002';
  viewer uuid := 'c8000000-0000-0000-0000-000000000003';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  coast jsonb := '[{"label": "Coast", "source": "place", "geometry":
    {"type": "Polygon", "coordinates": [[[-123,35],[-120,35],[-120,38],[-123,38],[-123,35]]]}},
    {"label": "Valley", "source": "place", "geometry":
    {"type": "Polygon", "coordinates": [[[-120,36],[-119,36],[-119,37],[-120,37],[-120,36]]]}}]';
  sf jsonb := '{"label": "San Francisco", "lat": 37.77, "lng": -122.42}';
  la jsonb := '{"label": "Los Angeles", "lat": 34.05, "lng": -118.24}';
  trip uuid;
  edit text := 'select public.edit_trip(%L, %L, %L, %L, %L, %L, %L)';
  message text;
begin
  perform test.act_as(owner);
  trip := public.create_trip('Edit test', '2026-10-03', '2026-10-07', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  -- An editor renames it and changes its dates, start and end points and regions.
  perform test.act_as(editor);
  assert not test.fails(format(edit, trip, '  Coast run ', '2026-10-02', '2026-10-08', coast, sf, la)), 'an editor edits';
  assert (select name from public.trips where id = trip) = 'Coast run', 'name, trimmed';
  assert (select start_date from public.trips where id = trip) = '2026-10-02';
  assert (select end_date from public.trips where id = trip) = '2026-10-08';
  assert (select start_label from public.trips where id = trip) = 'San Francisco';
  assert (select end_label from public.trips where id = trip) = 'Los Angeles';
  assert (select count(*) from public.trip_regions where trip_id = trip) = 2, 'regions replaced whole';
  -- The regions alone, through the existing function.
  perform public.set_trip_regions(trip, area);
  assert (select count(*) from public.trip_regions where trip_id = trip) = 1;
  -- Start and end can be cleared.
  assert not test.fails(format(edit, trip, 'Coast run', '2026-10-02', '2026-10-08', area, null, null));
  assert (select start_point from public.trips where id = trip) is null;

  -- A viewer can do none of it.
  perform test.act_as(viewer);
  assert test.fails(format(edit, trip, 'Mine now', '2026-10-02', '2026-10-08', area, null, null)), 'a viewer cannot edit';
  assert test.fails(format('select public.set_trip_regions(%L, %L)', trip, coast)), 'a viewer cannot change regions';
  assert test.rows(format('update public.trips set name = %L where id = %L', 'Mine now', trip)) = 0, 'nor update directly';
  perform test.act_as(owner);
  assert (select name from public.trips where id = trip) = 'Coast run';

  -- A note on Oct 7 (day 6 of a trip from Oct 2).
  perform test.act_as(editor);
  insert into public.day_notes (trip_id, day, body) values (trip, '2026-10-07', 'Long drive');

  -- Shortening the dates past the note is refused, naming the day; nothing changes.
  begin
    perform public.edit_trip(trip, 'Coast run', '2026-10-02', '2026-10-05', area, null, null);
    message := null;
  exception when check_violation then
    message := sqlerrm;
  end;
  assert message = 'Day 6 (Wed 07 Oct) has a note. Delete or move it first.', coalesce(message, 'not refused');
  assert (select end_date from public.trips where id = trip) = '2026-10-08', 'dates unchanged';
  assert test.fails(format('update public.trips set start_date = %L where id = %L', '2026-10-08', trip)), 'a direct update too';
  assert (select count(*) from public.day_notes where trip_id = trip) = 1, 'the note is kept';

  -- Shortening to a range that still holds every note works.
  assert not test.fails(format(edit, trip, 'Coast run', '2026-10-04', '2026-10-07', area, null, null)), 'shorter, note inside';
  assert (select start_date from public.trips where id = trip) = '2026-10-04';

  -- Other edits (a rename) don't trip the check.
  perform test.act_as(owner);
  update public.trips set name = 'Coast run 2' where id = trip;
  assert (select name from public.trips where id = trip) = 'Coast run 2';

  -- Leave the database as found: later tests count trips.
  delete from public.trips where id = trip;
end $$;
