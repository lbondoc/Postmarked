-- Songs: who can add and remove them, and that only real Spotify links get in.

insert into auth.users (id, email) values
  ('a4000000-0000-0000-0000-000000000001', 'm-owner@example.com'),
  ('b4000000-0000-0000-0000-000000000002', 'm-editor@example.com'),
  ('c4000000-0000-0000-0000-000000000003', 'm-viewer@example.com'),
  ('d4000000-0000-0000-0000-000000000004', 'm-stranger@example.com');

do $$
declare
  owner    uuid := 'a4000000-0000-0000-0000-000000000001';
  editor   uuid := 'b4000000-0000-0000-0000-000000000002';
  viewer   uuid := 'c4000000-0000-0000-0000-000000000003';
  stranger uuid := 'd4000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  add text := 'insert into public.songs (trip_id, provider, url, title, artist, artwork_url, day) values (%L, %L, %L, %L, %L, %L, %L)';
  good text := 'https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT';
  art text := 'https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02baf89eb11ec7c657805d2da0';
  editors_song uuid;
begin
  perform test.act_as(owner);
  trip := public.create_trip('Song test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  perform test.act_as(editor);
  assert not test.fails(format(add, trip, 'spotify', good, 'Never Gonna Give You Up', 'Rick Astley', art, '2026-10-04'));
  editors_song := (select id from public.songs where trip_id = trip);
  assert (select added_by from public.songs where id = editors_song) = editor;
  assert not test.fails(format(add, trip, 'spotify', 'https://open.spotify.com/album/1A2GTWGtFfWp7KSQTwWOyo', 'Human After All', null, null, null)),
    'artist, artwork and day are optional';
  assert test.fails(format(add, trip, 'spotify', good || '?si=x', 'T', null, null, null)), 'stored without tracking';
  assert test.fails(format(add, trip, 'spotify', 'https://evil.example/track/4cOdK2wGLETKBW3PvgPWqT', 'T', null, null, null));
  assert test.fails(format(add, trip, 'spotify', 'javascript:alert(1)', 'T', null, null, null));
  assert test.fails(format(add, trip, 'spotify', good, 'T', null, 'https://evil.example/pixel.gif', null)), 'artwork only from Spotify';
  assert test.fails(format(add, trip, 'spotify', good, '  ', null, null, null)), 'needs a title';
  assert test.fails(format(add, trip, 'applemusic', good, 'T', null, null, null)), 'only providers the app knows';

  perform test.act_as(viewer);
  assert (select count(*) from public.songs where trip_id = trip) = 2, 'viewers see songs';
  assert test.fails(format(add, trip, 'spotify', good, 'Mine', null, null, null)), 'but cannot add them';
  assert test.rows(format('delete from public.songs where id = %L', editors_song)) = 0;
  perform test.act_as(stranger);
  assert (select count(*) from public.songs) = 0;
  assert test.fails(format(add, trip, 'spotify', good, 'Mine', null, null, null));

  perform test.act_as(owner);
  assert test.rows(format('delete from public.songs where id = %L', editors_song)) = 1, 'the owner can remove any song';
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.songs) = 0, 'songs go with the trip';
end $$;
