// Run with: deno test supabase/functions/share/
import { assert, assertEquals } from "jsr:@std/assert@1";
import type { Deps } from "./index.ts";

// index.ts starts a web server when loaded; swap that out so the tests can call handle() directly.
Deno.serve = (() => {}) as unknown as typeof Deno.serve;
const { handle } = await import("./index.ts");

const LIVE = "AbCdEfGhIjKlMnOpQrStUv";
const REVOKED = "ZyXwVuTsRqPoNmLkJiHgFe";
const TRIP = "11111111-1111-1111-1111-111111111111";

/** A fake database and storage holding one trip with a live link and a revoked one; records signing requests. */
function deps() {
  const signing: { expiresIn: number; paths: string[] }[] = [];
  const shares = [{ token: LIVE, trip_id: TRIP, revoked_at: null }, { token: REVOKED, trip_id: TRIP, revoked_at: "2026-11-01" }];
  const d: Deps = {
    env: (name) => ({ SUPABASE_URL: "https://project.supabase.co", SUPABASE_SERVICE_ROLE_KEY: "service" } as Record<string, string>)[name],
    fetch: (async (input: string | URL | Request, init?: RequestInit) => {
      const url = new URL(String(input));
      const q = url.searchParams;
      const rows = (body: unknown) => new Response(JSON.stringify(body));
      if (url.pathname === "/rest/v1/trip_shares") {
        const token = q.get("token")!.replace("eq.", "");
        const liveOnly = q.get("revoked_at") === "is.null";
        return rows(shares.filter((s) => s.token === token && (!liveOnly || s.revoked_at === null)).map((s) => ({ trip_id: s.trip_id })));
      }
      if (url.pathname === "/rest/v1/trips") return rows([{ id: TRIP, name: "Pacific Coast", start_date: "2026-09-12", end_date: "2026-09-15" }]);
      if (url.pathname === "/rest/v1/day_notes") return rows([{ day: "2026-09-12", body: "Fog lifted" }]);
      if (url.pathname === "/rest/v1/media") {
        assert(!q.get("select")!.includes("contributor,"), "the contributor id is not selected");
        return rows([
          { id: "p1", kind: "photo", captured_at: "2026-09-12T15:00:00Z", lat: 37.8, lng: -122.4, profile: { display_name: "Maya" } },
          { id: "v1", kind: "video", captured_at: "2026-09-13T15:00:00Z", lat: null, lng: null, profile: { display_name: "Jun" } },
        ]);
      }
      if (url.pathname === "/storage/v1/object/sign/media") {
        const body = JSON.parse(String(init?.body));
        signing.push(body);
        return rows(body.paths.map((p: string) => ({ path: p, signedURL: `/object/sign/media/${p}?token=t` })));
      }
      return new Response("not found", { status: 404 });
    }) as typeof fetch,
  };
  return { d, signing };
}

const ask = (token: unknown) =>
  new Request("https://project.supabase.co/functions/v1/share", { method: "POST", body: JSON.stringify({ token }) });

Deno.test("a live token gets the trip, notes and media with signed links", async () => {
  const { d } = deps();
  const res = await handle(ask(LIVE), d);
  assertEquals(res.status, 200);
  const body = await res.json();
  assertEquals(body.trip.name, "Pacific Coast");
  assertEquals(body.notes, [{ day: "2026-09-12", body: "Fog lifted" }]);
  assertEquals(body.media[0].thumb, `https://project.supabase.co/storage/v1/object/sign/media/${TRIP}/p1/thumb.jpg?token=t`);
  assertEquals(body.media[0].display, `https://project.supabase.co/storage/v1/object/sign/media/${TRIP}/p1/display.jpg?token=t`);
  assertEquals(body.media[1].poster, `https://project.supabase.co/storage/v1/object/sign/media/${TRIP}/v1/poster.jpg?token=t`);
  assertEquals(body.media[0].profile, { display_name: "Maya" });
});

Deno.test("a revoked or unknown token gets 404", async () => {
  for (const token of [REVOKED, "NoSuchTokenAtAllHere00", "too-short", 42, null]) {
    const { d, signing } = deps();
    const res = await handle(ask(token), d);
    assertEquals(res.status, 404, String(token));
    assertEquals(signing.length, 0, "nothing signed");
  }
});

Deno.test("the response has no original path and no emails", async () => {
  const { d, signing } = deps();
  const text = await (await handle(ask(LIVE), d)).text();
  assert(!text.includes("original"), "no original file");
  assert(!text.includes("@"), "no emails");
  assert(signing[0].paths.every((p) => /\/(thumb|display|poster)\.jpg$/.test(p)), "only thumb, display and poster are signed");
  assert(!signing[0].paths.some((p) => p.endsWith("video.mp4")));
});

Deno.test("signed links expire in 1 hour", async () => {
  const { d, signing } = deps();
  await handle(ask(LIVE), d);
  assertEquals(signing.length, 1, "one signing call for all files");
  assertEquals(signing[0].expiresIn, 3600);
});

Deno.test("only POST, and the browser's preflight is answered", async () => {
  const { d } = deps();
  assertEquals((await handle(new Request("https://x/functions/v1/share"), d)).status, 405);
  const pre = await handle(new Request("https://x/functions/v1/share", { method: "OPTIONS" }), d);
  assertEquals(pre.status, 200);
  assertEquals(pre.headers.get("Access-Control-Allow-Origin"), "*");
});
