import {
  decodeHtmlEntities,
  fetchText,
  isBlocked,
  makeStream,
  normalizeCategory,
  resolveUrl
} from "../http.mjs";

const MAIN_SITE = "https://embedtv.cc";
const BASE_URL = "https://w1.ebtv.lat";
const BLOCKED_IDS = new Set(["sexyhot", "playboy"]);

function fixImageUrl(url = "") {
  const raw = String(url || "").trim();
  if (!raw) return `${MAIN_SITE}/assets/icon.png`;
  if (raw.includes("cloudfront.net")) {
    return `${raw.split("?")[0]}?width=300&height=170&fit=crop&format=webp`;
  }
  return resolveUrl(raw, MAIN_SITE);
}

function normalizePlayerUrl(raw, channelId) {
  const trimmed = String(raw || "").trim();
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
  if (trimmed.startsWith("//")) return `https:${trimmed}`;
  if (trimmed) return `https://${trimmed}`;
  return `${BASE_URL}/${channelId}`;
}

export async function getEmbedTvCatalog() {
  try {
    const res = await fetchText(MAIN_SITE, { timeoutMs: 9000 });
    if (!res.ok) return { channels: [], events: [] };
    const html = res.text;

    const channels = [];
    const events = [];
    const sectionRe = /<section\b[^>]*>([\s\S]*?)<\/section>/gi;
    let secM;
    while ((secM = sectionRe.exec(html)) !== null) {
      const secBody = secM[1];
      const h2M = /<h2\b[^>]*>([\s\S]*?)<\/h2>/i.exec(secBody);
      if (!h2M) continue;
      const sectionTitle = decodeHtmlEntities(h2M[1].replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
      const isEvento = sectionTitle.toLowerCase() === "eventos";

      const artRe = /<article\b([^>]*)>([\s\S]*?)<\/article>/gi;
      let artM;
      while ((artM = artRe.exec(secBody)) !== null) {
        const attrs = artM[1];
        const body = artM[2];
        const idM = /data-id=["']([^"']+)["']/i.exec(attrs);
        const channelId = idM ? idM[1].trim() : "";
        if (!channelId || BLOCKED_IDS.has(channelId.toLowerCase())) continue;

        const buscaM = /data-busca=["']([^"']+)["']/i.exec(attrs);
        const imgTagM = /<img\b[^>]*>/i.exec(body);
        const imgTag = imgTagM ? imgTagM[0] : "";
        const altM = /\balt=["']([^"']+)["']/i.exec(imgTag);
        const dataSrcM = /\bdata-src=["']([^"']+)["']/i.exec(imgTag);
        const srcM = /\bsrc=["']([^"']+)["']/i.exec(imgTag);
        const rawImg = (dataSrcM && dataSrcM[1]) || (srcM && srcM[1]) || "";

        const baseTitle = decodeHtmlEntities((buscaM && buscaM[1]) || (altM && altM[1]) || "").trim();
        if (!baseTitle || isBlocked(`${baseTitle} ${channelId}`)) continue;

        const inputM = /<input\b[^>]*readonly[^>]*value=["']([^"']+)["']|<input\b[^>]*value=["']([^"']+)["'][^>]*readonly/i.exec(body);
        const inputVal = (inputM && (inputM[1] || inputM[2])) || "";
        const playerUrl = normalizePlayerUrl(inputVal, channelId);
        const poster = fixImageUrl(rawImg);

        if (isEvento) {
          const timeM = /\b(\d{2}:\d{2})\b/.exec(body.replace(/<[^>]+>/g, " "));
          const timeStr = timeM ? timeM[1] : "";
          const isLive = /ao\s*vivo|live/i.test(body);
          events.push({
            provider: "embedtv",
            providerLabel: "EmbedTV",
            slug: `embedtv-${channelId}`,
            title: baseTitle,
            competition: "EmbedTV",
            isLive,
            statusBadge: isLive ? "AO VIVO" : "EVENTO",
            timeStr,
            sortKey: `99999999${timeStr || "99:99"}`,
            poster,
            playerUrl
          });
        } else {
          channels.push({
            provider: "embedtv",
            providerLabel: "EmbedTV",
            id: channelId,
            name: baseTitle,
            category: normalizeCategory(sectionTitle, "Variedades"),
            poster,
            target: { kind: "embedtv", playerUrl }
          });
        }
      }
    }
    return { channels, events };
  } catch {
    return { channels: [], events: [] };
  }
}

const EMBEDTV_SLUG_MAP = {
  cazetv: "caze1",
  "cazetv-2": "caze2",
  "cazetv-3": "caze3",
  "cazetv-4": "caze4",
  "premiere-1": "premiere",
  "paramount-plus": "paramountplus",
  "paramount-plus-2": "paramountplus2"
};

export async function resolveEmbedTvBySlug(rawSlug, label = "EmbedTV") {
  const key = String(rawSlug || "").toLowerCase().trim();
  const mapped = EMBEDTV_SLUG_MAP[key] || key.replace(/[^a-z0-9]/g, "");
  if (!mapped || BLOCKED_IDS.has(mapped)) return [];
  return resolveEmbedTvPlayer(`${BASE_URL}/${mapped}`, label);
}

export async function resolveEmbedTvPlayer(playerUrl, label = "EmbedTV") {
  try {
    let cleanUrl = normalizePlayerUrl(String(playerUrl || "").split("?")[0], "");
    cleanUrl = cleanUrl.replace(/^https?:\/\/(?:ww\d+\.)?embedtv\.(?:cc|lat|cv)/i, BASE_URL);
    const res = await fetchText(cleanUrl, {
      headers: {
        Referer: `${MAIN_SITE}/`,
        Origin: MAIN_SITE,
        "Sec-Fetch-Dest": "iframe",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "same-site"
      },
      timeoutMs: 7000
    });
    if (!res.ok) return [];
    const html = res.text;
    if (!html || /Content Removed by DMCA/i.test(html)) return [];
    let streamUrl = null;

    const constM = /const\s+src\s*=\s*["'](https?:\/\/[^"']+)["']/.exec(html);
    if (constM) streamUrl = constM[1].replace(/\\\//g, "/");

    if (!streamUrl) {
      const spRe = /startPlayer\(\s*["'](https?:\/\/[^"']+)["']\s*\)/g;
      let m;
      while ((m = spRe.exec(html)) !== null) {
        const cand = m[1].replace(/\\\//g, "/");
        if (!cand.includes("live-chunks.mediacdn.net")) streamUrl = cand;
      }
    }

    if (!streamUrl) {
      const dsM = /data-stream=["'](https?:\/\/[^"']+)["']/i.exec(html);
      if (dsM) streamUrl = dsM[1].replace(/\\\//g, "/");
    }

    if (!streamUrl) return [];
    streamUrl = streamUrl.replace("xn--d1ma04s8hp12.cloudfront.lat", "d1ma04s8hp12.cloudfront.lat");

    const st = makeStream({
      source: "EmbedTV",
      label,
      url: streamUrl,
      referer: `${BASE_URL}/`,
      origin: BASE_URL,
      extraHeaders: {
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "cross-site"
      }
    });
    return st ? [st] : [];
  } catch {
    return [];
  }
}
