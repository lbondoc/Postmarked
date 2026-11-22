-- Who can add, see and remove a trip's photos and videos, and their files in Storage.

insert into auth.users (id, email) values
  ('a1000000-0000-0000-0000-000000000001', 'owner@example.com'),
  ('b1000000-0000-0000-0000-000000000002', 'editor@example.com'),
  ('c1000000-0000-0000-0000-000000000003', 'viewer@example.com'),
  ('d1000000-0000-0000-0000-000000000004', 'stranger@example.com');

do $$
declare
  owner    uuid := 'a1000000-0000-0000-0000-000000000001';
  editor   uuid := 'b1000000-0000-0000-0000-000000000002';
  viewer   uuid := 'c1000000-0000-0000-0000-000000000003';
  stranger uuid := 'd1000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  photo uuid := '11111111-0000-0000-0000-000000000001';
  video uuid := '11111111-0000-0000-0000-000000000002';
  hash1 text := repeat('a', 64);
  hash2 text := repeat('b', 64);
  add_media text := 'insert into public.media (id, trip_id, kind, captured_at, utc_offset_minutes, location, sha256)
                     values (%L, %L, %L, %L, %s, %L, %L)';
  add_file text := 'insert into storage.objects (bucket_id, name) values (%L, %L)';
begin
  perform test.act_as(owner);
  trip := public.create_trip('Media test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  -- Files first, the way the app uploads them.
  perform test.act_as(editor);
  assert not test.fails(format(add_file, 'media', trip || '/' || photo || '/original'));
  assert not test.fails(format(add_file, 'media', trip || '/' || photo || '/thumb.jpg'));
  assert test.fails(format(add_file, 'media', trip || '/' || photo || '/anything-else.exe')), 'only our file names';
  assert test.fails(format(add_file, 'media', 'not-a-trip/' || photo || '/original')), 'only our folder layout';
  assert test.fails(format(add_file, 'media', gen_random_uuid() || '/' || photo || '/original')), 'not into other trips';
  assert test.rows(format('update storage.objects set name = name where name = %L', trip || '/' || photo || '/original')) = 1,
    'the uploader can overwrite their own file (retrying an upload)';

  -- Then the row. Location arrives as text, the form the app sends it in.
  assert not test.fails(format(add_media, photo, trip, 'photo', '2026-10-03T18:00:00Z', '-420',
                               'SRID=4326;POINT(-121.5 36.5)', hash1));
  assert (select contributor from public.media where id = photo) = editor, 'the contributor is always the uploader';
  assert (select round(st_y(location::geometry)::numeric, 1) from public.media where id = photo) = 36.5;
  assert test.fails(format(add_media, gen_random_uuid(), trip, 'photo', '2026-10-03T18:00:00Z', 'null', null, hash1)),
    'the same file cannot be added to a trip twice';
  assert test.fails(format(add_media, gen_random_uuid(), trip, 'sticker', '2026-10-03T18:00:00Z', 'null', null, hash2));
  assert test.fails(format(add_media, gen_random_uuid(), trip, 'photo', '2026-10-03T18:00:00Z', 'null', null, 'not-a-hash'));
  assert test.fails(format('insert into public.media (id, trip_id, contributor, kind, captured_at, sha256) values (%L, %L, %L, %L, %L, %L)',
                           gen_random_uuid(), trip, owner, 'photo', '2026-10-03T18:00:00Z', hash2)),
    'nobody can add media in someone else''s name';

  -- Viewers look but do not add.
  perform test.act_as(viewer);
  assert (select count(*) from public.media) = 1;
  assert (select count(*) from storage.objects where bucket_id = 'media') = 2;
  assert test.fails(format(add_file, 'media', trip || '/' || video || '/video.mp4'));
  assert test.fails(format(add_media, video, trip, 'video', '2026-10-04T18:00:00Z', 'null', null, hash2));
  assert test.rows(format('delete from public.media where id = %L', photo)) = 0;
  assert test.rows(format('delete from storage.objects where name = %L', trip || '/' || photo || '/thumb.jpg')) = 0;

  -- Strangers see nothing.
  perform test.act_as(stranger);
  assert (select count(*) from public.media) = 0;
  assert (select count(*) from storage.objects) = 0;
  assert test.rows(format('update storage.objects set name = name where name = %L', trip || '/' || photo || '/original')) = 0;

  -- The owner adds a video; the editor cannot remove it, the owner can remove anyone's files.
  perform test.act_as(owner);
  assert not test.fails(format(add_file, 'media', trip || '/' || video || '/video.mp4'));
  assert not test.fails(format(add_media, video, trip, 'video', '2026-10-04T18:00:00Z', 'null', null, hash2));
  perform test.act_as(editor);
  assert test.rows(format('delete from public.media where id = %L', video)) = 0;
  assert test.rows(format('delete from storage.objects where name = %L', trip || '/' || video || '/video.mp4')) = 0;
  assert test.rows(format('delete from public.media where id = %L', photo)) = 1, 'members remove their own media';
  perform test.act_as(owner);
  assert test.rows(format('delete from storage.objects where name like %L', trip || '/%')) = 3;

  -- Deleting the trip removes its media rows.
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.media) = 0;
end $$;
