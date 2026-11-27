import { fromBase64Url } from "./http.mjs";
import { renderConfigurePage } from "./configure.mjs";
import { getMmaCatalog, getMmaMeta, getMmaStreams } from "./providers/watchmmafull.mjs";
import { F1_GENRES, getF1Catalog, getF1Meta, getF1Streams } from "./providers/watchf1full.mjs";
import {
  FOOTBALLIA_GENRES,
  getFootballiaCatalog,
  getFootballiaMeta,
  getFootballiaStreams,
  loginFootballia,
  searchFootballiaTeams
} from "./providers/footballia.mjs";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "*"
};

function jsonResponse(data, status = 200, maxAgeSec = 60) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": maxAgeSec > 0 ? `public, max-age=${maxAgeSec}` : "no-store",
      ...CORS_HEADERS
    }
  });
}

function parseConfigToken(token) {
  if (!token) return {};
  try {
    const parsed = JSON.parse(fromBase64Url(token));
    if (parsed && typeof parsed === "object") return parsed;
  } catch {}
  return {};
}

function parseExtraSegment(extraPart = "") {
  const params = {};
  if (!extraPart) return params;
  for (const pair of extraPart.split("&")) {
    const idx = pair.indexOf("=");
    if (idx <= 0) continue;
    params[decodeURIComponent(pair.slice(0, idx))] = decodeURIComponent(pair.slice(idx + 1));
  }
  return params;
}

export function getManifest(config = {}) {
  const fbConfigured = Boolean(config?.fbEmail && config?.fbPassword);
  return {
    id: "com.guirepo.sports.replays",
    version: "1.0.0",
    name: fbConfigured ? "GuiRepo Sports Replays (Footballia OK)" : "GuiRepo Sports Replays",
    description:
      "Replays esportivos completos para NuvioTV e Stremio: WatchMMAFull (Eventos = Séries / Lutas = Episódios), WatchF1Full (F1/F2/F3/MotoGP agrupados por GP e categoria) e Footballia (jogos históricos com busca por time; configure sua conta gratuita em /configure).",
    logo: "https://watchmmafull.com/pwa-icon/192?v=2",
    types: ["series", "movie"],
    idPrefixes: ["grsp:"],
    resources: [
      "catalog",
      { name: "meta", types: ["series", "movie"], idPrefixes: ["grsp:"] },
      { name: "stream", types: ["series", "movie"], idPrefixes: ["grsp:"] }
    ],
    catalogs: [
      {
        type: "series",
        id: "grsp-mma",
        name: "MMA / UFC — WatchMMAFull",
        extra: [
          { name: "search", isRequired: false },
          { name: "skip", isRequired: false }
        ]
      },
      {
        type: "series",
        id: "grsp-f1",
        name: "F1, F2, F3 & MotoGP — WatchF1Full",
        extra: [
          { name: "genre", isRequired: false, options: F1_GENRES },
          { name: "search", isRequired: false },
          { name: "skip", isRequired: false }
        ]
      },
      {
        type: "movie",
        id: "grsp-footballia",
        name: "Footballia — Jogos Históricos",
        extra: [
          { name: "genre", isRequired: false, options: FOOTBALLIA_GENRES },
          { name: "skip", isRequired: false }
        ]
      },
      {
        type: "series",
        id: "grsp-footballia-teams",
        name: "Footballia — Times",
        extra: [{ name: "search", isRequired: true }]
      }
    ],
    behaviorHints: {
      configurable: true,
      configurationRequired: false
    }
  };
}

