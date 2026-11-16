-- Who can see and do what with trips, regions, members and invites.
-- One block so later steps can reuse the trip and tokens created earlier. A failed
-- `assert` aborts with its line number; test.act_as switches the signed-in user.

insert into auth.users (id, email, raw_user_meta_data) values
  ('a0000000-0000-0000-0000-000000000001', 'alice@example.com', '{"name": "Alice"}'),
  ('b0000000-0000-0000-0000-000000000002', 'bob@example.com', null),
  ('c0000000-0000-0000-0000-000000000003', null, null),
  ('d0000000-0000-0000-0000-000000000004', 'dave@example.com', null),
  ('e0000000-0000-0000-0000-000000000005', 'erin@example.com', null);

do $$
declare
  alice uuid := 'a0000000-0000-0000-0000-000000000001'; -- will own the trip
  bob   uuid := 'b0000000-0000-0000-0000-000000000002'; -- joins as editor
  carol uuid := 'c0000000-0000-0000-0000-000000000003'; -- joins as viewer
  dave  uuid := 'd0000000-0000-0000-0000-000000000004'; -- never gets in
  erin  uuid := 'e0000000-0000-0000-0000-000000000005'; -- takes the single-use invite
  square jsonb := '[{"label": "Big Sur", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  -- A figure-eight: crosses itself, the way a finger-drawn shape can.
  bowtie jsonb := '[{"label": "Bow tie", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,37],[-121,36],[-122,37],[-122,36]]]}}]';
  a_point jsonb := '[{"label": "Dot", "source": "place", "geometry": {"type": "Point", "coordinates": [-122,36]}}]';
  off_map jsonb := '[{"label": "Nowhere", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[200,36],[201,36],[201,37],[200,37],[200,36]]]}}]';
  trip uuid;
  editor_token text;
  viewer_token text;
  once_token text;
