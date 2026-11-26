import assert from "node:assert/strict";
import { handleRequest } from "../src/addon.mjs";

async function reqJson(path) {
  const res = await handleRequest(new Request(`http://127.0.0.1:7000${path}`));
  assert.equal(res.status, 200, `Expected 200 for ${path}, got ${res.status}`);
  return res.json();
}

async function run() {
  console.log("1. Testing /manifest.json...");
  const manifest = await reqJson("/manifest.json");
  assert.equal(manifest.id, "com.guirepo.tv.br");
  assert.ok(manifest.types.includes("channel"));
  console.log(`   OK: ${manifest.name} (${manifest.catalogs.length} catalogs)`);

  console.log("2. Testing /catalog/channel/grtv-canais.json (all 5 providers in parallel)...");
  const t0 = Date.now();
  const canals = await reqJson("/catalog/channel/grtv-canais.json");
  console.log(`   OK: ${canals.metas.length} unified channels (first page) in ${Date.now() - t0}ms`);
  assert.ok(canals.metas.length > 10, "Expected >10 unified channels");
  for (const sample of canals.metas.slice(0, 6)) {
    console.log(`   - [${sample.genres[0]}] ${sample.name} (${sample.id}) -> ${sample.description}`);
  }

  console.log("3. Testing /catalog/channel/grtv-agenda.json...");
  const agenda = await reqJson("/catalog/channel/grtv-agenda.json");
  console.log(`   OK: ${agenda.metas.length} live/upcoming sports events`);
  for (const sample of agenda.metas.slice(0, 5)) {
    console.log(`   - ${sample.name} (${sample.id})`);
  }

  console.log("4. Testing /catalog/channel/grtv-canais/search=sportv.json...");
  const search = await reqJson("/catalog/channel/grtv-canais/search=sportv.json");
  console.log(`   OK: ${search.metas.length} matches for "sportv"`);
  assert.ok(search.metas.length >= 1, "Expected SporTV match");

  const firstId = search.metas[0].id;
  console.log(`5. Testing /meta/channel/${firstId}.json and /stream/channel/${firstId}.json...`);
  const meta = await reqJson(`/meta/channel/${encodeURIComponent(firstId)}.json`);
  assert.equal(meta.meta.id, firstId);
  const streams = await reqJson(`/stream/channel/${encodeURIComponent(firstId)}.json`);
  console.log(`   OK: ${streams.streams.length} live stream(s) resolved for ${meta.meta.name}:`);
  for (const s of streams.streams) {
    console.log(`   * [${s.name}] ${s.title.replace(/\n/g, " | ")} -> ${s.url.slice(0, 100)}`);
  }
  assert.ok(streams.streams.length >= 1, "Expected at least 1 playable stream");
  console.log("\nALL SMOKE TESTS PASSED!");
}

run().catch((err) => {
  console.error("SMOKE TEST FAILED:", err);
  process.exit(1);
});
