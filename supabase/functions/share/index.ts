// Supabase Edge Function "share": the read-only trip behind a share link, for people with no account (SPEC §7).
//
// The website's #/s/<token> page calls this with the token. If the token is live, it answers with the trip, its
// day notes and its media rows, plus signed links (1 hour) to thumb.jpg, display.jpg and poster.jpg only. It never
// links `original` (the camera file, which carries GPS) and never returns emails or account ids. It reads the
// database with the service role, which Supabase gives every Edge Function as SUPABASE_SERVICE_ROLE_KEY.
//
// Request:  POST {"token": "<22 characters>"}   (no sign-in)
// Response: {"trip": {...}, "notes": [{"day", "body"}], "media": [{"id", "kind", ..., "thumb", "display"|"poster"}]}

const TOKEN = /^[A-Za-z0-9_-]{22}$/;
const LINK_SECONDS = 60 * 60;

export type Deps = {
  env: (name: string) => string | undefined;
  fetch: typeof fetch;
};

type MediaRow = { id: string; kind: "photo" | "video"; [k: string]: unknown };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
  });
}

const notFound = () => json({ error: "This link doesn't work any more. Ask for a new one." }, 404);

export async function handle(req: Request, deps: Deps): Promise<Response> {
  // The website is on another origin, so the browser asks first.
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST",
        "Access-Control-Allow-Headers": "content-type, apikey, authorization, x-client-info",
      },
    });
  }
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  const token: unknown = (await req.json().catch(() => null))?.token;
  if (typeof token !== "string" || !TOKEN.test(token)) return notFound();

  const base = deps.env("SUPABASE_URL");
  const key = deps.env("SUPABASE_SERVICE_ROLE_KEY");
  if (!base || !key) return json({ error: "Sharing isn't set up on the server" }, 503);
  const headers = { apikey: key, Authorization: `Bearer ${key}` };
  const rows = async (path: string) => {
    const res = await deps.fetch(`${base}/rest/v1/${path}`, { headers });
    if (!res.ok) throw new Error(`database answered ${res.status}`);
    return await res.json() as Record<string, unknown>[];
  };

  try {
    const [share] = await rows(`trip_shares?select=trip_id&token=eq.${token}&revoked_at=is.null`);
    if (!share) return notFound();
    const trip = String(share.trip_id);

    const [details] = await rows(
      `trips?select=id,name,start_date,end_date,start_label,end_label,theme_id,theme_overrides&id=eq.${trip}`,
    );
    if (!details) return notFound();
    const notes = await rows(`day_notes?select=day,body&trip_id=eq.${trip}&order=day`);
    // The person's display name only: no contributor id, no email.
    const media = await rows(
      `media?select=id,kind,captured_at,utc_offset_minutes,lat,lng,dhash,sharpness,palette,` +
        `profile:profiles!contributor(display_name)&trip_id=eq.${trip}&order=captured_at`,
    ) as MediaRow[];

    // Which files each item may be seen through. Never `original` or the video file itself.
    const files = (m: MediaRow) => m.kind === "video" ? ["thumb.jpg", "poster.jpg"] : ["thumb.jpg", "display.jpg"];
    const paths = media.flatMap((m) => files(m).map((f) => `${trip}/${m.id}/${f}`));
    const signed = new Map<string, string>();
    if (paths.length > 0) {
      const res = await deps.fetch(`${base}/storage/v1/object/sign/media`, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ expiresIn: LINK_SECONDS, paths }),
      });
      if (!res.ok) throw new Error(`storage answered ${res.status}`);
      for (const s of await res.json() as { path: string; signedURL: string | null }[]) {
        if (s.signedURL) signed.set(s.path, `${base}/storage/v1${s.signedURL}`);
      }
    }

    return json({
      trip: details,
      notes,
      media: media.map((m) => {
        const links = Object.fromEntries(
          files(m).map((f) => [f.replace(".jpg", ""), signed.get(`${trip}/${m.id}/${f}`) ?? null]),
        );
        return { ...m, ...links };
      }),
    });
  } catch (e) {
    return json({ error: `Couldn't load the trip: ${(e as Error).message}` }, 502);
  }
}

Deno.serve((req) => handle(req, { env: (name) => Deno.env.get(name), fetch }));
