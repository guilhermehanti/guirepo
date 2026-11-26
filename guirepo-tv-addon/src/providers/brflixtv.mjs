import {
  fetchJson,
  fetchText,
  isBlocked,
  makeStream,
  normalizeCategory,
  originOf,
  resolveUrl
} from "../http.mjs";

const MAIN_URL = "https://brflix.online";
const TIME_RE = /(?:T|\s|^)(\d{2}:\d{2})/;
const DATE_ISO_RE = /(\d{4})-(\d{2})-(\d{2})/;
const CONST_SRC_RE = /(?:const|var|let)\s+(?:src|SRC)\s*=\s*["'](https?:\/\/[^"']+)["']/;
const M3U8_RE = /https?:\/\/[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?/i;

function unwrapStreamProxyUrl(rawUrl) {
  const trimmed = String(rawUrl || "").trim().replace(/\\\//g, "/");
  if (!trimmed) return "";
  if (trimmed.includes("/api/stream-proxy") && trimmed.includes("url=")) {
    const encoded = trimmed.split("url=")[1].split("&")[0];
    try {
      const decoded = decodeURIComponent(encoded);
      if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded;
    } catch {
      // ignore
    }
  }
  return resolveUrl(trimmed, MAIN_URL);
}

function upgradePosterUrl(raw) {
  const s = resolveUrl(String(raw || "").trim(), MAIN_URL);
  if (!s) return null;
  if (s.includes("imagecache.365scores.com")) {
    return s.replace(/w_\d+,h_\d+/g, "w_320,h_320");
  }
  return s;
}

function isDisguisedTxtStream(url) {
  const lower = String(url || "").toLowerCase();
  return lower.endsWith(".txt") || lower.includes("error.txt") || lower.includes("cloudfront-net.lat");
}

function parseResolveJson(root) {
  if (!root || typeof root !== "object") return { directStreams: [], fallbackEmbeds: [] };
  const streams = [];
  const seen = new Set();

  const addStream = (raw, label) => {
    const unwrapped = unwrapStreamProxyUrl(raw);
    if (!unwrapped || seen.has(unwrapped)) return;
    seen.add(unwrapped);
    const ref = isDisguisedTxtStream(unwrapped) ? "https://w1.ebtv.lat/" : `${MAIN_URL}/`;
    streams.push({ label, url: unwrapped, referer: ref });
  };

  if (Array.isArray(root.directStreams)) {
    root.directStreams.forEach((obj, i) => {
      if (!obj) return;
      const src = obj.src || obj.proxiedUrl || "";
      const label = obj.label || obj.provider || `Sinal ${i + 1}`;
      addStream(src, label);
    });
  }
  if (root.hlsUrl) {
    addStream(root.hlsUrl, root.hlsProvider || "BRFlix HD");
  }

  const standard = streams.filter((s) => !isDisguisedTxtStream(s.url));
  const finalStreams = standard.length > 0 ? standard : streams;

  const fallbacks = [];
  if (Array.isArray(root.fallbackEmbeds)) {
    root.fallbackEmbeds.forEach((obj, i) => {
      const embedUrl = String(obj?.embedUrl || "").replace(/\\\//g, "/").trim();
      const provider = String(obj?.provider || `Servidor ${i + 1}`).trim();
      if (embedUrl.startsWith("http")) fallbacks.push({ provider, embedUrl });
    });
  }
  return { directStreams: finalStreams, fallbackEmbeds: fallbacks };
}

export async function getBrFlixChannels() {
  try {
    const root = await fetchJson(`${MAIN_URL}/api/live/channels`, {
      headers: { Referer: `${MAIN_URL}/` },
      timeoutMs: 9000
    });
    const arr = Array.isArray(root?.data) ? root.data : [];
    const seen = new Set();
    const out = [];
    for (const obj of arr) {
      if (!obj || obj.is_active === false) continue;
      const id = String(obj.id || "").trim();
      const name = String(obj.name || "").trim();
      const rawCat = String(obj.category || "").trim();
      if (!id || !name || isBlocked(`${name} ${id} ${rawCat}`) || seen.has(id)) continue;
      seen.add(id);
      const rawLogo = obj.logo_url || obj.logo || obj.image || "";
      out.push({
        provider: "brflixtv",
        providerLabel: "BRFlix TV",
        id,
        name,
        category: normalizeCategory(rawCat, "Variedades"),
        poster: resolveUrl(rawLogo, MAIN_URL) || null,
        target: { kind: "channel", slug: id }
      });
    }
    return out;
  } catch {
    return [];
  }
}

export async function getBrFlixEvents() {
  try {
    const root = await fetchJson(`${MAIN_URL}/api/live/eventos`, {
      headers: { Referer: `${MAIN_URL}/futebol` },
      timeoutMs: 9000
    });
    const arr = Array.isArray(root?.data) ? root.data : [];
    const out = [];
    for (const obj of arr) {
      if (!obj) continue;
      const id = String(obj.id || "").trim();
      const slug = String(obj.slug || id.replace(/^e\//, "")).trim();
      const title = String(obj.title || "").trim();
      if (!title || !slug || isBlocked(title)) continue;
      const rawStatus = String(obj.status || "").trim().toLowerCase();
      if (["finished", "ended", "encerrado", "cancelled"].includes(rawStatus)) continue;

      const isLive =
        ["live", "ao_vivo", "ao vivo"].includes(rawStatus) ||
        Boolean(obj.is_live) ||
        Boolean(obj.isLive);
      const competition = String(obj.competition || "").trim();
      const startTimeRaw = String(obj.start_time || obj.startTime || obj.time || obj.horario || "").trim();
      const timeStr = (TIME_RE.exec(startTimeRaw) || [])[1] || "";
      const dateM = DATE_ISO_RE.exec(startTimeRaw);
      const dateKey = dateM ? `${dateM[1]}${dateM[2]}${dateM[3]}` : "99999999";
      const sortKey = `${dateKey}${timeStr || "99:99"}`;

      const poster = upgradePosterUrl(
        obj.poster || obj.image || obj.banner || obj.event_logo || obj.home_logo || obj.time1 || obj.competition_logo || obj.away_logo || obj.time2 || ""
      );
      const directEmbed = String(obj.directEmbedUrl || obj.embed_url || obj.sources?.embedtv || "").trim() || null;
      const playUrl = String(obj.play_url || obj.play_event_direct_url || "").replace(/\\\//g, "/").trim() || null;

      const targets = [];
      if (Array.isArray(obj.embeds)) {
        obj.embeds.forEach((em, j) => {
          if (!em) return;
          const label = String(em.name || em.provider || `Opção ${j + 1}`).trim();
          const emSlug = String(em.slug || "").trim().replace(/^(?:canal|e)\//, "");
          const embedUrl = String(em.embed_url || em.embedUrl || "").replace(/\\\//g, "/").trim() || null;
          if (emSlug || embedUrl) targets.push({ label, slug: emSlug, embedUrl });
        });
      }
      if (directEmbed && !targets.some((t) => t.slug.toLowerCase() === directEmbed.toLowerCase())) {
        targets.unshift({
          label: directEmbed.toUpperCase(),
          slug: directEmbed.replace(/^(?:canal|e)\//, ""),
          embedUrl: playUrl
        });
      }

      out.push({
        provider: "brflixtv",
        providerLabel: "BRFlix TV",
        slug,
        title,
        competition,
        isLive,
        statusBadge: isLive ? "AO VIVO" : "EM BREVE",
        timeStr,
        sortKey,
        poster: poster ? resolveUrl(poster, MAIN_URL) : null,
        targets,
        playUrl
      });
    }
    return out;
  } catch {
    return [];
  }
}

async function resolveFallbackEmbed(embedUrl, label) {
  let current = embedUrl;
  let referer = `${MAIN_URL}/futebol`;
  for (let hop = 0; hop < 3; hop++) {
    try {
      const res = await fetchText(current, { headers: { Referer: referer }, timeoutMs: 8000 });
      const html = res.text || "";
      const origin = originOf(current, MAIN_URL);
      const srcM = CONST_SRC_RE.exec(html);
      const direct = (srcM && srcM[1]) || (M3U8_RE.exec(html) || [])[0];
      if (direct) {
        const st = makeStream({
          source: "BRFlix TV",
          label: `BRFlix TV • ${label}`,
          url: direct,
          referer: `${origin}/`,
          origin
        });
        return st ? [st] : [];
      }
      const ifr = /<iframe[^>]+src=["']([^"']+)["']/i.exec(html);
      if (!ifr || ifr[1].toLowerCase() === "about:blank") break;
      referer = current;
      current = resolveUrl(ifr[1].replace(/&amp;/g, "&"), origin);
    } catch {
      break;
    }
  }
  return [];
}

export async function resolveBrFlixChannel(slug) {
  try {
    const enc = encodeURIComponent(slug);
    const json = await fetchJson(`${MAIN_URL}/api/resolve/${enc}?directUrl=${enc}`, {
      headers: { Referer: `${MAIN_URL}/canal/${slug}` },
      timeoutMs: 9000
    });
    const parsed = parseResolveJson(json);
    const streams = parsed.directStreams
      .map((s) =>
        makeStream({
          source: "BRFlix TV",
          label: `BRFlix TV • ${s.label}`,
          url: s.url,
          referer: s.referer
        })
      )
      .filter(Boolean);
    if (streams.length > 0) return streams;

    const fbResults = await Promise.all(
      parsed.fallbackEmbeds.slice(0, 3).map((fb) => resolveFallbackEmbed(fb.embedUrl, fb.provider))
    );
    return fbResults.flat();
  } catch {
    return [];
  }
}

export async function resolveBrFlixEvent(event) {
  const streams = [];
  const seen = new Set();
  const slugTargets = (event.targets || []).filter((t) => t.slug).slice(0, 5);
  const fallbacks = [];

  if (slugTargets.length > 0) {
    const settled = await Promise.all(
      slugTargets.map(async (t) => {
        try {
          const enc = encodeURIComponent(t.slug);
          const json = await fetchJson(`${MAIN_URL}/api/resolve/${enc}?directUrl=${enc}`, {
            headers: { Referer: `${MAIN_URL}/futebol` },
            timeoutMs: 8000
          });
          return { label: t.label, parsed: parseResolveJson(json) };
        } catch {
          return { label: t.label, parsed: { directStreams: [], fallbackEmbeds: [] } };
        }
      })
    );
    for (const { label, parsed } of settled) {
      for (const s of parsed.directStreams) {
        if (seen.has(s.url)) continue;
        seen.add(s.url);
        const st = makeStream({
          source: "BRFlix TV",
          label: `BRFlix TV • ${label} (${s.label})`,
          url: s.url,
          referer: s.referer
        });
        if (st) streams.push(st);
      }
      for (const fb of parsed.fallbackEmbeds) {
        fallbacks.push({ label: `${label} (${fb.provider})`, embedUrl: fb.embedUrl });
      }
    }
  }

  if (streams.length === 0) {
    for (const t of event.targets || []) {
      if (t.embedUrl && t.embedUrl.startsWith("http")) {
        fallbacks.push({ label: t.label, embedUrl: t.embedUrl });
      }
    }
    if (event.playUrl && event.playUrl.startsWith("http")) {
      fallbacks.push({ label: "Principal", embedUrl: event.playUrl });
    }
    const uniqFb = [];
    const seenFb = new Set();
    for (const fb of fallbacks) {
      if (!seenFb.has(fb.embedUrl)) {
        seenFb.add(fb.embedUrl);
        uniqFb.push(fb);
      }
    }
    const fbOut = await Promise.all(uniqFb.slice(0, 4).map((fb) => resolveFallbackEmbed(fb.embedUrl, fb.label)));
    for (const st of fbOut.flat()) {
      if (!seen.has(st.url)) {
        seen.add(st.url);
        streams.push(st);
      }
    }
  }
  return streams;
}
