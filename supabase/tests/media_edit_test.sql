-- Who can change a photo's date and place, and that it's marked as set by hand (SPEC §9.3).

insert into auth.users (id, email) values
  ('a9000000-0000-0000-0000-000000000001', 'm-owner@example.com'),
  ('b9000000-0000-0000-0000-000000000002', 'm-uploader@example.com'),
  ('c9000000-0000-0000-0000-000000000003', 'm-editor@example.com'),
  ('d9000000-0000-0000-0000-000000000004', 'm-viewer@example.com');

do $$
declare
  owner    uuid := 'a9000000-0000-0000-0000-000000000001';
  uploader uuid := 'b9000000-0000-0000-0000-000000000002';
  editor   uuid := 'c9000000-0000-0000-0000-000000000003';
  viewer   uuid := 'd9000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  photo uuid := '19000000-0000-0000-0000-000000000001';
  undated uuid := '19000000-0000-0000-0000-000000000002';
  edit text := 'select public.edit_media(%L, %L, %s, %s, %s)';
  row public.media;
  who uuid;
begin
  perform test.act_as(owner);
  trip := public.create_trip('Media edit test', '2026-10-03', '2026-10-05', area);
  foreach who in array array[uploader, editor, viewer] loop
    perform test.act_as(who);
    perform public.join_trip(test.invite(trip, owner, case when who = viewer then 'viewer' else 'editor' end));
  end loop;

  -- The uploader adds a photo with GPS, and one with no date in the file (the day was chosen: date_by_hand).
  perform test.act_as(uploader);
  insert into public.media (id, trip_id, kind, captured_at, location, sha256)
    values (photo, trip, 'photo', '2026-10-03T18:00:00Z', 'SRID=4326;POINT(-121.9 36.6)', repeat('c', 64));
  insert into public.media (id, trip_id, kind, captured_at, sha256, date_by_hand)
    values (undated, trip, 'photo', '2026-10-04T19:00:00Z', repeat('d', 64), true);
  perform set_config('role', 'none', true);
  select * into row from public.media where id = photo;
  assert not row.date_by_hand and not row.location_by_hand, 'camera values are not by hand';
  assert (select date_by_hand from public.media where id = undated), 'an upload can say its day was chosen';

  -- The uploader moves it to another day and places it by hand.
  perform test.act_as(uploader);
  assert not test.fails(format(edit, photo, '2026-10-04T17:30:00Z', '-420', '36.27', '-121.81')), 'the uploader edits';
  perform set_config('role', 'none', true);
  select * into row from public.media where id = photo;
  assert row.captured_at = '2026-10-04T17:30:00Z' and row.utc_offset_minutes = -420;
  assert row.date_by_hand and row.location_by_hand, 'both marked as set by hand';
  assert abs(extensions.st_y(row.location::extensions.geometry) - 36.27) < 1e-9;

  -- The owner can too.
  perform test.act_as(owner);
  assert not test.fails(format(edit, photo, '2026-10-05T16:00:00Z', 'null', '36.5', '-121.9')), 'the owner edits';

  -- Another editor and a viewer can't.
  perform test.act_as(editor);
  assert test.fails(format(edit, photo, '2026-10-03T10:00:00Z', 'null', 'null', 'null')), 'another editor cannot';
  -- The palette policy lets editors reach the row, but no update grant covers the date or place.
  assert test.fails(format('update public.media set captured_at = %L where id = %L', '2026-10-03T10:00:00Z', photo)),
    'no direct update of the date';
  assert test.fails(format('update public.media set location = null where id = %L', photo)), 'nor of the place';
  perform test.act_as(viewer);
  assert test.fails(format(edit, photo, '2026-10-03T10:00:00Z', 'null', 'null', 'null')), 'a viewer cannot';
  perform set_config('role', 'none', true);
  assert (select captured_at from public.media where id = photo) = '2026-10-05T16:00:00Z', 'unchanged by them';

  -- Nobody changes which trip it's in, who added it, or its fingerprint.
  foreach who in array array[owner, uploader] loop
    perform test.act_as(who);
    assert test.fails(format('update public.media set trip_id = gen_random_uuid() where id = %L', photo));
    assert test.fails(format('update public.media set contributor = %L where id = %L', editor, photo));
    assert test.fails(format('update public.media set sha256 = %L where id = %L', repeat('e', 64), photo));
  end loop;

  -- A place can be cleared (not marked by hand any more); a date can't be.
  perform test.act_as(uploader);
  assert not test.fails(format(edit, photo, '2026-10-05T16:00:00Z', 'null', 'null', 'null')), 'clear the place';
  perform set_config('role', 'none', true);
  select * into row from public.media where id = photo;
  assert row.location is null and not row.location_by_hand;
  perform test.act_as(uploader);
  assert test.fails(format(edit, photo, null, 'null', 'null', 'null')), 'a photo keeps a date';
  assert test.fails(format(edit, photo, '2026-10-05T16:00:00Z', 'null', '36.5', 'null')), 'half a place is refused';
  assert test.fails(format(edit, photo, '2026-10-05T16:00:00Z', 'null', '95', '0')), 'a place off the map is refused';

  -- Leave the database as found: later tests count rows.
  perform test.act_as(owner);
  delete from public.trips where id = trip;
end $$;
