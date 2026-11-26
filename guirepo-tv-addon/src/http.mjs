export const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const BLOCKED_KEYWORDS = [
  "sexy hot",
  "sexyhot",
  "playboy",
  "venus",
  "sextreme",
  "privê",
  "prive",
  "adulto",
  "extra +18",
  "+18",
  "xxx",
  "aviso.html"
];

export function isBlocked(text = "") {
  const lower = String(text).toLowerCase();
  return BLOCKED_KEYWORDS.some((kw) => lower.includes(kw));
}

export function stripDiacritics(str = "") {
  return String(str)
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}

export function decodeHtmlEntities(str = "") {
  return String(str)
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCharCode(parseInt(h, 16)));
}

export function stripTags(html = "") {
  return decodeHtmlEntities(String(html).replace(/<[^>]+>/g, " "))
    .replace(/\s+/g, " ")
    .trim();
}

export function resolveUrl(raw, baseUrl) {
  const trimmed = String(raw || "").trim().replace(/\\\//g, "/");
  if (!trimmed) return "";
  if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
  if (trimmed.startsWith("//")) return `https:${trimmed}`;
  const cleanBase = String(baseUrl || "").replace(/\/+$/, "");
  if (!cleanBase) return trimmed;
  return trimmed.startsWith("/") ? `${cleanBase}${trimmed}` : `${cleanBase}/${trimmed}`;
}

export function originOf(url, fallback = "") {
  try {
    const u = new URL(url);
    return `${u.protocol}//${u.host}`;
  } catch {
    return fallback;
  }
}

export function normalizeCategory(raw = "", fallback = "Variedades") {
  const c = stripDiacritics(String(raw).trim().toLowerCase());
  if (!c) return fallback;
  if (c.includes("esport") || c.includes("futebol") || c.includes("sport")) return "Esportes";
  if (c.includes("abert")) return "TV Aberta";
  if (c.includes("filme") || c.includes("serie")) return "Filmes e Séries";
  if (c.includes("infant") || c.includes("desenho")) return "Infantil";
  if (c.includes("notic")) return "Notícias";
  if (c.includes("doc")) return "Documentários";
  if (c.includes("relig")) return "Religiosos";
  if (c.includes("music")) return "Músicas";
  if (c.includes("varied") || c.includes("24h") || c.includes("destaq")) return "Variedades";
  return fallback;
}

/**
 * Normalizes channel names across providers so "SporTV HD", "SporTV 1 FHD",
 * and "SPORTV" merge into the same unified channel entry with multi-server streams.
 */
export function normalizeChannelKey(name = "") {
  let s = stripDiacritics(String(name).toLowerCase())
    .replace(/\[[^\]]*\]|\([^)]*\)/g, " ")
    .replace(/\b(assistir|ao\s+vivo|online|gratis|em|canal|full\s*hd|fhd|uhd|4k|hd|sd|h265|hevc|backup|op[cç][aã]o\s*\d+|sinal\s*\d+|server\s*\d+)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();

  // Split trailing digit from known channel prefixes ("espn2" -> "espn 2", "caze1" -> "caze 1")
  s = s.replace(/\b(sportv|espn|premiere|cazetv|caze|paramount\s*plus|paramountplus|combate|tnt|hbo|telecine|max)(\d+)\b/g, "$1 $2");

  // Common BR TV aliases so channels merge cleanly across providers
  s = s
    .replace(/^spor\s*tv\b/, "sportv")
    .replace(/^sportv\s*1$/, "sportv")
    .replace(/^espn\s*1$/, "espn")
    .replace(/^espn\s*brasil$/, "espn")
    .replace(/^premiere\s*clubes$/, "premiere 1")
    .replace(/^premiere$/, "premiere 1")
    .replace(/^band\s*sports$/, "bandsports")
    .replace(/^band\s*news$/, "bandnews")
    .replace(/^globo\s*news$/, "globonews")
    .replace(/^record\s*news$/, "recordnews")
    .replace(/^rede\s*tv$/, "redetv")
    .replace(/^tnt\s*sports\s*stadium$/, "tnt sports")
    .replace(/^telecine\s*pipoca$/, "tc pipoca")
    .replace(/^telecine\s*action$/, "tc action")
    .replace(/^telecine\s*premium$/, "tc premium")
    .replace(/^telecine\s*touch$/, "tc touch")
    .replace(/^telecine\s*fun$/, "tc fun")
    .replace(/^telecine\s*cult$/, "tc cult")
    .replace(/^paramountplus\b/, "paramount plus")
    .replace(/^caze(?:\s*tv)?(?:\s*1)?$/, "cazetv")
    .replace(/^caze(?:\s*tv)?\s*(\d+)$/, "cazetv $1")
    .replace(/\s+/g, "-")
    .replace(/^-+|-+$/g, "");

  return s || "canal";
}

export async function fetchText(url, { headers = {}, method = "GET", body, timeoutMs = 10000 } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method,
      body,
      redirect: "follow",
      signal: ctrl.signal,
      headers: {
        "User-Agent": USER_AGENT,
        "Accept-Language": "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        ...headers
      }
    });
    const text = await res.text();
    return { ok: res.ok, status: res.status, text, url: res.url, contentType: res.headers.get("content-type") || "" };
  } finally {
    clearTimeout(timer);
  }
}

export async function fetchJson(url, opts = {}) {
  const res = await fetchText(url, {
    ...opts,
    headers: { Accept: "application/json, text/plain, */*", ...(opts.headers || {}) }
  });
  if (!res.ok) return null;
  try {
    return JSON.parse(res.text);
  } catch {
    return null;
  }
}

export function decodeBase64ToUtf8(input) {
  const clean = String(input || "")
    .trim()
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/\s+/g, "");
  if (!clean) return null;
  try {
    if (typeof Buffer !== "undefined") {
      return Buffer.from(clean, "base64").toString("utf8");
    }
    const bin = atob(clean);
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return new TextDecoder("utf-8").decode(bytes);
  } catch {
    return null;
  }
}

export function makeStream({ source, label, url, referer, origin, userAgent = USER_AGENT, extraHeaders = {} }) {
  const cleanUrl = String(url || "").trim().replace(/\\\//g, "/");
  if (!cleanUrl.startsWith("http")) return null;
  const reqHeaders = {
    "User-Agent": userAgent,
    Accept: "*/*",
    ...extraHeaders
  };
  if (referer) reqHeaders.Referer = referer;
  if (origin) reqHeaders.Origin = origin;
  else if (referer) {
    const orig = originOf(referer);
    if (orig) reqHeaders.Origin = orig;
  }
  return {
    source,
    label: label || source,
    url: cleanUrl,
    referer: referer || "",
    headers: reqHeaders
  };
}
