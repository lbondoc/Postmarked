-- Who can choose a trip's cover, and that it always stays one of the trip's own photos.

insert into auth.users (id, email) values
  ('a5000000-0000-0000-0000-000000000001', 'c-owner@example.com'),
  ('b5000000-0000-0000-0000-000000000002', 'c-editor@example.com'),
  ('c5000000-0000-0000-0000-000000000003', 'c-viewer@example.com');

do $$
declare
  owner  uuid := 'a5000000-0000-0000-0000-000000000001';
  editor uuid := 'b5000000-0000-0000-0000-000000000002';
  viewer uuid := 'c5000000-0000-0000-0000-000000000003';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  other_trip uuid;
  photo uuid := '15000000-0000-0000-0000-000000000001';
  elsewhere uuid := '15000000-0000-0000-0000-000000000002';
  add_media text := 'insert into public.media (id, trip_id, kind, captured_at, sha256) values (%L, %L, %L, %L, %L)';
  set_cover text := 'update public.trips set cover_media_id = %L where id = %L';
begin
  perform test.act_as(owner);
  trip := public.create_trip('Cover test', '2026-10-03', '2026-10-05', area);
  other_trip := public.create_trip('Another trip', '2026-11-01', '2026-11-02', area);
  assert not test.fails(format(add_media, elsewhere, other_trip, 'photo', '2026-11-01T12:00:00Z', repeat('d', 64)));
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  perform test.act_as(editor);
  assert not test.fails(format(add_media, photo, trip, 'photo', '2026-10-03T18:00:00Z', repeat('c', 64)));

  -- An editor sets it, and every member sees it.
  assert test.rows(format(set_cover, photo, trip)) = 1, 'an editor can set the cover';
  perform test.act_as(viewer);
  assert (select cover_media_id from public.trips where id = trip) = photo, 'members see the cover';

  -- A viewer can't change it.
  assert test.rows(format('update public.trips set cover_media_id = null where id = %L', trip)) = 0, 'a viewer cannot set the cover';
  assert (select cover_media_id from public.trips where id = trip) = photo;

  -- Media from another trip is refused, even by the owner of both trips.
  perform test.act_as(owner);
  assert test.fails(format(set_cover, elsewhere, trip)), 'media from another trip is refused';
  assert test.fails(format(set_cover, gen_random_uuid(), trip)), 'media that does not exist is refused';
  assert (select cover_media_id from public.trips where id = trip) = photo;

  -- Deleting the photo clears the cover, and only the cover.
  perform test.act_as(editor);
  assert test.rows(format('delete from public.media where id = %L', photo)) = 1;
  assert (select (cover_media_id is null, name) from public.trips where id = trip) = (true, 'Cover test'::text),
    'deleting the media clears the cover';

  perform test.act_as(owner);
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  assert test.rows(format('delete from public.trips where id = %L', other_trip)) = 1;
end $$;
