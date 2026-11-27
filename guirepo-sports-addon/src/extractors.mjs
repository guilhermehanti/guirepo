import { USER_AGENT, cookieHeaderFromMap, decodeHtml, fetchPage } from "./http.mjs";

const VIDARA_HOSTS = new Set(["vidara.so", "vidaraa.cc", "oisauh.com"]);
const VIDSONIC_HOSTS = new Set(["vidsonic.net"]);
const CONTENT_ST_HOSTS = new Set(["content.st"]);
const VOE_HOSTS = new Set(["voe.sx", "voe-network.net", "jilliandescribecompany.com", "mikaylaarealike.com", "caseyseemavoid.com"]);

export function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase().replace(/^www\./, "");
  } catch {
    return "";
  }
}

export async function extractVidara(embedUrl, labelPrefix = "Vidara") {
  try {
    const u = new URL(embedUrl);
    const origin = `${u.protocol}//${u.host}`;
    const filecode = u.pathname.replace(/^\/+|\/+$/g, "").split("/").pop();
    if (!filecode) return [];

    const res = await fetch(`${origin}/api/stream`, {
      method: "POST",
      headers: {
        "User-Agent": USER_AGENT,
        "Content-Type": "application/json",
        Origin: origin,
        Referer: embedUrl
      },
      body: JSON.stringify({ filecode, device: "desktop" })
    });
    if (!res.ok) return [];
    const data = await res.json();
    const streamUrl = String(data?.streaming_url || "").trim();
    if (!streamUrl.startsWith("http")) return [];

    return [
      {
        name: `${labelPrefix} (HD)`,
        title: `HLS • ${u.hostname}`,
        url: streamUrl,
        behaviorHints: {
          notWebReady: true,
          proxyHeaders: {
            request: {
              Origin: origin,
              Referer: `${origin}/`,
              "User-Agent": USER_AGENT
            }
          }
        }
      }
    ];
  } catch {
    return [];
  }
}

export async function extractOkRu(embedUrl, labelPrefix = "OK.ru") {
  try {
    const page = await fetchPage(embedUrl, { referer: "https://ok.ru/" });
    const match = page.text.match(/data-options="([^"]+)"/i);
    if (!match) return [];
    const rawOpts = JSON.parse(decodeHtml(match[1]));
    const rawMeta = rawOpts?.flashvars?.metadata;
    const meta = typeof rawMeta === "string" ? JSON.parse(rawMeta) : rawMeta;
    if (!meta) return [];

    const streams = [];
    if (meta.hlsManifestUrl && String(meta.hlsManifestUrl).startsWith("http")) {
      streams.push({
        name: `${labelPrefix} (HLS)`,
        title: `OK.ru • Adaptive HLS`,
        url: meta.hlsManifestUrl,
        behaviorHints: {
          notWebReady: true,
          proxyHeaders: {
            request: {
              Referer: "https://ok.ru/",
              "User-Agent": USER_AGENT
            }
          }
        }
      });
    }
    const qualMap = { full: "1080p", hd: "720p", sd: "480p", low: "360p", lowest: "240p", mobile: "144p" };
    if (Array.isArray(meta.videos)) {
      const sorted = [...meta.videos].reverse();
      for (const v of sorted) {
        if (v?.url && String(v.url).startsWith("http")) {
          const q = qualMap[String(v.name || "").toLowerCase()] || v.name || "MP4";
          streams.push({
            name: `${labelPrefix} (${q})`,
            title: `OK.ru • Direct MP4 (${q})`,
            url: v.url,
            behaviorHints: {
              notWebReady: true,
              proxyHeaders: {
                request: {
                  Referer: "https://ok.ru/",
                  "User-Agent": USER_AGENT
                }
              }
            }
          });
        }
      }
    }
    return streams;
  } catch {
    return [];
  }
}

