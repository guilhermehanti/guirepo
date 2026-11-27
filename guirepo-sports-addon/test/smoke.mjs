import { handleRequest } from "../src/addon.mjs";

async function req(path) {
  const res = await handleRequest(new Request(`http://127.0.0.1:7001${path}`));
  const text = await res.text();
  return { status: res.status, text, json: () => JSON.parse(text) };
}

(async () => {
  console.log("=== 1. MANIFEST & /configure ===");
  const m = await req("/manifest.json");
  console.log("Manifest:", m.status, m.json().name, "catalogs:", m.json().catalogs.map((c) => c.id));
  const cfg = await req("/configure");
  console.log("/configure HTML:", cfg.status, "len:", cfg.text.length, "has Android TV guide:", cfg.text.includes("Android TV"));

  console.log("\n=== 2. WATCHMMAFULL ===");
  const mmaCat = (await req("/catalog/series/grsp-mma.json")).json();
  console.log("MMA events count:", mmaCat.metas.length, "first 3:", mmaCat.metas.slice(0, 3).map((x) => x.name));
  const mmaMeta = (await req(`/meta/series/${encodeURIComponent(mmaCat.metas[0].id)}.json`)).json().meta;
  const s1 = mmaMeta.videos.filter((v) => v.season === 1);
  const s2 = mmaMeta.videos.filter((v) => v.season === 2);
  console.log("MMA event meta:", mmaMeta.name, "Main Card (S1):", s1.length, "Prelims (S2):", s2.length);
  console.log("Sample episodes:", mmaMeta.videos.slice(0, 2).map((v) => `S${v.season}E${v.episode}: ${v.title}`));
  const mmaStreams = (await req(`/stream/series/${encodeURIComponent(mmaMeta.videos[0].id)}.json`)).json().streams;
  console.log("MMA fight streams:", mmaStreams.length, mmaStreams.map((s) => `${s.name} -> ${s.url?.slice(0, 70)}`));

  console.log("\n=== 3. WATCHF1FULL ===");
  const f1Cat = (await req("/catalog/series/grsp-f1.json")).json();
  console.log("F1/MotoGP GPs count:", f1Cat.metas.length, "first 8:", f1Cat.metas.slice(0, 8).map((x) => x.name));
  const f1Only = f1Cat.metas.find((x) => x.name.startsWith("F1 "));
  if (f1Only) {
    const f1Meta = (await req(`/meta/series/${encodeURIComponent(f1Only.id)}.json`)).json().meta;
    console.log("F1 GP meta:", f1Meta.name, "sessions:", f1Meta.videos.map((v) => v.title));
    const t0 = Date.now();
    const f1Streams = (await req(`/stream/series/${encodeURIComponent(f1Meta.videos[f1Meta.videos.length - 1].id)}.json`)).json().streams;
    console.log(`F1 Race streams (${Date.now() - t0}ms):`, f1Streams.length, f1Streams.map((s) => `${s.name} -> ${s.url?.slice(0, 70)}`));
  }

  console.log("\n=== 4. FOOTBALLIA ===");
  const fbCat = (await req("/catalog/movie/grsp-footballia/genre=Campeonato%20Brasileiro.json")).json();
  console.log("Footballia Brasileirão count:", fbCat.metas.length, "first 3:", fbCat.metas.slice(0, 3).map((x) => x.name));
  const fbTeamSearch = (await req("/catalog/series/grsp-footballia-teams/search=Corinthians.json")).json();
  console.log("Footballia Team search 'Corinthians':", fbTeamSearch.metas.slice(0, 3).map((x) => x.name));
  if (fbTeamSearch.metas[0]) {
    const fbTeamMeta = (await req(`/meta/series/${encodeURIComponent(fbTeamSearch.metas[0].id)}.json`)).json().meta;
    console.log("Team meta:", fbTeamMeta.name, "matches:", fbTeamMeta.videos.length, "first 2:", fbTeamMeta.videos.slice(0, 2).map((v) => v.title));
    const fbNoLogin = (await req(`/stream/movie/${encodeURIComponent(fbTeamMeta.videos[0].id)}.json`)).json().streams;
    console.log("Footballia unconfigured stream notice:", fbNoLogin);
  }
})();
