-- Reactions, comments and the shot-grouping columns.

insert into auth.users (id, email) values
  ('a2000000-0000-0000-0000-000000000001', 'g-owner@example.com'),
  ('b2000000-0000-0000-0000-000000000002', 'g-viewer@example.com'),
  ('c2000000-0000-0000-0000-000000000003', 'g-stranger@example.com');

do $$
declare
  owner    uuid := 'a2000000-0000-0000-0000-000000000001';
  viewer   uuid := 'b2000000-0000-0000-0000-000000000002';
  stranger uuid := 'c2000000-0000-0000-0000-000000000003';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  photo uuid := '22222222-0000-0000-0000-000000000001';
  comment_id uuid;
  react text := 'insert into public.reactions (media_id, emoji) values (%L, %L)';
  say text := 'insert into public.comments (media_id, body) values (%L, %L)';
begin
  perform test.act_as(owner);
  trip := public.create_trip('Gallery test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  perform test.act_as(owner);
  insert into public.media (id, trip_id, kind, captured_at, sha256, dhash, sharpness)
  values (photo, trip, 'photo', '2026-10-03T18:00:00Z', repeat('d', 64), -8070450532247928832, 123.5);
  assert (select dhash from public.media where id = photo) = -8070450532247928832, 'all 64 bits survive';
  assert test.fails(format('insert into public.media (id, trip_id, kind, captured_at, sha256, sharpness) values (%L, %L, %L, %L, %L, -1)',
                           gen_random_uuid(), trip, 'photo', '2026-10-03T18:00:00Z', repeat('e', 64)));

  -- Viewers react and comment; they're members too.
  perform test.act_as(viewer);
  assert not test.fails(format(react, photo, '❤️'));
  assert not test.fails(format(react, photo, '🔥'));
  assert test.fails(format(react, photo, '❤️')), 'one of each emoji per person';
  assert test.fails(format(react, photo, '💩')), 'only the offered emoji';
  assert not test.fails(format(say, photo, 'Great light!'));
  assert test.fails(format(say, photo, '   ')), 'no empty comments';
  assert test.fails(format(say, photo, repeat('x', 1001)));
  assert test.fails(format('insert into public.comments (media_id, body, author) values (%L, %L, %L)', photo, 'as owner', owner)),
    'nobody comments in someone else''s name';
  assert (select author from public.comments where body = 'Great light!') = viewer;
  comment_id := (select id from public.comments where body = 'Great light!');

  perform test.act_as(owner);
  assert (select count(*) from public.reactions where media_id = photo) = 2, 'members see everyone''s reactions';
  assert (select count(*) from public.comments where media_id = photo) = 1;
  assert test.rows(format('delete from public.comments where id = %L', comment_id)) = 0, 'only the author deletes a comment';
  assert test.rows(format('delete from public.reactions where user_id = %L', viewer)) = 0;

  -- Strangers: nothing.
  perform test.act_as(stranger);
  assert (select count(*) from public.reactions) + (select count(*) from public.comments) = 0;
  assert test.fails(format(react, photo, '👍'));
  assert test.fails(format(say, photo, 'hi'));
  assert public.trip_of_media(photo) = trip, 'the helper only returns an id, which reveals nothing on its own';

  perform test.act_as(viewer);
  assert test.rows(format('delete from public.reactions where media_id = %L and emoji = %L', photo, '🔥')) = 1;
  assert test.rows(format('delete from public.comments where id = %L', comment_id)) = 1;

  -- Removing the photo takes its reactions and comments with it.
  perform test.act_as(owner);
  assert test.rows(format('delete from public.media where id = %L', photo)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.reactions where media_id = photo) = 0;
  perform test.act_as(owner);
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
end $$;