begin
  -- Sign-up creates a profile, named from metadata, then email, then a fallback.
  assert (select display_name from public.profiles where id = alice) = 'Alice';
  assert (select display_name from public.profiles where id = bob) = 'bob';
  assert (select display_name from public.profiles where id = carol) = 'Traveler';

  -- Signed-out visitors: nothing at all.
  perform test.act_as(null);
  assert test.fails('select * from public.trips');
  assert test.fails('select * from public.profiles');
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', 'x', '2026-10-03', '2026-10-05', square));
  assert test.fails($q$select public.join_trip('anything')$q$);

  -- create_trip: date range AND location are both required, and inputs are validated.
  perform test.act_as(alice);
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', 'No regions', '2026-10-03', '2026-10-05', '[]'));
  assert test.fails(format('select public.create_trip(%L, %L, %L, null)', 'Null regions', '2026-10-03', '2026-10-05'));
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', 'Backwards', '2026-10-05', '2026-10-03', square));
  assert test.fails(format('select public.create_trip(%L, null, %L, %L)', 'No start date', '2026-10-05', square));
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', '   ', '2026-10-03', '2026-10-05', square));
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', 'Point', '2026-10-03', '2026-10-05', a_point));
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L)', 'Off map', '2026-10-03', '2026-10-05', off_map));
  assert test.fails(format('select public.create_trip(%L, %L, %L, %L, %L)', 'Bad start', '2026-10-03', '2026-10-05',
                           square, '{"label": "North of north", "lat": 95, "lng": 0}'));
  perform set_config('role', 'none', true);
  assert (select count(*) from public.trips) = 0, 'a rejected create_trip must leave nothing behind';

  perform test.act_as(alice);
  trip := public.create_trip('  Big Sur  ', '2026-10-03', '2026-10-05', square,
                             '{"label": "San Francisco", "lat": 37.7749, "lng": -122.4194}');
  assert public.my_trip_role(trip) = 'owner';
  assert (select name from public.trips where id = trip) = 'Big Sur';
  assert (select count(*) from public.trip_regions where trip_id = trip) = 1;
  assert (select public.start_geojson(t) from public.trips t where id = trip)
         = '{"type": "Point", "coordinates": [-122.4194, 37.7749]}';
  assert (select public.end_geojson(t) from public.trips t where id = trip) is null;
  assert (select public.geojson(r) ->> 'type' from public.trip_regions r where trip_id = trip) = 'MultiPolygon';
  -- Replacing regions swaps the set, and a self-crossing shape is repaired rather than rejected.
  perform public.set_trip_regions(trip, bowtie);
  assert (select count(*) from public.trip_regions where trip_id = trip) = 1;
  assert (select st_isvalid(geom::geometry) and st_area(geom) > 0 from public.trip_regions where trip_id = trip);

  -- Even the owner cannot write rows directly: it all goes through the functions.
  assert test.fails($q$insert into public.trips (name, start_date, end_date) values ('x', '2026-01-01', '2026-01-02')$q$);
  assert test.fails(format('insert into public.trip_members (trip_id, user_id, role) values (%L, %L, %L)', trip, dave, 'editor'));
  assert test.fails(format('delete from public.trip_regions where trip_id = %L', trip));
  assert test.fails(format('select public._replace_regions(%L, %L)', trip, square));
  assert test.fails(format('update public.trips set id = gen_random_uuid() where id = %L', trip));

  -- A stranger sees nothing and can change nothing.
  perform test.act_as(dave);
  assert (select count(*) from public.trips) = 0;
  assert (select count(*) from public.trip_regions) = 0;
  assert (select count(*) from public.trip_members) = 0;
  assert (select count(*) from public.trip_invites) = 0;
  assert (select count(*) from public.profiles) = 1, 'a stranger sees only their own profile';
  assert public.my_trip_role(trip) is null;
  assert test.rows(format('update public.trips set name = %L where id = %L', 'hacked', trip)) = 0;
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 0;
  assert test.rows(format('update public.profiles set display_name = %L where id = %L', 'hacked', alice)) = 0;
  assert test.fails(format('select public.create_invite(%L)', trip));
  assert test.fails(format('select public.set_trip_regions(%L, %L)', trip, square));
  assert test.fails($q$select public.join_trip('not-a-real-token')$q$);
  assert test.fails('select public.join_trip(null)');

  -- Invites.
  perform test.act_as(alice);
  editor_token := public.create_invite(trip);
  viewer_token := public.create_invite(trip, 'viewer');
  once_token := public.create_invite(trip, 'editor', 14, 1);
  assert test.fails(format('select public.create_invite(%L, %L)', trip, 'owner'));
  assert test.fails(format('select public.create_invite(%L, %L, 0)', trip, 'editor'));
  assert (select count(*) from public.trip_invites where trip_id = trip) = 3;

  perform test.act_as(bob);
  assert public.join_trip(editor_token) = trip;
  assert public.join_trip(editor_token) = trip, 'opening the link twice is harmless';
  assert public.my_trip_role(trip) = 'editor';
  assert (select count(*) from public.trips) = 1;
  assert (select count(*) from public.profiles) = 2, 'trip mates can see each other';
  perform test.act_as(carol);
  assert public.join_trip(viewer_token) = trip;
  assert public.my_trip_role(trip) = 'viewer';
  perform test.act_as(erin);
  assert public.join_trip(once_token) = trip;
  perform test.act_as(dave);
  assert test.fails(format('select public.join_trip(%L)', once_token)), 'a single-use invite works once';

  perform set_config('role', 'none', true);
  assert (select use_count from public.trip_invites where token_hash = sha256(convert_to(editor_token, 'UTF8'))) = 1,
    'joining twice counts once';
  assert not exists (select 1 from public.trip_invites where encode(token_hash, 'escape') like '%' || editor_token || '%'),
    'the token itself is not stored';
  update public.trip_invites set expires_at = now() - interval '1 second'
  where token_hash = sha256(convert_to(editor_token, 'UTF8'));
  perform test.act_as(dave);
  assert test.fails(format('select public.join_trip(%L)', editor_token)), 'an expired invite is refused';
  assert public.my_trip_role(trip) is null;

  -- Editor: can edit the trip and invite, cannot delete it or manage members.
  perform test.act_as(bob);
  assert test.rows(format('update public.trips set name = %L, theme_id = %L where id = %L', 'Big Sur 2026', 'film', trip)) = 1;
  perform public.set_trip_regions(trip, square);
  assert public.create_invite(trip, 'viewer') is not null;
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 0;
  assert test.rows(format('update public.trip_members set role = %L where trip_id = %L and user_id = %L', 'viewer', trip, carol)) = 0;
  assert test.rows(format('update public.trip_members set role = %L where trip_id = %L and user_id = %L', 'owner', trip, bob)) = 0;
  assert test.rows(format('delete from public.trip_members where trip_id = %L and user_id = %L', trip, carol)) = 0;

  -- Viewer: read only.
  perform test.act_as(carol);
  assert (select name from public.trips where id = trip) = 'Big Sur 2026';
  assert (select count(*) from public.trip_invites) = 0;
  assert test.rows(format('update public.trips set name = %L where id = %L', 'nope', trip)) = 0;
  assert test.fails(format('select public.create_invite(%L)', trip));
  assert test.fails(format('select public.set_trip_regions(%L, %L)', trip, square));

  -- Owner: manages members, but cannot make a second owner, demote or remove themself.
  perform test.act_as(alice);
  assert test.rows(format('update public.trip_members set role = %L where trip_id = %L and user_id = %L', 'viewer', trip, bob)) = 1;
  assert test.fails(format('update public.trip_members set role = %L where trip_id = %L and user_id = %L', 'owner', trip, bob));
  assert test.rows(format('update public.trip_members set role = %L where trip_id = %L and user_id = %L', 'viewer', trip, alice)) = 0;
  assert test.rows(format('delete from public.trip_members where trip_id = %L and user_id = %L', trip, alice)) = 0;
  assert test.rows(format('delete from public.trip_members where trip_id = %L and user_id = %L', trip, carol)) = 1;

  -- Anyone but the owner can leave, and then the trip is gone for them.
  perform test.act_as(bob);
  assert test.rows(format('delete from public.trip_members where trip_id = %L and user_id = %L', trip, bob)) = 1;
  assert (select count(*) from public.trips) = 0;

  -- Deleting the trip takes its regions, members and invites with it.
  perform test.act_as(alice);
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.trip_regions) + (select count(*) from public.trip_members)
       + (select count(*) from public.trip_invites) = 0;
end $$;
