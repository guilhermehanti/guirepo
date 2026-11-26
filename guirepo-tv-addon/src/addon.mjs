import {
  GENRES,
  formatEventTitle,
  getMetaById,
  getUnifiedChannels,
  getUnifiedEvents,
  resolveStreamsById
} from "./aggregator.mjs";
import { USER_AGENT, fetchText, originOf, stripDiacritics } from "./http.mjs";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "*"
};

export function getManifest() {
  return {
    id: "com.guirepo.tv.br",
    version: "1.0.0",
    name: "GuiRepo TV & Esportes BR",
    description:
      "Canais ao Vivo e Agenda Esportiva unificados (BRFlix TV, EmbedTV, TV a Cabo, RedeCanais TV e Rei dos Embeds).",
    logo: "https://brflix.online/favicon.png",
    types: ["channel"],
    idPrefixes: ["grtv:"],
    resources: [
      "catalog",
      { name: "meta", types: ["channel"], idPrefixes: ["grtv:"] },
      { name: "stream", types: ["channel"], idPrefixes: ["grtv:"] }
    ],
    catalogs: [
      {
        type: "channel",
        id: "grtv-agenda",
        name: "Agenda Esportiva (Ao Vivo e Em Breve)",
        showInHome: true,
        extra: [{ name: "search", isRequired: false }]
      },
      {
        type: "channel",
        id: "grtv-canais",
        name: "Canais ao Vivo BR",
        showInHome: true,
        extra: [
          { name: "genre", isRequired: false, options: GENRES },
          { name: "search", isRequired: false },
          { name: "skip", isRequired: false }
        ]
      },
      {
        type: "channel",
        id: "grtv-esportes",
        name: "TV Esportes",
        showInHome: true,
        extra: [{ name: "skip", isRequired: false }]
      },
      {
        type: "channel",
        id: "grtv-abertos",
        name: "TV Aberta",
        showInHome: true,
        extra: [{ name: "skip", isRequired: false }]
      },
      {
        type: "channel",
        id: "grtv-filmes",
        name: "Filmes e Séries ao Vivo",
        showInHome: true,
        extra: [{ name: "skip", isRequired: false }]
      },
      {
        type: "channel",
        id: "grtv-infantil",
        name: "Infantil & Desenhos",
        showInHome: true,
        extra: [{ name: "skip", isRequired: false }]
      }
    ],
    behaviorHints: {
      configurable: false,
      configurationRequired: false
    }
  };
}

function jsonResponse(data, status = 200, maxAgeSec = 60) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": `public, max-age=${maxAgeSec}`,
      ...CORS_HEADERS
    }
  });
}

function parseExtraSegment(extraPart = "") {
  const params = {};
  if (!extraPart) return params;
  for (const pair of extraPart.split("&")) {
    const idx = pair.indexOf("=");
    if (idx <= 0) continue;
    const k = decodeURIComponent(pair.slice(0, idx));
    const v = decodeURIComponent(pair.slice(idx + 1));
    params[k] = v;
  }
  return params;
}

function channelToMetaPreview(ch) {
  const providers = [...new Set(ch.sources.map((s) => s.providerLabel))];
  return {
    id: ch.id,
    type: "channel",
    name: ch.name,
    poster: ch.poster || undefined,
    posterShape: "landscape",
    genres: [ch.category],
    description: `${ch.category} • ${providers.length} fonte(s): ${providers.join(", ")}`
  };
}

function eventToMetaPreview(ev) {
  const providers = [...new Set(ev.entries.map((e) => e.providerLabel))];
  return {
    id: ev.id,
    type: "channel",
    name: formatEventTitle(ev),
    poster: ev.poster || undefined,
    posterShape: "landscape",
    genres: ["Esportes", ev.statusBadge],
    description: `${ev.competition ? `${ev.competition} • ` : ""}${providers.join(", ")}`
  };
}

function needsHlsProxy(s) {
  const u = String(s?.url || "").toLowerCase();
  return /\.txt(\?|$)|error\.txt|__index|bombrilhoso\.lat|cloudfront-net\.lat/i.test(u);
}

