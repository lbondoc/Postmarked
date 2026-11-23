-- Who can read and write a trip's day notes, and that the newest edit wins.

insert into auth.users (id, email) values
  ('a6000000-0000-0000-0000-000000000001', 'n-owner@example.com'),
  ('b6000000-0000-0000-0000-000000000002', 'n-editor@example.com'),
  ('c6000000-0000-0000-0000-000000000003', 'n-viewer@example.com'),
  ('d6000000-0000-0000-0000-000000000004', 'n-stranger@example.com');

do $$
declare
  owner    uuid := 'a6000000-0000-0000-0000-000000000001';
  editor   uuid := 'b6000000-0000-0000-0000-000000000002';
  viewer   uuid := 'c6000000-0000-0000-0000-000000000003';
  stranger uuid := 'd6000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  -- The app's upsert, as PostgREST sends it.
  upsert text := 'insert into public.day_notes (trip_id, day, body, updated_at) values (%L, %L, %L, %L)
                  on conflict (trip_id, day) do update set body = excluded.body, updated_at = excluded.updated_at';
begin
  perform test.act_as(owner);
  trip := public.create_trip('Notes test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  -- Editors write; the writer is recorded, whatever the client says.
  perform test.act_as(editor);
  assert not test.fails(format(upsert, trip, '2026-10-03', 'Fog all morning', '2026-10-03T20:00:00Z'));
  assert (select updated_by from public.day_notes where trip_id = trip and day = '2026-10-03') = editor;

  -- Members read it.
  perform test.act_as(viewer);
  assert (select body from public.day_notes where trip_id = trip and day = '2026-10-03') = 'Fog all morning', 'members read';

  -- Viewers don't write, and strangers don't even see it.
  assert test.fails(format(upsert, trip, '2026-10-04', 'viewer was here', '2026-10-04T20:00:00Z')), 'a viewer cannot add a note';
  assert test.rows(format('update public.day_notes set body = %L where trip_id = %L', 'changed', trip)) = 0, 'a viewer cannot edit one';
  assert test.rows(format('delete from public.day_notes where trip_id = %L', trip)) = 0, 'a viewer cannot delete one';
  perform test.act_as(stranger);
  assert (select count(*) from public.day_notes where trip_id = trip) = 0, 'strangers see nothing';
  assert test.fails(format(upsert, trip, '2026-10-04', 'hi', '2026-10-04T20:00:00Z'));

  -- The day must be within the trip's dates, both ends included.
  perform test.act_as(owner);
  assert not test.fails(format(upsert, trip, '2026-10-05', 'Last day', '2026-10-05T20:00:00Z')), 'the last day counts';
  assert test.fails(format(upsert, trip, '2026-10-02', 'Before', '2026-10-02T20:00:00Z')), 'a day before the trip is refused';
  assert test.fails(format(upsert, trip, '2026-10-06', 'After', '2026-10-06T20:00:00Z')), 'a day after the trip is refused';

  -- Last write wins by updated_at: a newer edit replaces, an older one that arrives late is skipped.
  assert not test.fails(format(upsert, trip, '2026-10-03', 'Fog, then sun', '2026-10-03T21:00:00Z'));
  assert (select (body, updated_by) from public.day_notes where trip_id = trip and day = '2026-10-03') = ('Fog, then sun'::text, owner);
  perform test.act_as(editor);
  assert not test.fails(format(upsert, trip, '2026-10-03', 'Stale offline edit', '2026-10-03T20:30:00Z'));
  assert (select body from public.day_notes where trip_id = trip and day = '2026-10-03') = 'Fog, then sun', 'the older edit does not win';

  -- Editors can remove a note; notes go with the trip.
  assert test.rows(format('delete from public.day_notes where trip_id = %L and day = %L', trip, '2026-10-05')) = 1;
  perform test.act_as(owner);
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.day_notes where trip_id = trip) = 0, 'notes go with the trip';
end $$;
