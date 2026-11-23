-- MVP milestone 4: the gallery. Reactions and comments on photos and videos, and two numbers
-- the phone computes at upload so near-identical shots can be grouped.

-- A 64-bit fingerprint of what the picture looks like (similar pictures differ in few bits),
-- and how sharp it is (higher is sharper), used to pick the best of a burst.
alter table public.media add column dhash bigint, add column sharpness real check (sharpness >= 0);
grant insert (dhash, sharpness) on public.media to authenticated;

-- The trip an item belongs to, for the rules below. Security definer: it must read media rows
-- regardless of the caller's own access, the same way my_trip_role reads memberships.
create function public.trip_of_media(p_media uuid) returns uuid
language sql stable security definer set search_path = '' as $$
  select trip_id from public.media where id = p_media
$$;

create table public.reactions (
  media_id uuid not null references public.media on delete cascade,
  user_id uuid not null default auth.uid() references public.profiles on delete cascade,
  emoji text not null check (emoji in ('❤️', '😂', '😮', '👍', '🔥')),
  created_at timestamptz not null default now(),
  primary key (media_id, user_id, emoji)
);

create table public.comments (
  id uuid primary key default gen_random_uuid(),
  media_id uuid not null references public.media on delete cascade,
  author uuid not null default auth.uid() references public.profiles on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 1000),
  created_at timestamptz not null default now()
);
create index comments_media on public.comments (media_id, created_at);

alter table public.reactions enable row level security;
alter table public.comments enable row level security;

-- Every member, viewers included, can see, react and comment.
create policy "members see reactions" on public.reactions
  for select to authenticated using (public.my_trip_role(public.trip_of_media(media_id)) is not null);
create policy "members react as themselves" on public.reactions
  for insert to authenticated
  with check (user_id = auth.uid() and public.my_trip_role(public.trip_of_media(media_id)) is not null);
create policy "members take back their own reactions" on public.reactions
  for delete to authenticated using (user_id = auth.uid());

create policy "members see comments" on public.comments
  for select to authenticated using (public.my_trip_role(public.trip_of_media(media_id)) is not null);
create policy "members comment as themselves" on public.comments
  for insert to authenticated
  with check (author = auth.uid() and public.my_trip_role(public.trip_of_media(media_id)) is not null);
create policy "authors delete their own comments" on public.comments
  for delete to authenticated using (author = auth.uid());

revoke all on public.reactions, public.comments from anon, authenticated;
grant select, delete on public.reactions, public.comments to authenticated;
-- No user_id or author column: they always default to the signed-in user.
grant insert (media_id, emoji) on public.reactions to authenticated;
grant insert (media_id, body) on public.comments to authenticated;

revoke execute on function public.trip_of_media(uuid) from public, anon;
grant execute on function public.trip_of_media(uuid) to authenticated;
