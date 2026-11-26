import {
  decodeBase64ToUtf8,
  fetchJson,
  fetchText,
  isBlocked,
  makeStream,
  normalizeCategory,
  originOf,
  resolveUrl
} from "../http.mjs";

const MAIN_URL = "https://tvacabo.top";
const DIRECT_SRC_RE = /(?:const|var|let)\s+(?:src|SRC)\s*=\s*["'](https?:\/\/[^"']+)["']/;
const M3U8_RE = /https?:\/\/[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?/i;
const DATA_C_RE = /data-c=["']([^"']+)["']/i;
const OBF_ARRAY_RE = /var\s+\w+\s*=\s*\[([\s\S]*?)\]\s*;/;
const OBF_ITEM_RE = /"([A-Za-z0-9+/=]{8,})"/g;
const OBF_OFFSET_RE = /fromCharCode\s*\([\s\S]*?-\s*(\d+)\s*\)/;
const SOURCES_RE = /var\s+sources\s*=\s*(\[[\s\S]*?\]);/;

function unwrapWrapperUrl(rawUrl) {
  const trimmed = String(rawUrl || "").trim();
  const keys = ["iframe.html?url=", "player.html?url=", "embed.html?src="];
  let key = keys.find((k) => trimmed.includes(k));
  if (!key && trimmed.includes("anym3u8player.com") && trimmed.includes("url=")) key = "url=";
  if (!key) return trimmed;
  const inner = trimmed.split(key)[1] || "";
  if (/^https?%3A/i.test(inner)) {
    try {
      return decodeURIComponent(inner.split("&")[0]).trim();
    } catch {
      return inner.trim();
    }
  }
  return inner.trim();
}

function redeCanaisLiveFallback(url) {
  if (!/redecanaistv\./i.test(url) || !/canal=/i.test(url)) return null;
  const slug = url.split(/canal=/i)[1].split("&")[0].trim();
  return slug ? `https://redecanais.sk/canais-de-tv/player3/live.php?etv=${slug}` : null;
}

function decodeObfuscatedCharArray(html) {
  if (!html.includes("fromCharCode") || !html.includes("atob")) return null;
  const offM = OBF_OFFSET_RE.exec(html);
  const arrM = OBF_ARRAY_RE.exec(html);
  if (!offM || !arrM) return null;
  const offset = Number(offM[1]);
  const items = [];
  let m;
  OBF_ITEM_RE.lastIndex = 0;
  while ((m = OBF_ITEM_RE.exec(arrM[1])) !== null) items.push(m[1]);
  if (items.length < 20) return null;
  let out = "";
  for (const item of items) {
    const decoded = decodeBase64ToUtf8(item);
    if (!decoded) return null;
    const digits = decoded.replace(/\D+/g, "");
    if (!digits) return null;
    const code = Number(digits) - offset;
    if (code < 0 || code > 0xffff) return null;
    out += String.fromCharCode(code);
  }
  return out;
}

function extractDataCStreams(html) {
  const m = DATA_C_RE.exec(html);
  if (!m) return [];
  const reversed = m[1].trim().split("").reverse().join("");
  const jsonText = decodeBase64ToUtf8(reversed);
  if (!jsonText) return [];
  try {
    const root = JSON.parse(jsonText);
    const arr = Array.isArray(root?.u) ? root.u : [];
    return [...new Set(arr.map((u) => String(u || "").replace(/\\\//g, "/").trim()).filter((u) => /^https?:\/\//.test(u)))];
  } catch {
    return [];
  }
}

export async function getTvaCaboChannels() {
  try {
    const arr = await fetchJson(`${MAIN_URL}/json.php`, {
      headers: { Referer: `${MAIN_URL}/` },
      timeoutMs: 9000
    });
    if (!Array.isArray(arr)) return [];
    const seen = new Set();
    const out = [];
    for (const obj of arr) {
      const name = String(obj?.nome || "").trim();
      const url = String(obj?.url || "").trim();
      const rawCat = String(obj?.categoria || "").trim();
      if (!name || !url || isBlocked(`${name} ${url}`) || /esporte\.html/i.test(url) || name.toUpperCase() === "JOGOS DO DIA") {
        continue;
      }
      const dedup = `${name}|${url}`;
      if (seen.has(dedup)) continue;
      seen.add(dedup);
      out.push({
        provider: "tvacabo",
        providerLabel: "TV a Cabo",
        id: encodeURIComponent(name.toLowerCase()),
        name,
        category: normalizeCategory(rawCat, "Variedades"),
        poster: `${MAIN_URL}/favicon.png`,
        target: { kind: "tvacabo", url }
      });
    }
    return out;
  } catch {
    return [];
  }
}

export async function getTvaCaboEvents() {
  try {
    const page = await fetchText(`${MAIN_URL}/esporte.html`, {
      headers: { Referer: `${MAIN_URL}/` },
      timeoutMs: 8000
    });
    const apiM = /https?:\/\/[^\s"'<>\\]+\/sports\b/i.exec(page.text || "");
    const apiUrl = (apiM && apiM[0]) || "https://api.reidoscanais.im/sports";
    const root = await fetchJson(apiUrl, {
      headers: { Referer: `${MAIN_URL}/esporte.html` },
      timeoutMs: 9000
    });
    const arr = Array.isArray(root?.data) ? root.data : [];
    const out = [];
    for (const obj of arr) {
      const id = String(obj?.id || "").trim();
      const title = String(obj?.title || "").trim();
      if (!id || !title || isBlocked(title)) continue;
      const status = String(obj?.status || "").trim().toLowerCase();
      if (["finished", "ended", "encerrado", "cancelled"].includes(status)) continue;
      const isLive = ["live", "ao_vivo", "ao vivo"].includes(status);
      const competition = String(obj?.competition || "").trim();
      const startTime = String(obj?.start_time || "").trim();
      const timeStr = (/\b(\d{2}:\d{2})\b/.exec(startTime) || [])[1] || "";
      const embeds = Array.isArray(obj?.embeds)
        ? obj.embeds
            .map((em, j) => ({
              provider: String(em?.provider || `Opção ${j + 1}`).trim(),
              url: String(em?.embed_url || "").replace(/\\\//g, "/").trim()
            }))
            .filter((em) => em.url.startsWith("http"))
        : [];
      if (embeds.length === 0) continue;
      out.push({
        provider: "tvacabo",
        providerLabel: "TV a Cabo",
        slug: `tvacabo-${id}`,
        title,
        competition,
        isLive,
        statusBadge: isLive ? "AO VIVO" : "EM BREVE",
        timeStr,
        sortKey: String(obj?.start_timestamp || 9999999999),
        poster: String(obj?.poster || "").trim() || `${MAIN_URL}/favicon.png`,
        embeds
      });
    }
    return out;
  } catch {
    return [];
  }
}

async function followPlayerChain(startUrl, initialReferer, label) {
  let currentUrl = startUrl;
  let referer = initialReferer;

  for (let hop = 0; hop <= 3; hop++) {
    try {
      if (/(?:dailymotion\.com|youtube\.com|youtu\.be|twitch\.tv)/i.test(currentUrl)) return [];
      const res = await fetchText(currentUrl, { headers: { Referer: referer }, timeoutMs: 8000 });
      const rawHtml = res.text || "";
      const html = decodeObfuscatedCharArray(rawHtml) || rawHtml;
      const origin = originOf(currentUrl, MAIN_URL);

      // Pattern 0A: mastruzcomleite Clappr data-c reversed base64
      const dataC = extractDataCStreams(html);
      if (dataC.length > 0) {
        return dataC
          .map((u, idx) =>
            makeStream({
              source: "TV a Cabo",
              label: dataC.length > 1 ? `${label} • Sinal ${idx + 1}` : label,
              url: u,
              referer: `${origin}/`,
              origin
            })
          )
          .filter(Boolean);
      }

      // Pattern 1: var sources = [...]
      const sourcesM = SOURCES_RE.exec(html);
      if (sourcesM) {
        try {
          const arr = JSON.parse(sourcesM[1]);
          const found = [];
          for (const s of arr) {
            let src = String(s?.src || "").replace(/\\\//g, "/").trim();
            if (!src && s?.ref) {
              const refPath = String(s.ref).replace(/\\\//g, "/").trim();
              const refUrl = refPath.startsWith("http") ? refPath : `${origin}${refPath}`;
              const refJson = await fetchJson(refUrl, {
                method: "POST",
                headers: { Referer: currentUrl, Origin: origin },
                timeoutMs: 7000
              });
              src = String(refJson?.src || "").replace(/\\\//g, "/").trim();
            }
            if (src) {
              const st = makeStream({
                source: "TV a Cabo",
                label: `${label} - ${s?.label || "HD"}`,
                url: src,
                referer: currentUrl,
                origin
              });
              if (st) found.push(st);
            }
          }
          if (found.length > 0) return found;
        } catch {
          // fall through
        }
      }

      // Pattern 2: const src / var SRC / direct .m3u8
      const srcM = DIRECT_SRC_RE.exec(html);
      const direct = (srcM && srcM[1]) || (M3U8_RE.exec(html) || [])[0];
      if (direct) {
        const st = makeStream({
          source: "TV a Cabo",
          label,
          url: direct,
          referer: `${origin}/`,
          origin
        });
        return st ? [st] : [];
      }

      // Pattern 3: nested iframe
      const ifr = /<iframe[^>]+src=["']([^"']+)["']/i.exec(html);
      if (!ifr || ifr[1].toLowerCase() === "about:blank") break;
      referer = currentUrl;
      currentUrl = resolveUrl(ifr[1].replace(/&amp;/g, "&"), origin);
    } catch {
      break;
    }
  }
  return [];
}

export async function resolveTvaCaboTarget(rawUrl, label = "TV a Cabo") {
  const unwrapped = unwrapWrapperUrl(rawUrl);
  if (!unwrapped) return [];
  if (/\.m3u8/i.test(unwrapped) && !/\.html/i.test(unwrapped)) {
    const st = makeStream({ source: "TV a Cabo", label, url: unwrapped, referer: `${MAIN_URL}/`, origin: MAIN_URL });
    return st ? [st] : [];
  }
  const primary = await followPlayerChain(unwrapped, `${MAIN_URL}/`, label);
  if (primary.length > 0) return primary;
  const rcFallback = redeCanaisLiveFallback(unwrapped);
  if (rcFallback) {
    return followPlayerChain(rcFallback, "https://redecanais.sk/canais-de-tv/", label);
  }
  return [];
}
