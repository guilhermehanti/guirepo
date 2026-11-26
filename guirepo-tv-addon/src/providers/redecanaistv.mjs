import {
  decodeHtmlEntities,
  fetchText,
  isBlocked,
  makeStream,
  resolveUrl
} from "../http.mjs";

const MAIN_URL = "https://redecanais.sk";
const PLAYER_IFRAME_RE = /<iframe[^>]+src=["']([^"']*(?:player\d*\/live\.php|player\d*\/server\.php)[^"']*)["']/i;
const SRC_VAR_RE = /var\s+SRC\s*=\s*["']([^"']+)["']/;
const M3U8_RE = /https?:\/\/[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?/i;

function categorize(name) {
  const n = name.toLowerCase();
  if (["sportv", "espn", "premiere", "combate", "band sports", "bandsports", "cazé", "caze", "nosso futebol", "fox sports", "paramount", "tnt sports", "ufc", "nba", "nfl", "dazn", "futebol", "esporte", "sport"].some((k) => n.includes(k))) {
    return "Esportes";
  }
  if (["globo", "sbt", "record", "band", "redetv", "cultura", "aparecida", "canção nova", "cancao nova", "gazeta", "tv brasil", "futura"].some((k) => n.includes(k)) && !n.includes("news") && !n.includes("sports")) {
    return "TV Aberta";
  }
  if (["hbo", "telecine", "megapix", "tnt", "space", "warner", "sony", "axn", "amc", "cinemax", "universal", "studio universal", "syfy", "star channel", "fx", "paramount network", "usa", "a&e", "aie", "tcm", "film&arts", "canal brasil"].some((k) => n.includes(k))) {
    return "Filmes e Séries";
  }
  if (["cartoon", "disney", "nickelodeon", "nick", "gloob", "discovery kids", "tooncast", "adult swim", "boomerang", "cartoonito", "dreamworks", "infantil"].some((k) => n.includes(k))) {
    return "Infantil";
  }
  if (["news", "cnn", "jovem pan", "globonews", "bandnews", "record news", "bbc", "bloomberg"].some((k) => n.includes(k))) {
    return "Notícias";
  }
  if (["discovery", "history", "national geographic", "natgeo", "animal planet", "h2", "tlc", "hgtv", "id ", "investigação", "curta"].some((k) => n.includes(k))) {
    return "Documentários";
  }
  return "Variedades";
}

export async function getRedeCanaisTvChannels() {
  try {
    const res = await fetchText(`${MAIN_URL}/canais-de-tv/`, {
      headers: { Referer: `${MAIN_URL}/canais-de-tv/` },
      timeoutMs: 9000
    });
    if (!res.ok) return [];
    const html = res.text;

    const channels = [];
    const seen = new Set();
    const cardRe = /<div\b[^>]*class=["'][^"']*\bchannel\b[^"']*["'][^>]*>([\s\S]*?)<\/div>\s*(?=<div\b|<\/section|<\/div)/gi;
    // Simpler robust match: every <a ...> inside list-channels
    const listM = /class=["'][^"']*list-channels[^"']*["'][^>]*>([\s\S]*?)<\/section>/i.exec(html);
    const scope = listM ? listM[1] : html;
    const linkRe = /<a\b[^>]*href=["']([^"'#]+)["'][^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = linkRe.exec(scope)) !== null) {
      const href = resolveUrl(m[1], MAIN_URL);
      const inner = m[2];
      if (!href || seen.has(href) || !inner.includes("<img")) continue;

      const imgM = /<img\b[^>]*>/i.exec(inner);
      const imgTag = imgM ? imgM[0] : "";
      const dataSrcM = /\bdata-src=["']([^"']+)["']/i.exec(imgTag);
      const srcM = /\bsrc=["']([^"']+)["']/i.exec(imgTag);
      const altM = /\balt=["']([^"']+)["']/i.exec(imgTag);

      // Extract non-badge span inside .name
      const spans = [...inner.matchAll(/<span\b([^>]*)>([\s\S]*?)<\/span>/gi)]
        .filter((s) => !/\bbadge\b/i.test(s[1]))
        .map((s) => decodeHtmlEntities(s[2].replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim())
        .filter(Boolean);

      const name = spans[spans.length - 1] || decodeHtmlEntities((altM && altM[1]) || "").trim();
      if (!name || isBlocked(`${name} ${href}`)) continue;
      seen.add(href);

      const rawLogo = (dataSrcM && dataSrcM[1]) || (srcM && srcM[1]) || "";
      channels.push({
        provider: "redecanaistv",
        providerLabel: "RedeCanais TV",
        id: encodeURIComponent(href),
        name,
        category: categorize(name),
        poster: resolveUrl(rawLogo, MAIN_URL) || null,
        target: { kind: "redecanaistv", url: href }
      });
    }
    return channels;
  } catch {
    return [];
  }
}

export async function resolveRedeCanaisTvChannel(channelUrl) {
  try {
    const fullUrl = resolveUrl(channelUrl, MAIN_URL);
    if (!fullUrl) return [];
    const page = await fetchText(fullUrl, { headers: { Referer: `${MAIN_URL}/canais-de-tv/` }, timeoutMs: 8000 });
    const ifrM = PLAYER_IFRAME_RE.exec(page.text || "");
    if (!ifrM) return [];
    const iframeUrl = resolveUrl(ifrM[1], MAIN_URL);
    const player = await fetchText(iframeUrl, { headers: { Referer: fullUrl }, timeoutMs: 8000 });
    const playerHtml = player.text || "";
    const srcM = SRC_VAR_RE.exec(playerHtml);
    const streamUrl = (srcM && srcM[1].replace(/\\\//g, "/").trim()) || (M3U8_RE.exec(playerHtml) || [])[0];
    if (!streamUrl) return [];
    const st = makeStream({
      source: "RedeCanais TV",
      label: "RedeCanais TV",
      url: streamUrl,
      referer: `${MAIN_URL}/`,
      origin: MAIN_URL
    });
    return st ? [st] : [];
  } catch {
    return [];
  }
}
