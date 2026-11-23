-- MVP milestone 3: the map needs each photo's coordinates as plain numbers. The API returns
-- geography columns as binary, so these add readable ones: select=id,lat,lng
-- (a function taking a table row acts as an extra column).

create function public.lat(m public.media) returns double precision
language sql stable set search_path = '' as $$ select extensions.st_y(m.location::extensions.geometry) $$;

create function public.lng(m public.media) returns double precision
language sql stable set search_path = '' as $$ select extensions.st_x(m.location::extensions.geometry) $$;

revoke execute on function public.lat(public.media), public.lng(public.media) from public, anon;
grant execute on function public.lat(public.media), public.lng(public.media) to authenticated;
