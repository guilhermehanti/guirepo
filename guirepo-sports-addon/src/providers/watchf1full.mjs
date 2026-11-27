import { absUrl, decodeHtml, fetchPage, fromBase64Url, stripTags, toBase64Url } from "../http.mjs";
import { extractFromHostUrl, resolveLkssBatch } from "../extractors.mjs";

const BASE = "https://watchf1full.com";
const DEFAULT_POSTER = "https://watchf1full.com/uploads/images/2026/08/32365e513609f68d5da404fe22a5a262.png";
export const F1_GENRES = ["Todas", "F1", "F2", "F3", "MotoGP", "Moto2", "Moto3"];

const SERIES_SEARCH_MAP = {
  F1: "f1",
  F2: "f2",
  F3: "f3",
  MotoGP: "motogp",
  Moto2: "moto2",
  Moto3: "moto3"
};

function detectSeriesToken(raw = "") {
  const cleaned = raw.trim().replace(/\b(19|20)\d{2}\b/g, "").trim();
  const key = cleaned.toLowerCase().replace(/\s+/g, "");
  const map = {
    f1: "F1",
    formula1: "F1",
    f2: "F2",
    formula2: "F2",
    f3: "F3",
    formula3: "F3",
    f1academy: "F1 Academy",
    motogp: "MotoGP",
    moto2: "Moto2",
    moto3: "Moto3",
    motoe: "MotoE",
    indycar: "IndyCar",
    indy: "IndyCar",
    indynxt: "Indy NXT",
    nascar: "NASCAR",
    nascarcup: "NASCAR",
    wec: "WEC",
    wrc: "WRC",
    supercars: "Supercars",
    wsbk: "WorldSBK",
    worldsbk: "WorldSBK"
  };
  return map[key] || null;
}

