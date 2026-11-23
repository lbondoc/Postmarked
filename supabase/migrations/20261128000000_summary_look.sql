-- Summary filters and adjustments.

-- The filter and adjustments for a whole summary:
-- {"filter": "Golden", "strength": 0.8, "adjust": {"Warmth": 0.15}}. Null means none.
-- Each tile's own crop and look are stored in its entry in tiles, alongside "media":
-- {"media": "<id>", "crop": {"x": 0.5, "y": 0.4, "zoom": 1.6}, "look": {...}, "ownLook": false}.
alter table public.summaries add column look jsonb
  check (look is null or (jsonb_typeof(look) = 'object' and octet_length(look::text) <= 2000));

grant insert (look) on public.summaries to authenticated;
grant update (look) on public.summaries to authenticated;
