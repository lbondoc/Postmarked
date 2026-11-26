-- Who can make, revoke and read a trip's share link (SPEC §7).

insert into auth.users (id, email) values
  ('a7000000-0000-0000-0000-000000000001', 's-owner@example.com'),
  ('b7000000-0000-0000-0000-000000000002', 's-editor@example.com'),
  ('c7000000-0000-0000-0000-000000000003', 's-viewer@example.com'),
  ('d7000000-0000-0000-0000-000000000004', 's-stranger@example.com');

do $$
declare
  owner    uuid := 'a7000000-0000-0000-0000-000000000001';
  editor   uuid := 'b7000000-0000-0000-0000-000000000002';
  viewer   uuid := 'c7000000-0000-0000-0000-000000000003';
  stranger uuid := 'd7000000-0000-0000-0000-000000000004';
  area jsonb := '[{"label": "Box", "source": "drawn", "geometry":
    {"type": "Polygon", "coordinates": [[[-122,36],[-121,36],[-121,37],[-122,37],[-122,36]]]}}]';
  trip uuid;
  first_token text;
  second_token text;
begin
  perform test.act_as(owner);
  trip := public.create_trip('Shares test', '2026-10-03', '2026-10-05', area);
  perform test.act_as(editor);
  perform public.join_trip(test.invite(trip, owner, 'editor'));
  perform test.act_as(viewer);
  perform public.join_trip(test.invite(trip, owner, 'viewer'));

  -- An editor turns the link on: a 22-character base64url token (128 bits).
  perform test.act_as(editor);
  first_token := public.create_share(trip);
  assert first_token ~ '^[A-Za-z0-9_-]{22}$', 'token is 128 bits in base64url';
  assert public.share_is_on(trip);

  -- Only the creator reads the token through the API; other members only learn that sharing is on.
  assert (select count(*) from public.trip_shares where trip_id = trip) = 1, 'the creator sees their link';
  perform test.act_as(owner);
  assert (select count(*) from public.trip_shares) = 0, 'the owner cannot read the editor''s token';
  assert public.share_is_on(trip), 'but sees that sharing is on';
  perform test.act_as(viewer);
  assert (select count(*) from public.trip_shares) = 0, 'a viewer cannot read tokens';
  assert public.share_is_on(trip);
  perform test.act_as(stranger);
  assert (select count(*) from public.trip_shares) = 0;
  assert not public.share_is_on(trip), 'a stranger learns nothing';
  perform test.act_as(null);
  assert test.fails('select * from public.trip_shares'), 'signed out: no access at all';
  assert test.fails(format('select public.share_is_on(%L)', trip));

  -- Viewers and strangers can't turn it on or off.
  perform test.act_as(viewer);
  assert test.fails(format('select public.create_share(%L)', trip)), 'a viewer cannot share';
  assert test.fails(format('select public.revoke_share(%L)', trip)), 'a viewer cannot revoke';
  perform test.act_as(stranger);
  assert test.fails(format('select public.create_share(%L)', trip));

  -- Nobody writes the table directly.
  perform test.act_as(editor);
  assert test.fails(format('insert into public.trip_shares (token, trip_id, created_by) values (%L, %L, %L)',
    'AAAAAAAAAAAAAAAAAAAAAA', trip, editor)), 'no direct inserts';
  assert test.fails(format('update public.trip_shares set revoked_at = null where trip_id = %L', trip)), 'no direct updates';

  -- A new link replaces the old one: one live token per trip.
  perform test.act_as(owner);
  second_token := public.create_share(trip);
  assert second_token <> first_token, 'a new link gets a new token';
  perform set_config('role', 'none', true);
  assert (select count(*) from public.trip_shares where trip_id = trip and revoked_at is null) = 1, 'one live link';
  assert (select revoked_at from public.trip_shares where token = first_token) is not null, 'the old token is revoked';

  -- Any editor can turn it off, whoever made it.
  perform test.act_as(editor);
  perform public.revoke_share(trip);
  assert not public.share_is_on(trip), 'revoked';
  perform set_config('role', 'none', true);
  assert (select count(*) from public.trip_shares where trip_id = trip and revoked_at is null) = 0;

  -- Deleting the trip deletes its links.
  perform test.act_as(owner);
  delete from public.trips where id = trip;
  perform set_config('role', 'none', true);
  assert (select count(*) from public.trip_shares where trip_id = trip) = 0;
end $$;