function normalizeGpKey(gp = "") {
  return gp
    .toLowerCase()
    .replace(/\bgrand\s+prix\b/g, "")
    .replace(/\bchampionship\b/g, "")
    .replace(/\bgp\b/g, "")
    .replace(/\b(19|20)\d{2}\b/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function parseReplayTitle(rawTitle, href) {
  const cleaned = decodeHtml(rawTitle).replace(/\.mp4$/i, "").trim();
  const parts = cleaned.split(/\s+-\s+/).map((s) => s.trim()).filter(Boolean);
  const year = (cleaned.match(/\b(19|20)\d{2}\b/) || [])[0] || "";
  const hasDateTail = parts.length > 0 && /\b(19|20)\d{2}\b/.test(parts[parts.length - 1]);
  const dateText = hasDateTail ? parts[parts.length - 1] : year;
  const coreParts = hasDateTail && parts.length >= 3 ? parts.slice(0, -1) : parts;

  if (coreParts.length >= 3) {
    const s0 = detectSeriesToken(coreParts[0]);
    const s1 = detectSeriesToken(coreParts[1]);
    if (s1) {
      return {
        url: absUrl(href, BASE),
        href,
        rawTitle: cleaned,
        session: coreParts[0],
        series: s1,
        gp: coreParts.slice(2).join(" - ").trim(),
        year,
        dateText
      };
    }
    if (s0) {
      return {
        url: absUrl(href, BASE),
        href,
        rawTitle: cleaned,
        session: coreParts[1],
        series: s0,
        gp: coreParts.slice(2).join(" - ").trim(),
        year,
        dateText
      };
    }
    for (let i = 0; i < coreParts.length; i++) {
      const si = detectSeriesToken(coreParts[i]);
      if (si) {
        return {
          url: absUrl(href, BASE),
          href,
          rawTitle: cleaned,
          session: coreParts.slice(0, i).join(" - ") || "Full Replay",
          series: si,
          gp: coreParts.slice(i + 1).join(" - ") || coreParts[coreParts.length - 1],
          year,
          dateText
        };
      }
    }
  } else if (coreParts.length === 2) {
    const s0 = detectSeriesToken(coreParts[0]);
    const s1 = detectSeriesToken(coreParts[1]);
    if (s0) {
      return { url: absUrl(href, BASE), href, rawTitle: cleaned, session: "Full Replay", series: s0, gp: coreParts[1], year, dateText };
    }
    if (s1) {
      return { url: absUrl(href, BASE), href, rawTitle: cleaned, session: coreParts[0], series: s1, gp: coreParts[0], year, dateText };
    }
  }

  const detectedSeries =
    /\bMotoGP\b/i.test(cleaned) ? "MotoGP" :
    /\bMoto2\b/i.test(cleaned) ? "Moto2" :
    /\bMoto3\b/i.test(cleaned) ? "Moto3" :
    /\b(F1|Formula\s*1)\b/i.test(cleaned) ? "F1" :
    /\b(F2|Formula\s*2)\b/i.test(cleaned) ? "F2" :
    /\b(F3|Formula\s*3)\b/i.test(cleaned) ? "F3" : "Motorsport";
  return { url: absUrl(href, BASE), href, rawTitle: cleaned, session: "Full Replay", series: detectedSeries, gp: cleaned, year, dateText };
}

function extractReplays(html) {
  const seen = new Set();
  const out = [];
  for (const m of html.matchAll(/<a[^>]+class="[^"]*rr-video-title[^"]*"[^>]+href="([^"]+\.html)"[^>]*title="([^"]+)"/gi)) {
    const href = m[1].trim();
    if (href.includes("browse-") || href.includes("contact")) continue;
    if (seen.has(href)) continue;
    seen.add(href);
    out.push(parseReplayTitle(m[2], href));
  }
  if (!out.length) {
    for (const m of html.matchAll(/<a[^>]+href="(\/[^"]+_[0-9a-f]+\.html)"[^>]*>([\s\S]*?)<\/a>/gi)) {
      const href = m[1].trim();
      if (seen.has(href)) continue;
      seen.add(href);
      const t = stripTags(m[2]);
      if (t) out.push(parseReplayTitle(t, href));
    }
  }
  return out;
}

function sessionWeight(session = "") {
  const s = session.toLowerCase();
  if (/press conference/.test(s)) return 5;
  if (/1st practice|practice 1|practice one|fp1|nr\. 1/.test(s)) return 10;
  if (/2nd practice|practice 2|practice two|fp2|nr\. 2/.test(s)) return 20;
  if (/3rd practice|practice 3|practice three|fp3|nr\. 3/.test(s)) return 30;
  if (/practice/.test(s)) return 35;
  if (/sprint.*(qual|shootout)/.test(s)) return 40;
  if (/qualifying|quali/.test(s)) return 50;
  if (/sprint/.test(s)) return 60;
  if (/warm[\s-]*up/.test(s)) return 70;
  if (/pre[- ]race|build|grand prix sunday/.test(s)) return 75;
  if (/race/.test(s)) return 80;
  if (/post[- ]race|chequered|highlights|paddock/.test(s)) return 90;
  return 55;
}

function groupIntoGpMetas(replays, seriesFilter = "") {
  const grouped = new Map();
  for (const r of replays) {
    if (seriesFilter && seriesFilter !== "Todas" && r.series.toLowerCase() !== seriesFilter.toLowerCase()) continue;
    // Strict key: Series + GP + Year (never mixes F1 with F2/F3 or MotoGP with Moto2/Moto3!)
    const key = `${r.series.toLowerCase()}|${normalizeGpKey(r.gp)}|${r.year}`;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(r);
  }
  const metas = [];
  for (const sessions of grouped.values()) {
    const first = sessions[0];
    const displayGp = first.gp || first.rawTitle;
    const yearSuffix = first.year && !displayGp.includes(first.year) ? ` (${first.year})` : "";
    const payload = toBase64Url(JSON.stringify({ s: first.series, g: displayGp, y: first.year, u: first.href }));
    metas.push({
      id: `grsp:f1:gp:${payload}`,
      type: "series",
      name: `${first.series} • ${displayGp}${yearSuffix}`,
      poster: DEFAULT_POSTER,
      posterShape: "landscape",
      genres: [first.series],
      description: `${first.series} — ${displayGp}${yearSuffix} (${sessions.length} sessão(ões) encontradas)`
    });
  }
  return metas;
}

export async function getF1Catalog({ genre = "", search = "", skip = 0 } = {}) {
  if (search) {
    const p = await fetchPage(`${BASE}/search/${encodeURIComponent(search.trim())}`);
    return groupIntoGpMetas(extractReplays(p.text)).slice(skip, skip + 40);
  }

  const pageNum = Math.floor(skip / 15) + 1;
  const searchTerm = SERIES_SEARCH_MAP[genre];
  if (searchTerm) {
    const basePage = (pageNum - 1) * 3 + 1;
    const pages = await Promise.allSettled([
      fetchPage(basePage === 1 ? `${BASE}/search/${searchTerm}` : `${BASE}/search/${searchTerm}/${basePage}`),
      fetchPage(`${BASE}/search/${searchTerm}/${basePage + 1}`),
      fetchPage(`${BASE}/search/${searchTerm}/${basePage + 2}`)
    ]);
    const all = pages.flatMap((r) => (r.status === "fulfilled" ? extractReplays(r.value.text) : []));
    return groupIntoGpMetas(all, genre);
  }

  const basePage = (pageNum - 1) * 3 + 1;
  const pages = await Promise.allSettled([
    fetchPage(`${BASE}/newvideos.html?&page=${basePage}`),
    fetchPage(`${BASE}/newvideos.html?&page=${basePage + 1}`),
    fetchPage(`${BASE}/newvideos.html?&page=${basePage + 2}`)
  ]);
  const all = pages.flatMap((r) => (r.status === "fulfilled" ? extractReplays(r.value.text) : []));
  return groupIntoGpMetas(all, "");
}

export async function getF1Meta(id) {
  if (!id.startsWith("grsp:f1:gp:")) return null;
  const spec = JSON.parse(fromBase64Url(id.slice("grsp:f1:gp:".length)));
  const targetSeries = spec.s || "";
  const targetGp = spec.g || "";
  const targetYear = spec.y || "";
  const sampleHref = spec.u || "";
  const targetGpKey = normalizeGpKey(targetGp);

  const collected = new Map();
  const gpSearchTerm = targetGp.replace(/\bGP\b/gi, "").trim() || targetGp;

  const sources = await Promise.allSettled([
    fetchPage(`${BASE}/search/${encodeURIComponent(gpSearchTerm)}`),
    fetchPage(`${BASE}/newvideos.html?&page=1`),
    fetchPage(`${BASE}/newvideos.html?&page=2`)
  ]);

  for (const res of sources) {
    if (res.status !== "fulfilled") continue;
    for (const r of extractReplays(res.value.text)) {
      if (
        r.series.toLowerCase() === targetSeries.toLowerCase() &&
        normalizeGpKey(r.gp) === targetGpKey &&
        (!targetYear || r.year === targetYear)
      ) {
        collected.set(r.href, r);
      }
    }
  }

  if (!collected.size && sampleHref) {
    try {
      const sp = await fetchPage(absUrl(sampleHref, BASE));
      const t = stripTags((sp.text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || targetGp);
      collected.set(sampleHref, parseReplayTitle(t, sampleHref));
    } catch {}
  }

  const sorted = [...collected.values()].sort(
    (a, b) => sessionWeight(a.session) - sessionWeight(b.session) || a.rawTitle.localeCompare(b.rawTitle)
  );

  const videos = sorted.map((r, idx) => ({
    id: `grsp:f1:vid:${toBase64Url(r.href)}`,
    title: `${r.session}${r.dateText ? ` (${r.dateText})` : ""}`,
    season: 1,
    episode: idx + 1,
    thumbnail: DEFAULT_POSTER,
    overview: `${r.rawTitle} • Ao dar Play, aguarde ~11s enquanto os servidores são desbloqueados em paralelo.`
  }));

  const yearSuffix = targetYear && !targetGp.includes(targetYear) ? ` (${targetYear})` : "";
  return {
    id,
    type: "series",
    name: `${targetSeries} • ${targetGp}${yearSuffix}`,
    poster: DEFAULT_POSTER,
    background: DEFAULT_POSTER,
    genres: [targetSeries],
    description: `${targetSeries} — ${targetGp}${yearSuffix}\n\n📌 Escolha em Episódios qual sessão deseja assistir (Treino Livre / Practice, Classificação / Qualifying, Sprint, Corrida / Race, etc.). Ao dar Play, aguarde ~11s enquanto os servidores são desbloqueados.`,
    videos
  };
}

export async function getF1Streams(id) {
  if (!id.startsWith("grsp:f1:vid:")) return [];
  const href = fromBase64Url(id.slice("grsp:f1:vid:".length));
  const videoUrl = absUrl(href, BASE);

  const page = await fetchPage(videoUrl, { referer: `${BASE}/` });
  const buttons = [];
  const seen = new Set();
  for (const m of page.text.matchAll(/<a[^>]+class="[^"]*rr-direct-btn[^"]*"[^>]+href="(https?:\/\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
    const url = m[1].trim();
    if (seen.has(url)) continue;
    seen.add(url);
    const label = stripTags((m[2].match(/<strong[^>]*>([\s\S]*?)<\/strong>/i) || [])[1] || `Server ${buttons.length + 1}`);
    buttons.push({ label, url });
  }
  if (!buttons.length) return [];

  const lkssButtons = buttons.filter((b) => /lkss\.site/i.test(b.url));
  const directButtons = buttons.filter((b) => !/lkss\.site/i.test(b.url));

  const resolvedLkss = lkssButtons.length ? await resolveLkssBatch(lkssButtons, videoUrl) : [];
  const targets = [...directButtons, ...resolvedLkss];

  const extracted = await Promise.all(
    targets.map((t) => extractFromHostUrl(t.url, `WatchF1Full ${t.label}`))
  );
  return extracted.flat();
}