function toStremioStream(s, baseOrigin) {
  // Disguised .txt manifests and .woff segment CDNs (EmbedTV, Rei dos Embeds, RedeCanais)
  // are proxied through /hls.m3u8 + /seg.ts so ExoPlayer/NuvioTV always receives clean
  // application/vnd.apple.mpegurl + video/mp2t with required Referer/Origin headers.
  if (needsHlsProxy(s) && baseOrigin) {
    const proxyUrl = `${baseOrigin}/hls.m3u8?u=${encodeURIComponent(s.url)}&r=${encodeURIComponent(s.referer || "")}`;
    return {
      name: s.source,
      title: `${s.label}\nAo Vivo • HLS (Proxy)`,
      url: proxyUrl,
      behaviorHints: {
        notWebReady: true
      }
    };
  }
  return {
    name: s.source,
    title: `${s.label}\nAo Vivo • HLS`,
    url: s.url,
    behaviorHints: {
      notWebReady: true,
      proxyHeaders: {
        request: s.headers
      }
    }
  };
}

function buildUpstreamHeaders(referer) {
  const headers = { "User-Agent": USER_AGENT, Accept: "*/*" };
  if (referer) {
    headers.Referer = referer;
    const orig = originOf(referer);
    if (orig) headers.Origin = orig;
  }
  return headers;
}

async function handleHlsProxy(reqUrl, baseOrigin) {
  const target = reqUrl.searchParams.get("u") || "";
  const referer = reqUrl.searchParams.get("r") || "";
  if (!target.startsWith("http://") && !target.startsWith("https://")) {
    return new Response("Missing u param", { status: 400, headers: CORS_HEADERS });
  }
  const res = await fetchText(target, { headers: buildUpstreamHeaders(referer), timeoutMs: 10000 });
  if (!res.ok) {
    return new Response(`Upstream ${res.status}`, { status: 502, headers: CORS_HEADERS });
  }
  const finalBase = res.url || target;
  const isMaster = res.text.includes("#EXT-X-STREAM-INF");
  const segRef = referer || `${originOf(finalBase)}/`;

  const proxyUri = (absUrl, isPlaylist) => {
    const endpoint = isPlaylist ? "hls.m3u8" : "seg.ts";
    return `${baseOrigin}/${endpoint}?u=${encodeURIComponent(absUrl)}&r=${encodeURIComponent(segRef)}`;
  };

  const rewritten = res.text
    .split(/\r?\n/)
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        return line.replace(/URI="([^"]+)"/g, (_, uri) => {
          try {
            const abs = new URL(uri, finalBase).toString();
            const isPl = /\.m3u8(\?|$)|\.txt(\?|$)/i.test(abs);
            return `URI="${proxyUri(abs, isPl)}"`;
          } catch {
            return `URI="${uri}"`;
          }
        });
      }
      try {
        const abs = new URL(trimmed, finalBase).toString();
        const isPl = isMaster || /\.m3u8(\?|$)|__index\.txt|error\.txt/i.test(abs);
        return proxyUri(abs, isPl);
      } catch {
        return line;
      }
    })
    .join("\n");

  return new Response(rewritten, {
    status: 200,
    headers: {
      "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
      "Cache-Control": "no-store",
      ...CORS_HEADERS
    }
  });
}

async function handleSegmentProxy(reqUrl) {
  const target = reqUrl.searchParams.get("u") || "";
  const referer = reqUrl.searchParams.get("r") || "";
  if (!target.startsWith("http://") && !target.startsWith("https://")) {
    return new Response("Missing u param", { status: 400, headers: CORS_HEADERS });
  }
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    const upstream = await fetch(target, {
      method: "GET",
      headers: buildUpstreamHeaders(referer),
      redirect: "follow",
      signal: controller.signal
    });
    clearTimeout(timer);
    if (!upstream.ok) {
      return new Response(`Upstream ${upstream.status}`, { status: 502, headers: CORS_HEADERS });
    }
    const buf = new Uint8Array(await upstream.arrayBuffer());
    // Strip fake image/font header prefix if present before first MPEG-TS 0x47 sync packet
    let offset = 0;
    if (buf.length > 376 && buf[0] !== 0x47) {
      const limit = Math.min(512, buf.length - 189);
      for (let i = 1; i < limit; i++) {
        if (buf[i] === 0x47 && buf[i + 188] === 0x47) {
          offset = i;
          break;
        }
      }
    }
    const body = offset > 0 ? buf.subarray(offset) : buf;
    const isMp4 = body.length >= 8 && body[4] === 0x66 && body[5] === 0x74 && body[6] === 0x79 && body[7] === 0x70;
    const ct = isMp4 ? "video/mp4" : "video/mp2t";
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": ct,
        "Content-Length": String(body.byteLength),
        "Cache-Control": "no-store",
        ...CORS_HEADERS
      }
    });
  } catch {
    return new Response("Segment fetch error", { status: 502, headers: CORS_HEADERS });
  }
}