export async function handleRequest(request) {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  const reqUrl = new URL(request.url);
  const baseOrigin = `${reqUrl.protocol}//${reqUrl.host}`;
  let path = reqUrl.pathname;

  // Test login endpoint for /configure UI
  if (path === "/api/test-footballia" && request.method === "POST") {
    try {
      const body = await request.json();
      const email = String(body?.fbEmail || "").trim();
      const password = String(body?.fbPassword || "");
      if (!email || !password) return jsonResponse({ ok: false, error: "Informe e-mail e senha." }, 400, 0);
      await loginFootballia(email, password, true);
      return jsonResponse({ ok: true }, 200, 0);
    } catch (err) {
      return jsonResponse({ ok: false, error: err?.message || "Falha no login." }, 401, 0);
    }
  }

  // Optional /:config prefix (e.g. /eyJmYkVtYWls... /manifest.json | /configure | /catalog/...)
  let config = {};
  const cfgMatch = /^\/([^/]+)\/(manifest\.json|configure|catalog\/.+|meta\/.+|stream\/.+)$/i.exec(path);
  if (cfgMatch && !["catalog", "meta", "stream", "api"].includes(cfgMatch[1].toLowerCase())) {
    config = parseConfigToken(cfgMatch[1]);
    path = `/${cfgMatch[2]}`;
  }

  if (path === "/" || path === "/configure") {
    return new Response(renderConfigurePage(baseOrigin, config), {
      status: 200,
      headers: { "Content-Type": "text/html; charset=utf-8", ...CORS_HEADERS }
    });
  }

  if (path === "/health") {
    return jsonResponse({ status: "ok", addon: "GuiRepo Sports Replays", configure: `${baseOrigin}/configure` }, 200, 0);
  }

  if (path === "/manifest.json") {
    return jsonResponse(getManifest(config), 200, 120);
  }

  // /catalog/:type/:id(.json | /:extra.json)
  const catM = /^\/catalog\/([^/]+)\/([^/]+?)(?:\/([^/]+))?\.json$/i.exec(path);
  if (catM) {
    const [, , catalogId, extraRaw] = catM;
    const extra = parseExtraSegment(extraRaw || "");
    const search = (extra.search || "").trim();
    const genre = (extra.genre || "").trim();
    const skip = Math.max(0, parseInt(extra.skip || "0", 10) || 0);

    try {
      if (catalogId === "grsp-mma") {
        return jsonResponse({ metas: await getMmaCatalog({ search, skip }) }, 200, 120);
      }
      if (catalogId === "grsp-f1") {
        return jsonResponse({ metas: await getF1Catalog({ genre, search, skip }) }, 200, 120);
      }
      if (catalogId === "grsp-footballia") {
        return jsonResponse({ metas: await getFootballiaCatalog({ genre, skip }) }, 200, 180);
      }
      if (catalogId === "grsp-footballia-teams") {
        return jsonResponse({ metas: await searchFootballiaTeams(search) }, 200, 180);
      }
    } catch (err) {
      return jsonResponse({ metas: [], error: err?.message }, 200, 10);
    }
    return jsonResponse({ metas: [] }, 200, 60);
  }

  // /meta/:type/:id.json
  const metaM = /^\/meta\/([^/]+)\/(.+)\.json$/i.exec(path);
  if (metaM) {
    const id = decodeURIComponent(metaM[2]);
    try {
      let meta = null;
      if (id.startsWith("grsp:mma:")) meta = await getMmaMeta(id);
      else if (id.startsWith("grsp:f1:")) meta = await getF1Meta(id);
      else if (id.startsWith("grsp:fb:")) meta = await getFootballiaMeta(id, config, baseOrigin);
      return jsonResponse({ meta }, meta ? 200 : 404, 120);
    } catch (err) {
      return jsonResponse({ meta: null, error: err?.message }, 200, 10);
    }
  }

  // /stream/:type/:id.json
  const streamM = /^\/stream\/([^/]+)\/(.+)\.json$/i.exec(path);
  if (streamM) {
    const id = decodeURIComponent(streamM[2]);
    try {
      let streams = [];
      if (id.startsWith("grsp:mma:")) streams = await getMmaStreams(id);
      else if (id.startsWith("grsp:f1:")) streams = await getF1Streams(id);
      else if (id.startsWith("grsp:fb:")) streams = await getFootballiaStreams(id, config, baseOrigin);
      return jsonResponse({ streams }, 200, 0);
    } catch (err) {
      return jsonResponse({ streams: [], error: err?.message }, 200, 0);
    }
  }

  return jsonResponse({ error: "Not found" }, 404, 10);
}
