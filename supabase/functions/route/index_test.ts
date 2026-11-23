// Run with: deno test supabase/functions/route/
import { assertEquals } from "jsr:@std/assert@1";
import type { Deps } from "./index.ts";

// index.ts starts a web server when loaded; swap that out so the tests can call handle() directly.
Deno.serve = (() => {}) as unknown as typeof Deno.serve;
const { handle } = await import("./index.ts");

const sf = { lat: 37.7749, lng: -122.4194 };
const santaCruz = { lat: 36.9741, lng: -122.0308 };
const monterey = { lat: 36.6002, lng: -121.8947 };

/** A fake Supabase and Stadia; records what Stadia was asked. */
function deps(opts: { signedIn?: boolean; key?: string; stadia?: Response } = {}) {
  const sentToStadia: { url: string; body: any }[] = [];
  const d: Deps = {
    env: (name) => ({ SUPABASE_URL: "https://project.supabase.co", STADIA_API_KEY: opts.key ?? "secret" } as Record<string, string>)[name],
    fetch: (async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/auth/v1/user")) return new Response("{}", { status: opts.signedIn === false ? 401 : 200 });
      sentToStadia.push({ url, body: JSON.parse(String(init?.body)) });
      return opts.stadia ?? new Response(JSON.stringify({
        trip: { legs: [{ shape: "abc", summary: { time: 5400.4, length: 120 } }, { shape: "def", summary: { time: 2000, length: 40 } }] },
      }));
    }) as typeof fetch,
  };
  return { d, sentToStadia };
}

function request(body: unknown, headers: Record<string, string> = { Authorization: "Bearer user-token", apikey: "sb_publishable_x" }) {
  return new Request("https://project.supabase.co/functions/v1/route", {
    method: "POST",
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

Deno.test("routes through the points and returns one entry per leg", async () => {
  const { d, sentToStadia } = deps();
  const res = await handle(request({ points: [sf, santaCruz, monterey] }), d);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { legs: [{ shape: "abc", seconds: 5400 }, { shape: "def", seconds: 2000 }] });
  assertEquals(sentToStadia.length, 1);
  assertEquals(sentToStadia[0].url, "https://api.stadiamaps.com/route/v1?api_key=secret");
  assertEquals(sentToStadia[0].body.locations[0], { lat: 37.7749, lon: -122.4194, type: "break" });
  assertEquals(sentToStadia[0].body.costing, "auto");
});

Deno.test("refuses anyone who isn't signed in, without calling Stadia", async () => {
  for (const [req, d] of [
    [request({ points: [sf, santaCruz] }, {}), deps()],
    [request({ points: [sf, santaCruz] }), deps({ signedIn: false })],
  ] as const) {
    const res = await handle(req, d.d);
    assertEquals(res.status, 401);
    assertEquals(d.sentToStadia.length, 0);
  }
});

Deno.test("rejects bad input before calling Stadia", async () => {
  const bad = [
    "not json",
    {},
    { points: [sf] },
    { points: Array(21).fill(sf) },
    { points: [sf, { lat: 95, lng: 0 }] },
    { points: [sf, { lat: "37", lng: "-122" }] },
    { points: [sf, null] },
  ];
  for (const body of bad) {
    const { d, sentToStadia } = deps();
    const res = await handle(request(body), d);
    assertEquals(res.status, 400, JSON.stringify(body));
    assertEquals(sentToStadia.length, 0);
  }
});

Deno.test("says plainly when the key hasn't been set", async () => {
  const { d } = deps({ key: "" });
  const res = await handle(request({ points: [sf, santaCruz] }), d);
  assertEquals(res.status, 503);
});

Deno.test("no road between the points is a 422, other router trouble a 502", async () => {
  let res = await handle(request({ points: [sf, santaCruz] }), deps({ stadia: new Response("{}", { status: 400 }) }).d);
  assertEquals(res.status, 422);
  res = await handle(request({ points: [sf, santaCruz] }), deps({ stadia: new Response("{}", { status: 500 }) }).d);
  assertEquals(res.status, 502);
  // Fewer legs than asked for would misplace every line on the map.
  res = await handle(request({ points: [sf, santaCruz, monterey] }), deps({
    stadia: new Response(JSON.stringify({ trip: { legs: [{ shape: "abc", summary: { time: 1 } }] } })),
  }).d);
  assertEquals(res.status, 502);
});

Deno.test("only POST", async () => {
  const res = await handle(new Request("https://x/functions/v1/route"), deps().d);
  assertEquals(res.status, 405);
});
