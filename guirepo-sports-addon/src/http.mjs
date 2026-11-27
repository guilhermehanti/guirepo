export const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

export function decodeHtml(str = "") {
  return String(str)
    .replace(/&amp;/g, "&")
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(parseInt(dec, 10)));
}

export function stripTags(html = "") {
  return decodeHtml(String(html).replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
}

export function toBase64Url(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64Url(b64) {
  const norm = String(b64 || "").replace(/-/g, "+").replace(/_/g, "/");
  const pad = norm.length % 4 ? "=".repeat(4 - (norm.length % 4)) : "";
  const bin = atob(norm + pad);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

export function getSetCookies(headers) {
  if (typeof headers.getSetCookie === "function") {
    return headers.getSetCookie();
  }
  const raw = headers.get("set-cookie");
  return raw ? raw.split(/,(?=[^;\s]+=)/) : [];
}

export function mergeCookieMap(base = {}, setCookieList = []) {
  const out = { ...base };
  for (const item of setCookieList) {
    const first = String(item).split(";")[0];
    const idx = first.indexOf("=");
    if (idx > 0) {
      const k = first.slice(0, idx).trim();
      const v = first.slice(idx + 1).trim();
      if (k) out[k] = v;
    }
  }
  return out;
}

export function cookieHeaderFromMap(map = {}) {
  return Object.entries(map)
    .map(([k, v]) => `${k}=${v}`)
    .join("; ");
}

export async function fetchPage(url, options = {}) {
  const { referer, cookie, headers: extra = {}, method = "GET", body, redirect = "follow", timeoutMs = 15000 } = options;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const headers = {
      "User-Agent": USER_AGENT,
      Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "en-US,en;q=0.9",
      ...extra
    };
    if (referer) headers["Referer"] = referer;
    if (cookie) headers["Cookie"] = cookie;
    const res = await fetch(url, { method, headers, body, redirect, signal: controller.signal });
    const text = await res.text();
    return {
      status: res.status,
      url: res.url,
      text,
      headers: res.headers,
      cookies: mergeCookieMap({}, getSetCookies(res.headers))
    };
  } finally {
    clearTimeout(timer);
  }
}

export function absUrl(href, base) {
  if (!href) return "";
  try {
    return new URL(href, base).href;
  } catch {
    return href;
  }
}