export async function handleRequest(request) {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  const reqUrl = new URL(request.url);
  const path = reqUrl.pathname;
  const baseOrigin = `${reqUrl.protocol}//${reqUrl.host}`;

  if (path === "/" || path === "/health") {
    return jsonResponse(
      {
        status: "ok",
        addon: "GuiRepo TV & Esportes BR",
        manifest: `${baseOrigin}/manifest.json`
      },
      200,
      10
    );
  }

  if (path === "/manifest.json") {
    return jsonResponse(getManifest(), 200, 300);
  }

  if (path === "/hls.m3u8" || path === "/hls") {
    return handleHlsProxy(reqUrl, baseOrigin);
  }

  if (path === "/seg.ts" || path === "/seg") {
    return handleSegmentProxy(reqUrl);
  }

  // /catalog/:type/:id(.json | /:extra.json)
  const catM = /^\/catalog\/([^/]+)\/([^/]+?)(?:\/([^/]+))?\.json$/i.exec(path);
  if (catM) {
    const [, type, catalogId, extraRaw] = catM;
    if (type !== "channel" && type !== "tv") return jsonResponse({ metas: [] }, 200, 60);

    const extra = parseExtraSegment(extraRaw || "");
    const search = stripDiacritics((extra.search || "").trim().toLowerCase());
    const genre = (extra.genre || "").trim();
    const skip = Math.max(0, parseInt(extra.skip || "0", 10) || 0);
    const pageSize = 100;

    if (catalogId === "grtv-agenda") {
      let events = await getUnifiedEvents();
      if (search) {
        events = events.filter((ev) =>
          stripDiacritics(`${ev.title} ${ev.competition}`.toLowerCase()).includes(search)
        );
      }
      return jsonResponse({ metas: events.slice(skip, skip + pageSize).map(eventToMetaPreview) }, 200, 120);
    }

    let channels = await getUnifiedChannels();
    const fixedGenreMap = {
      "grtv-esportes": "Esportes",
      "grtv-abertos": "TV Aberta",
      "grtv-filmes": "Filmes e Séries",
      "grtv-infantil": "Infantil"
    };
    const activeGenre = fixedGenreMap[catalogId] || genre;

    if (activeGenre) {
      channels = channels.filter((c) => c.category.toLowerCase() === activeGenre.toLowerCase());
    }
    if (search) {
      channels = channels.filter((c) =>
        stripDiacritics(`${c.name} ${c.category}`.toLowerCase()).includes(search)
      );
    }

    return jsonResponse(
      { metas: channels.slice(skip, skip + pageSize).map(channelToMetaPreview) },
      200,
      300
    );
  }

  // /meta/:type/:id.json
  const metaM = /^\/meta\/([^/]+)\/(.+)\.json$/i.exec(path);
  if (metaM) {
    const id = decodeURIComponent(metaM[2]);
    const meta = await getMetaById(id);
    return jsonResponse({ meta: meta || null }, meta ? 200 : 404, 120);
  }

  // /stream/:type/:id.json
  const streamM = /^\/stream\/([^/]+)\/(.+)\.json$/i.exec(path);
  if (streamM) {
    const id = decodeURIComponent(streamM[2]);
    const streams = await resolveStreamsById(id);
    return jsonResponse(
      {
        streams: streams.map((s) => toStremioStream(s, baseOrigin))
      },
      200,
      30
    );
  }

  return jsonResponse({ error: "Not found" }, 404, 10);
}
