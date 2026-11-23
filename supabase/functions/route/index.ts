// Supabase Edge Function "route": turns the stops of a trip into a road route.
//
// The app sends up to 20 stops in travel order; this asks Stadia Maps (a hosted Valhalla road
// router) for the driving route through them and returns one entry per leg. It runs on the
// server so the Stadia key, set as the secret STADIA_API_KEY, never ships inside the app.
//
// Request:  POST {"points": [{"lat": 36.6, "lng": -121.9}, ...]}  with the user's sign-in token
// Response: {"legs": [{"shape": "<polyline, 6 decimals>", "seconds": 5400}, ...]}

const STADIA_URL = "https://api.stadiamaps.com/route/v1";
const MAX_POINTS = 20; // Valhalla's default limit for driving routes

type Point = { lat: number; lng: number };

export type Deps = {
  env: (name: string) => string | undefined;
  fetch: typeof fetch;
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function isPoint(p: unknown): p is Point {
  const { lat, lng } = (p ?? {}) as Record<string, unknown>;
  return typeof lat === "number" && typeof lng === "number" &&
    Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
}

export async function handle(req: Request, deps: Deps): Promise<Response> {
  if (req.method !== "POST") return json({ error: "Use POST" }, 405);

  // Only signed-in Postmarked users may spend the routing quota: ask Supabase who this token belongs to.
  const authorization = req.headers.get("Authorization");
  const apikey = req.headers.get("apikey");
  if (!authorization || !apikey) return json({ error: "Sign in first" }, 401);
  const user = await deps.fetch(`${deps.env("SUPABASE_URL")}/auth/v1/user`, {
    headers: { Authorization: authorization, apikey },
  });
  if (!user.ok) return json({ error: "Sign in first" }, 401);

  const body = await req.json().catch(() => null);
  const points: unknown = body?.points;
  if (!Array.isArray(points) || points.length < 2 || points.length > MAX_POINTS || !points.every(isPoint)) {
    return json({ error: `Send between 2 and ${MAX_POINTS} points as {"lat", "lng"}` }, 400);
  }

  const key = deps.env("STADIA_API_KEY");
  if (!key) return json({ error: "Road routing isn't set up yet (no STADIA_API_KEY secret)" }, 503);

  const routed = await deps.fetch(`${STADIA_URL}?api_key=${encodeURIComponent(key)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      // "break" makes every point a real stop, so Valhalla returns exactly one leg between each pair.
      locations: points.map((p) => ({ lat: p.lat, lon: p.lng, type: "break" })),
      costing: "auto",
      directions_type: "none", // the path only, no turn-by-turn text
    }),
  });
  if (!routed.ok) {
    // 400 from Valhalla usually means no road connects the points (an island); the app draws a straight line.
    return json({ error: `The road router answered ${routed.status}` }, routed.status === 400 ? 422 : 502);
  }
  const trip = (await routed.json())?.trip;
  if (!Array.isArray(trip?.legs) || trip.legs.length !== points.length - 1) {
    return json({ error: "The road router sent an unexpected answer" }, 502);
  }
  return json({
    legs: trip.legs.map((leg: { shape: string; summary: { time: number } }) => ({
      shape: leg.shape,
      seconds: Math.round(leg.summary.time),
    })),
  });
}

Deno.serve((req) => handle(req, { env: (name) => Deno.env.get(name), fetch }));
