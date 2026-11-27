import {
  USER_AGENT,
  absUrl,
  cookieHeaderFromMap,
  fetchPage,
  getSetCookies,
  mergeCookieMap,
  stripTags
} from "../http.mjs";

const BASE = "https://footballia.net";
const DEFAULT_POSTER = "https://footballia.net/favicon.ico";

export const FOOTBALLIA_COMPETITIONS = {
  "Últimos Jogos": "home:latest",
  "Copa do Mundo": "/competitions/world-cup",
  "Champions League": "/competitions/champions-league",
  "Copa Libertadores": "/competitions/copa-libertadores",
  "Campeonato Brasileiro": "/competitions/campeonato-brasileiro",
  "Copa do Brasil": "/competitions/copa-do-brasil",
  "Campeonato Paulista": "/competitions/campeonato-paulista",
  "Premier League": "/competitions/premier-league",
  "La Liga": "/competitions/liga-1-division",
  "Serie A (Itália)": "/competitions/serie-a",
  Bundesliga: "/competitions/bundesliga"
};

export const FOOTBALLIA_GENRES = Object.keys(FOOTBALLIA_COMPETITIONS);

// In-memory session cache keyed by email (never persisted on disk)
const sessionCache = new Map();

function parseMatchTableRows(html) {
  const seen = new Set();
  const rows = [];
  for (const m of html.matchAll(/<tr[^>]*>([\s\S]*?)<\/tr>/gi)) {
    const row = m[1];
    const linkM = row.match(/<a[^>]+href="(\/matches\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/i);
    if (!linkM) continue;
    const href = linkM[1].trim();
    const slug = href.replace(/^\/matches\//, "");
    if (!slug || seen.has(slug)) continue;
    seen.add(slug);

    const names = [...linkM[2].matchAll(/<span[^>]+itemprop="name"[^>]*>([\s\S]*?)<\/span>/gi)]
      .map((x) => stripTags(x[1]))
      .filter(Boolean);
    const matchup = names.length >= 2 ? `${names[0]} vs ${names[1]}` : stripTags(linkM[2]);
    if (!matchup) continue;

    const date = stripTags((row.match(/<td[^>]*class="[^"]*playing_date[^"]*"[^>]*>([\s\S]*?)<\/td>/i) || [])[1] || "");
    const comp = stripTags((row.match(/<td[^>]*class="[^"]*competition[^"]*"[^>]*>([\s\S]*?)<\/td>/i) || [])[1] || "");
    const stage = stripTags((row.match(/<td[^>]*class="[^"]*stage[^"]*"[^>]*>([\s\S]*?)<\/td>/i) || [])[1] || "");
    const season = stripTags((row.match(/<td[^>]*class="[^"]*season[^"]*"[^>]*>([\s\S]*?)<\/td>/i) || [])[1] || "");

    const logoRaw = (linkM[2].match(/<img[^>]+src="([^"]+)"/i) || [])[1] || "";
    const logo = logoRaw ? absUrl(logoRaw.replace("/small_", "/medium_"), BASE) : DEFAULT_POSTER;
    const year = ((date || season).match(/\b(19|20)\d{2}\b/) || [])[0] || "";

    rows.push({
      slug,
      href,
      matchup,
      season,
      comp,
      stage,
      date,
      year,
      logo
    });
  }
  return rows;
}

export async function getFootballiaCatalog({ genre = "", skip = 0 } = {}) {
  const target = FOOTBALLIA_COMPETITIONS[genre] || "home:latest";
  if (target === "home:latest") {
    if (skip > 0) return [];
    const res = await fetchPage(BASE);
    const seen = new Set();
    const metas = [];
    for (const m of res.text.matchAll(/<a[^>]+href="(\/matches\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/gi)) {
      const slug = m[1].replace(/^\/matches\//, "").trim();
      if (!slug || seen.has(slug)) continue;
      const text = stripTags(m[2]);
      if (text.length < 4) continue;
      seen.add(slug);
      metas.push({
        id: `grsp:fb:match:${slug}`,
        type: "movie",
        name: text,
        poster: DEFAULT_POSTER,
        posterShape: "landscape",
        description: "Partida histórica completa no Footballia (requer conta gratuita configurada em /configure)."
      });
    }
    return metas;
  }

  const page = Math.floor(skip / 40) + 1;
  const url = page <= 1 ? `${BASE}${target}` : `${BASE}${target}?page=${page}`;
  const res = await fetchPage(url);
  return parseMatchTableRows(res.text).map((r) => ({
    id: `grsp:fb:match:${r.slug}`,
    type: "movie",
    name: r.season ? `${r.matchup} (${r.season})` : r.matchup,
    poster: r.logo,
    posterShape: "square",
    releaseInfo: r.year || undefined,
    description: [r.comp, r.season, r.stage, r.date].filter(Boolean).join(" • ") || undefined
  }));
}

// Search strictly by Team (as requested)
export async function searchFootballiaTeams(query = "") {
  const q = query.trim();
  if (!q) return [];
  const res = await fetchPage(`${BASE}/search_by_team?name=${encodeURIComponent(q)}`, {
    referer: `${BASE}/`,
    headers: {
      "X-Requested-With": "XMLHttpRequest",
      Accept: "application/json, text/javascript, */*; q=0.01"
    }
  });
  let arr = [];
  try {
    arr = JSON.parse(res.text);
  } catch {
    return [];
  }
  if (!Array.isArray(arr)) return [];
  return arr
    .filter((t) => t?.slug && (t?.name || t?.value))
    .map((t) => ({
      id: `grsp:fb:team:${t.slug}`,
      type: "series",
      name: t.name || t.value,
      poster: absUrl(t.logo_medium || t.logo_thumb || DEFAULT_POSTER, BASE),
      posterShape: "square",
      description: `Partidas históricas completas de ${t.name || t.value} no Footballia.`
    }));
}

export async function getFootballiaMeta(id, config, baseOrigin) {
  const hasLogin = Boolean(config?.fbEmail && config?.fbPassword);
  const loginNote = hasLogin
    ? "Conta Footballia configurada."
    : `⚠️ Requer conta gratuita do Footballia! Configure em ${baseOrigin}/configure e reinstale o addon no NuvioTV.`;

  if (id.startsWith("grsp:fb:team:")) {
    const slug = id.slice("grsp:fb:team:".length);
    const p1 = await fetchPage(`${BASE}/teams/${slug}`);
    const rawTeamTitle = stripTags((p1.text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || slug.replace(/-/g, " "));
    const teamTitle = rawTeamTitle.replace(/\s+full\s+matches$/i, "").trim() || rawTeamTitle;

    // Pick THIS team's own logo (match alt or most-frequent logo across rows, never first opponent!)
    const logoImgs = [...p1.text.matchAll(/<img[^>]+alt="([^"]*)"[^>]+src="([^"]*\/uploads\/team\/logo\/[^"]+)"|<img[^>]+src="([^"]*\/uploads\/team\/logo\/[^"]+)"[^>]+alt="([^"]*)"/gi)]
      .map((m) => ({ alt: (m[1] || m[4] || "").trim(), src: (m[2] || m[3] || "").trim() }));
    const byAlt = logoImgs.find((x) => {
      const a = x.alt.toLowerCase();
      const t = teamTitle.toLowerCase();
      return a && (a === t || a.includes(t) || t.includes(a));
    })?.src;
    const freq = new Map();
    for (const x of logoImgs) freq.set(x.src, (freq.get(x.src) || 0) + 1);
    const byFreq = [...freq.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
    const logoRaw = byAlt || byFreq || "";
    const teamLogo = logoRaw ? absUrl(logoRaw.replace("/small_", "/medium_"), BASE) : DEFAULT_POSTER;

    const allRows = [...parseMatchTableRows(p1.text)];
    // Fetch page 2 as well (up to 80 matches) if pagination exists
    if (/rel="next"/i.test(p1.text)) {
      try {
        const p2 = await fetchPage(`${BASE}/teams/${slug}?page=2`);
        const seen = new Set(allRows.map((x) => x.slug));
        for (const r of parseMatchTableRows(p2.text)) {
          if (!seen.has(r.slug)) allRows.push(r);
        }
      } catch {}
    }

    return {
      id,
      type: "series",
      name: teamTitle,
      poster: teamLogo,
      background: teamLogo,
      description: `Partidas históricas de ${teamTitle} no Footballia.\n\n${loginNote}`,
      videos: allRows.map((r, idx) => ({
        id: `grsp:fb:match:${r.slug}`,
        title: r.season ? `${r.matchup} (${r.season})` : r.matchup,
        season: 1,
        episode: idx + 1,
        thumbnail: r.logo || teamLogo,
        overview: [r.comp, r.season, r.stage, r.date].filter(Boolean).join(" • ") || undefined
      }))
    };
  }

  if (id.startsWith("grsp:fb:match:")) {
    const slug = id.slice("grsp:fb:match:".length);
    const res = await fetchPage(`${BASE}/matches/${slug}`);
    const title = stripTags((res.text.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i) || [])[1] || slug.replace(/-/g, " "));
    const logoRaw = (res.text.match(/<img[^>]+src="([^"]*\/uploads\/team\/logo\/[^"]+)"/i) || [])[1] || "";
    const poster = logoRaw ? absUrl(logoRaw.replace("/small_", "/medium_"), BASE) : DEFAULT_POSTER;
    return {
      id,
      type: "movie",
      name: title,
      poster,
      background: poster,
      description: `${title} • Footballia\n\n${loginNote}`
    };
  }

  return null;
}

export async function loginFootballia(email, password, forceRefresh = false) {
  const key = `${email}::${password.length}`;
  if (!forceRefresh && sessionCache.has(key)) {
    const cached = sessionCache.get(key);
    if (Date.now() - cached.at < 30 * 60 * 1000) return cached.cookieHeader;
  }

  const signInUrl = `${BASE}/users/sign_in?locale=en`;
  const getRes = await fetchPage(signInUrl);
  const tokenM =
    getRes.text.match(/name="authenticity_token"[^>]+value="([^"]+)"/i) ||
    getRes.text.match(/<meta[^>]+content="([^"]+)"[^>]+name="csrf-token"/i) ||
    getRes.text.match(/<meta[^>]+name="csrf-token"[^>]+content="([^"]+)"/i);
  if (!tokenM) throw new Error("Não foi possível obter CSRF token do Footballia.");

  const body = new URLSearchParams({
    utf8: "✓",
    authenticity_token: tokenM[1],
    "user[email]": email,
    "user[password]": password,
    "user[remember_me]": "1",
    commit: "Sign in"
  }).toString();

  const postRes = await fetch(signInUrl, {
    method: "POST",
    headers: {
      "User-Agent": USER_AGENT,
      "Content-Type": "application/x-www-form-urlencoded",
      Origin: BASE,
      Referer: signInUrl,
      Cookie: cookieHeaderFromMap(getRes.cookies)
    },
    body,
    redirect: "manual"
  });

  if (postRes.status < 300 || postRes.status >= 400) {
    throw new Error("E-mail ou senha inválidos no Footballia.");
  }

  const merged = mergeCookieMap(getRes.cookies, getSetCookies(postRes.headers));
  const cookieHeader = cookieHeaderFromMap(merged);
  sessionCache.set(key, { cookieHeader, at: Date.now() });
  return cookieHeader;
}

function extractMatchVideoUrls(html) {
  const fromNewVideo = [];
  const seen = new Set();
  for (const m of html.matchAll(/new\s+Video\s*\(\s*["']([^"']+)["']\s*\)/gi)) {
    try {
      const cleanB64 = m[1].replace(/\\n|\\r|\s+/g, "");
      const decoded = atob(cleanB64).trim();
      if (/^https?:\/\//i.test(decoded) && !seen.has(decoded)) {
        seen.add(decoded);
        fromNewVideo.push(decoded);
      }
    } catch {}
  }
  if (fromNewVideo.length) return fromNewVideo;

  const unescaped = html.replace(/\\\//g, "/");
  const primary = [
    ...new Set(
      [...unescaped.matchAll(/https?:\/\/videos\d*\.footballia\.net\/uploads\/video\/source_file\/[^"'\s\\<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s\\<>]*)?/gi)].map(
        (m) => m[0]
      )
    )
  ];
  if (primary.length) return primary;
  return [
    ...new Set(
      [...unescaped.matchAll(/["'](https?:\/\/[^"'\s\\]+?\.(?:mp4|m3u8)(?:\?[^"'\s\\]*)?)["']/gi)]
        .map((m) => m[1])
        .filter((u) => /footballia/i.test(u))
    )
  ];
}

export async function getFootballiaStreams(id, config, baseOrigin) {
  if (!id.startsWith("grsp:fb:match:")) return [];
  const slug = id.slice("grsp:fb:match:".length);

  if (!config?.fbEmail || !config?.fbPassword) {
    return [
      {
        name: "⚙️ Footballia — Login Necessário",
        title: `Crie/use sua conta gratuita em footballia.net e configure em:\n${baseOrigin}/configure\nDepois instale a URL gerada no NuvioTV (via conta Nuvio no celular para sincronizar na TV, ou direto em Configurações > Addons).`,
        externalUrl: `${baseOrigin}/configure`
      }
    ];
  }

  const matchUrl = `${BASE}/matches/${slug}`;
  let cookieHeader;
  try {
    cookieHeader = await loginFootballia(config.fbEmail, config.fbPassword, false);
  } catch (err) {
    return [
      {
        name: "⚠️ Footballia — Erro no Login",
        title: `${err.message}\nReconfigure suas credenciais em: ${baseOrigin}/configure`,
        externalUrl: `${baseOrigin}/configure`
      }
    ];
  }

  let page = await fetchPage(matchUrl, { referer: `${BASE}/`, cookie: cookieHeader });
  let urls = extractMatchVideoUrls(page.text);

  if (!urls.length) {
    try {
      cookieHeader = await loginFootballia(config.fbEmail, config.fbPassword, true);
      page = await fetchPage(matchUrl, { referer: `${BASE}/`, cookie: cookieHeader });
      urls = extractMatchVideoUrls(page.text);
    } catch {}
  }

  return urls.map((u, idx) => ({
    name: urls.length > 1 ? `Footballia • Parte ${idx + 1}` : "Footballia • Jogo Completo",
    title: urls.length > 1 ? `MP4 Direto (Parte ${idx + 1}/${urls.length})` : "MP4 Direto • Footballia",
    url: u
  }));
}
