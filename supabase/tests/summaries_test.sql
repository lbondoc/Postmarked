-- Palettes on photos, and who can make, change and delete summaries.

insert into auth.users (id, email) values
  ('a3000000-0000-0000-0000-000000000001', 's-owner@example.com'),
  ('b3000000-0000-0000-0000-000000000002', 's-editor@example.com'),
  ('c3000000-0000-0000-0000-000000000003', 's-viewer@example.com'),
  ('d3000000-0000-0000-0000-000000000004', 's-stranger@example.com');

do $$
declare
  owner    uuid := 'a3000000-0000-0000-0000-000000000001';
  editor   uuid := 'b3000000-0000-0000-0000-000000000002';
  viewer   uuid := 'c3000000-0000-0000-0000-000000000003';
  stranger uuid := 'd3000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  photo uuid := '33333333-0000-0000-0000-000000000001';
  colours jsonb := '[{"c": "#F2C230", "w": 0.6}, {"c": "#3F8F3A", "w": 0.4}]';
  make text := 'insert into public.summaries (trip_id, name, layout, mode, tiles) values (%L, %L, %L, %L, %L)';
  mine uuid;
  owners uuid;
begin
  perform test.act_as(owner);
  trip := public.create_trip('Summary test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  -- Palettes arrive with the upload, or are filled in later by an owner or editor.
  perform test.act_as(owner);
  insert into public.media (id, trip_id, kind, captured_at, sha256) values (photo, trip, 'photo', '2026-10-03T18:00:00Z', repeat('f', 64));
  perform test.act_as(editor);
  assert test.rows(format('update public.media set palette = %L where id = %L', colours, photo)) = 1;
  assert (select m.palette from public.media m where m.id = photo) = colours;
  assert test.fails(format('update public.media set captured_at = now() where id = %L', photo)), 'only the palette can change';
  assert test.fails(format('update public.media set palette = %L where id = %L', '{"not": "a list"}', photo));
  perform test.act_as(viewer);
  assert test.rows(format('update public.media set palette = null where id = %L', photo)) = 0, 'viewers cannot';
  perform test.act_as(stranger);
  assert test.rows(format('update public.media set palette = null where id = %L', photo)) = 0;

  -- Making summaries: owners and editors.
  perform test.act_as(editor);
  assert not test.fails(format(make, trip, 'Yellow', 'Grid3x3', 'Monochrome', '[{"media": "' || photo || '"}, {"text": "Big Sur", "bg": "#F2C230"}]'));
  mine := (select id from public.summaries where name = 'Yellow');
  assert (select created_by from public.summaries where id = mine) = editor;
  assert test.fails(format(make, trip, '  ', 'Grid3x3', 'Manual', '[]')), 'needs a name';
  assert test.fails(format(make, trip, 'Big', 'Grid3x3', 'Manual', jsonb_build_array(repeat('x', 21000)))), 'tiles are capped';
  assert test.fails(format('insert into public.summaries (trip_id, name, layout, mode, tiles, harmonize) values (%L, %L, %L, %L, %L, 2)',
                           trip, 'Too strong', 'Grid3x3', 'Manual', '[]'));
  perform test.act_as(viewer);
  assert test.fails(format(make, trip, 'Mine', 'Grid3x3', 'Manual', '[]')), 'viewers cannot make summaries';
  assert (select count(*) from public.summaries where trip_id = trip) = 1, 'but they can see them';
  assert test.rows(format('update public.summaries set name = %L where id = %L', 'hijack', mine)) = 0;
  perform test.act_as(stranger);
  assert (select count(*) from public.summaries) = 0;
  assert test.fails(format(make, trip, 'Mine', 'Grid3x3', 'Manual', '[]'));

  -- Changing and deleting: the maker, or the trip owner.
  perform test.act_as(owner);
  assert not test.fails(format(make, trip, 'Owner''s', 'Hero', 'Palette', '[]'));
  owners := (select id from public.summaries where name = 'Owner''s');
  perform test.act_as(editor);
  assert test.rows(format('update public.summaries set harmonize = 0.6, updated_at = now() where id = %L', mine)) = 1;
  assert test.rows(format('update public.summaries set name = %L where id = %L', 'mine now', owners)) = 0, 'not someone else''s';
  assert test.rows(format('delete from public.summaries where id = %L', owners)) = 0;
  perform test.act_as(owner);
  assert test.rows(format('update public.summaries set name = %L where id = %L', 'Tidied', mine)) = 1, 'the owner can tidy any';

  -- An editor demoted to viewer can no longer change their old summary.
  update public.trip_members set role = 'viewer' where trip_id = trip and user_id = editor;
  perform test.act_as(editor);
  assert test.rows(format('update public.summaries set name = %L where id = %L', 'still mine?', mine)) = 0;
  assert test.rows(format('delete from public.summaries where id = %L', mine)) = 1, 'but can still delete what they made';

  perform test.act_as(owner);
  assert test.rows(format('delete from public.trips where id = %L', trip)) = 1;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.summaries) = 0, 'summaries go with the trip';
end $$;