export async function extractVidSonic(embedUrl, labelPrefix = "VidSonic") {
  try {
    const u = new URL(embedUrl);
    const origin = `${u.protocol}//${u.host}`;
    const page = await fetchPage(embedUrl, { referer: "https://watchf1full.com/" });
    const m = page.text.match(/const\s+_0x1\s*=\s*['"]([0-9a-fA-F|]+)['"]/);
    if (!m) return [];
    const clean = m[1].replace(/\|/g, "");
    if (clean.length < 16 || clean.length % 2 !== 0) return [];
    let out = "";
    for (let i = 0; i < clean.length; i += 2) {
      out += String.fromCharCode(parseInt(clean.slice(i, i + 2), 16));
    }
    const streamUrl = out.split("").reverse().join("").trim();
    if (!streamUrl.startsWith("http")) return [];

    return [
      {
        name: `${labelPrefix} (1080p HLS)`,
        title: `HLS • ${u.hostname}`,
        url: streamUrl,
        behaviorHints: {
          notWebReady: true,
          proxyHeaders: {
            request: {
              Origin: origin,
              Referer: `${origin}/`,
              "User-Agent": USER_AGENT
            }
          }
        }
      }
    ];
  } catch {
    return [];
  }
}

async function sha256Hex(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function extractContentSt(embedUrl, labelPrefix = "Content.st") {
  try {
    const u = new URL(embedUrl);
    const origin = `${u.protocol}//${u.host}`;
    const page = await fetchPage(embedUrl, { referer: "https://watchmmafull.com/", timeoutMs: 8000 });
    const cfgM = page.text.match(/<script[^>]+id="pcfg"[^>]*>([\s\S]*?)<\/script>/i);
    if (!cfgM) return [];
    const cfg = JSON.parse(cfgM[1]);

    let src = cfg?.src && String(cfg.src).startsWith("http") ? String(cfg.src) : "";
    if (!src && cfg?.id) {
      const planStr = cfg.plan === undefined ? "undefined" : JSON.stringify(cfg.plan);
      const pd = await sha256Hex(planStr);
      const res = await fetch(`${origin}/x/k/${encodeURIComponent(cfg.id)}`, {
        method: "POST",
        headers: {
          "User-Agent": USER_AGENT,
          "Content-Type": "application/json",
          Origin: origin,
          Referer: embedUrl,
          Cookie: cookieHeaderFromMap(page.cookies)
        },
        body: JSON.stringify({ bc: "", pd, pk: cfg.pk || "", pe: cfg.pe || 0 })
      });
      if (res.ok) {
        const j = await res.json();
        if (j?.ok && j?.src && String(j.src).startsWith("http")) src = String(j.src);
      }
    }
    if (!src) return [];

    return [
      {
        name: `${labelPrefix} (HD)`,
        title: `HLS • ${u.hostname}`,
        url: src,
        behaviorHints: {
          notWebReady: true,
          proxyHeaders: {
            request: {
              Origin: origin,
              Referer: `${origin}/`,
              "User-Agent": USER_AGENT
            }
          }
        }
      }
    ];
  } catch {
    return [];
  }
}

function decodeVoePayload(encoded) {
  let s = encoded.replace(/[A-Za-z]/g, (c) =>
    String.fromCharCode((c <= "Z" ? 90 : 122) >= (c = c.charCodeAt(0) + 13) ? c : c - 26)
  );
  for (const sym of ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]) {
    s = s.split(sym).join("");
  }
  s = atob(s);
  s = [...s].map((c) => String.fromCharCode(c.charCodeAt(0) - 3)).join("");
  s = [...s].reverse().join("");
  return JSON.parse(atob(s));
}

export async function extractVoe(embedUrl, labelPrefix = "VOE") {
  try {
    let page = await fetchPage(embedUrl, { referer: "https://watchf1full.com/", timeoutMs: 8000 });
    const redirM = page.text.match(/window\.location\.href\s*=\s*['"](https?:\/\/[^'"]+)['"]/i);
    if (redirM) {
      page = await fetchPage(redirM[1], { referer: embedUrl, timeoutMs: 8000 });
    }
    const jm = page.text.match(/<script[^>]+type="application\/json"[^>]*>\s*\[\s*"([^"]+)"\s*\]\s*<\/script>/i);
    if (!jm) return [];
    const obj = decodeVoePayload(jm[1]);
    const streamUrl = String(obj?.source || "").trim();
    if (!streamUrl.startsWith("http")) return [];
    const u = new URL(page.url);
    const origin = `${u.protocol}//${u.host}`;
    return [
      {
        name: `${labelPrefix} (HD HLS)`,
        title: `HLS • ${u.hostname}`,
        url: streamUrl,
        behaviorHints: {
          notWebReady: true,
          proxyHeaders: {
            request: {
              Origin: origin,
              Referer: `${origin}/`,
              "User-Agent": USER_AGENT
            }
          }
        }
      }
    ];
  } catch {
    return [];
  }
}

export async function extractFromHostUrl(targetUrl, labelPrefix = "Server") {
  const host = hostOf(targetUrl);
  if (VIDARA_HOSTS.has(host) || [...VIDARA_HOSTS].some((h) => host.endsWith(`.${h}`))) {
    return extractVidara(targetUrl, `${labelPrefix} • Vidara`);
  }
  if (CONTENT_ST_HOSTS.has(host) || [...CONTENT_ST_HOSTS].some((h) => host.endsWith(`.${h}`))) {
    return extractContentSt(targetUrl, `${labelPrefix} • Content.st`);
  }
  if (VIDSONIC_HOSTS.has(host) || [...VIDSONIC_HOSTS].some((h) => host.endsWith(`.${h}`))) {
    return extractVidSonic(targetUrl, `${labelPrefix} • VidSonic`);
  }
  if (VOE_HOSTS.has(host) || [...VOE_HOSTS].some((h) => host.endsWith(`.${h}`))) {
    return extractVoe(targetUrl, `${labelPrefix} • VOE`);
  }
  if (host === "ok.ru" || host.endsWith(".ok.ru") || host === "odnoklassniki.ru") {
    return extractOkRu(targetUrl, `${labelPrefix} • OK.ru`);
  }
  return [];
}

/**
 * Resolves multiple lkss.site redirector URLs in parallel using ONE single 10.6s wait.
 */
export async function resolveLkssBatch(entries, referer) {
  const pending = (
    await Promise.all(
      entries.map(async ({ label, url }) => {
        try {
          const r = await fetchPage(url, { referer });
          const formM = r.text.match(/<form[^>]*action=["']([^"']*link\/go[^"']*)["'][\s\S]*?<\/form>/i);
          if (!formM) return null;
          const action = new URL(formM[1], "https://lkss.site").href;
          const params = new URLSearchParams();
          for (const m of formM[0].matchAll(/<input[^>]+name=["']([^"']+)["'][^>]*value=["']([^"']*)["']/gi)) {
            params.append(m[1], m[2]);
          }
          if (!params.get("_token") || !params.get("ad_form_data")) return null;
          return {
            label,
            url,
            action,
            body: params.toString(),
            cookieHeader: cookieHeaderFromMap(r.cookies)
          };
        } catch {
          return null;
        }
      })
    )
  ).filter(Boolean);

  if (!pending.length) return [];

  await new Promise((r) => setTimeout(r, 10600));

  const resolved = (
    await Promise.all(
      pending.map(async (p) => {
        try {
          const res = await fetch(p.action, {
            method: "POST",
            headers: {
              "User-Agent": USER_AGENT,
              "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
              "X-Requested-With": "XMLHttpRequest",
              Referer: p.url,
              Origin: "https://lkss.site",
              Cookie: p.cookieHeader
            },
            body: p.body
          });
          const data = await res.json();
          const targetUrl = String(data?.url || "").trim();
          if (targetUrl.startsWith("http")) return { label: p.label, url: targetUrl };
          return null;
        } catch {
          return null;
        }
      })
    )
  ).filter(Boolean);

  return resolved;
}
