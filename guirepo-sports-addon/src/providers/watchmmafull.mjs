import { absUrl, fetchPage, fromBase64Url, stripTags, toBase64Url } from "../http.mjs";
import { extractFromHostUrl } from "../extractors.mjs";

const BASE = "https://watchmmafull.com";
const DEFAULT_POSTER = "https://watchmmafull.com/pwa-icon/512?v=2";
const EMBED_RE = /https?:\/\/watchmmafull\.com\/embed\/[a-zA-Z0-9]+/g;

export async function getMmaCatalog({ search = "", skip = 0 } = {}) {
  if (search) {
    const q = search.trim();
    const seen = new Set();
    const metas = [];

    // 1) Match events on /events
    try {
      const evPage = await fetchPage(`${BASE}/events`);
      const tokens = q.toLowerCase().split(/\s+/).filter((t) => t.length >= 2);
      for (const m of evPage.text.matchAll(/<a[^>]+class="[^"]*wmf-event-card[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
        const href = m[1];
        const block = m[2];
        const title = stripTags((block.match(/<h3[^>]*>([\s\S]*?)<\/h3>/i) || [])[1] || "");
        if (!title) continue;
        const lower = title.toLowerCase();
        if (tokens.length && !tokens.every((t) => lower.includes(t))) continue;
        const id = `grsp:mma:ev:${toBase64Url(href)}`;
        if (seen.has(id)) continue;
        seen.add(id);
        const img = absUrl((block.match(/<img[^>]+src="([^"]+)"/i) || [])[1] || "", BASE);
        const desc = stripTags((block.match(/<p[^>]*>([\s\S]*?)<\/p>/i) || [])[1] || "");
        metas.push({ id, type: "series", name: title, poster: img || undefined, posterShape: "poster", description: desc || undefined });
      }
    } catch {}

    // 2) Official search (individual fights)
    try {
      const sp = await fetchPage(`${BASE}/search/${encodeURIComponent(q)}`);
      for (const m of sp.text.matchAll(/<li[^>]+class="[^"]*video-item-compact[^"]*"[^>]*>([\s\S]*?)<\/li>/gi)) {
        const block = m[1];
        const linkM = block.match(/<a[^>]+href="([^"]+\.html)"[^>]*>([\s\S]*?)<\/a>/i);
        if (!linkM) continue;
        const href = linkM[1];
        const title = stripTags(linkM[2]);
        if (!title) continue;
        const id = `grsp:mma:fight:${toBase64Url(href)}`;
        if (seen.has(id)) continue;
        seen.add(id);
        const img = absUrl((block.match(/<img[^>]+src="([^"]+)"/i) || [])[1] || "", BASE);
        metas.push({ id, type: "series", name: title, poster: img || undefined, posterShape: "landscape" });
      }
    } catch {}

    return metas.slice(skip, skip + 50);
  }

  const page = Math.floor(skip / 20) + 1;
  const url = page <= 1 ? `${BASE}/events` : `${BASE}/events/${page}`;
  const res = await fetchPage(url);
  const metas = [];
  for (const m of res.text.matchAll(/<a[^>]+class="[^"]*wmf-event-card[^"]*"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    const href = m[1];
    const block = m[2];
    const title = stripTags((block.match(/<h3[^>]*>([\s\S]*?)<\/h3>/i) || [])[1] || "");
    if (!title) continue;
    const img = absUrl((block.match(/<img[^>]+src="([^"]+)"/i) || [])[1] || "", BASE) || DEFAULT_POSTER;
    const org = stripTags((block.match(/<small[^>]*>([\s\S]*?)<\/small>/i) || [])[1] || "");
    const info = stripTags((block.match(/<p[^>]*>([\s\S]*?)<\/p>/i) || [])[1] || "");
    metas.push({
      id: `grsp:mma:ev:${toBase64Url(href)}`,
      type: "series",
      name: title,
      poster: img,
      posterShape: "poster",
      description: [org, info].filter(Boolean).join(" • ") || undefined
    });
  }
  return metas;
}

export async function getMmaMeta(id) {
  if (id.startsWith("grsp:mma:fight:")) {
    const path = fromBase64Url(id.slice("grsp:mma:fight:".length));
    const fullUrl = absUrl(path, BASE);
    const res = await fetchPage(fullUrl);
    const title = stripTags((res.text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || "MMA Fight");
    const poster = absUrl((res.text.match(/<meta[^>]+property="og:image"[^>]+content="([^"]+)"/i) || [])[1] || "", BASE);
    return {
      id,
      type: "series",
      name: title,
      poster: poster || undefined,
      videos: [
        {
          id,
          title,
          season: 1,
          episode: 1,
          thumbnail: poster || undefined
        }
      ]
    };
  }

  if (!id.startsWith("grsp:mma:ev:")) return null;
  const path = fromBase64Url(id.slice("grsp:mma:ev:".length));
  const fullUrl = absUrl(path, BASE);
  const res = await fetchPage(fullUrl);

  const title = stripTags((res.text.match(/<h1[^>]*class="[^"]*ft-event-title[^"]*"[^>]*>([\s\S]*?)<\/h1>/i) || res.text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || "MMA Event");
  const rawPoster = absUrl((res.text.match(/class="ft-event-poster"[^>]*>\s*<img[^>]+src="([^"]+)"/i) || [])[1] || "", BASE);
  const poster = rawPoster && !/\.avif(\?|$)/i.test(rawPoster) ? rawPoster : DEFAULT_POSTER;
  const metaParts = [...res.text.matchAll(/<div[^>]*class="[^"]*ft-meta[^"]*"[^>]*>([\s\S]*?)<\/div>/gi)]
    .map((m) => stripTags(m[1]))
    .filter(Boolean);

  const mainBouts = [];
  const prelimBouts = [];
  const earlyBouts = [];

  let currentSection = "main";
  const tokenRe = /<h2[^>]*class="[^"]*ft-section-title[^"]*"[^>]*>([\s\S]*?)<\/h2>|<article[^>]*class="[^"]*ft-bout[^"]*"[^>]*>([\s\S]*?)<\/article>/gi;
  for (const m of res.text.matchAll(tokenRe)) {
    if (m[1] !== undefined) {
      const h = stripTags(m[1]).toLowerCase();
      currentSection = h.includes("early") ? "early" : h.includes("prelim") ? "prelim" : "main";
      continue;
    }
    const block = m[2] || "";
    const linkM = block.match(/<h3[^>]*class="[^"]*ft-compact-title[^"]*"[^>]*>\s*<a[^>]+href="([^"]+\.html)"[^>]*>([\s\S]*?)<\/a>/i)
      || block.match(/<a[^>]+href="([^"]+\.html)"[^>]*>([\s\S]*?)<\/a>/i);
    if (!linkM) continue;
    const fightHref = linkM[1];
    const fightTitle = stripTags(linkM[2]);
    if (!fightTitle) continue;
    const rawThumb = absUrl((block.match(/class="ft-compact-avatar"[\s\S]*?<img[^>]+src="([^"]+)"/i) || [])[1] || "", BASE);
    // Android TV Coil/Glide decoders often render .avif as solid green; prefer standard .jpg/.png event poster!
    const thumb = (rawThumb && !/\.avif(\?|$)/i.test(rawThumb) ? rawThumb : "") || poster || DEFAULT_POSTER;
    const metas = [...block.matchAll(/class="[^"]*ft-compact-meta[^"]*"[^>]*>([\s\S]*?)<\/span>/gi)].map((x) => stripTags(x[1])).filter(Boolean);

    const target = currentSection === "prelim" ? prelimBouts : currentSection === "early" ? earlyBouts : mainBouts;
    const season = currentSection === "prelim" ? 2 : currentSection === "early" ? 3 : 1;
    const sectionTag = currentSection === "prelim" ? "Prelims" : currentSection === "early" ? "Early Prelims" : "Main Card";

    target.push({
      id: `grsp:mma:fight:${toBase64Url(fightHref)}`,
      title: `[${sectionTag}] ${fightTitle}`,
      season,
      episode: target.length + 1,
      thumbnail: thumb || undefined,
      overview: metas.join(" • ") || undefined
    });
  }

  const videos = [...mainBouts, ...prelimBouts, ...earlyBouts];
  const usageHint = "📌 Escolha em Temporadas (Season 1 = Main Card, Season 2 = Prelims) e em Episódios selecione qual luta deseja assistir.";
  return {
    id,
    type: "series",
    name: title,
    poster: poster || undefined,
    background: poster || undefined,
    description: [metaParts.join(" • "), usageHint].filter(Boolean).join("\n\n"),
    videos
  };
}

export async function getMmaStreams(id) {
  if (!id.startsWith("grsp:mma:fight:")) return [];
  const path = fromBase64Url(id.slice("grsp:mma:fight:".length));
  const fightUrl = absUrl(path, BASE);

  const page = await fetchPage(fightUrl, { referer: `${BASE}/` });
  const embedUrls = [...new Set(page.text.match(EMBED_RE) || [])];
  if (!embedUrls.length) return [];

  const iframeUrls = [
    ...new Set(
      (
        await Promise.all(
          embedUrls.map(async (eu) => {
            try {
              const r = await fetchPage(eu, { referer: fightUrl });
              return [...r.text.matchAll(/<iframe[^>]+src=["'](https?:\/\/[^"']+)["']/gi)].map((m) => m[1]);
            } catch {
              return [];
            }
          })
        )
      ).flat()
    )
  ];

  const results = await Promise.all(
    iframeUrls.map((url, idx) => extractFromHostUrl(url, `WatchMMAFull #${idx + 1}`))
  );
  return results.flat();
}
