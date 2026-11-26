import {
  decodeHtmlEntities,
  fetchJson,
  fetchText,
  isBlocked,
  makeStream,
  originOf,
  resolveUrl
} from "../http.mjs";

const BASE_URL = "https://reidosembeds.lat";
const SOURCES_RE = /var\s+sources\s*=\s*(\[[\s\S]*?\]);/;

async function fetchAgendaStatus(status, maxPages) {
  const out = [];
  for (let page = 1; page <= maxPages; page++) {
    const res = await fetchText(`${BASE_URL}/agenda?status=${status}&page=${page}`, {
      headers: { Referer: `${BASE_URL}/`, Origin: BASE_URL },
      timeoutMs: 8000
    });
    if (!res.ok) break;
    const html = res.text;
    const cardRe = /<article\b[^>]*data-event-card[^>]*>([\s\S]*?)<\/article>/gi;
    let m;
    let foundValid = false;
    while ((m = cardRe.exec(html)) !== null) {
      const card = m[1];
      const isLive = status === "live" || />\s*AO VIVO\s*</i.test(card);
      const isSoon = />\s*EM BREVE\s*</i.test(card);
      if (!isLive && !isSoon) continue;
      foundValid = true;

      const h3M = /<h3\b[^>]*>([\s\S]*?)<\/h3>/i.exec(card);
      const title = h3M ? decodeHtmlEntities(h3M[1].replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim() : "";
      if (!title || isBlocked(title)) continue;

      const imgTags = [...card.matchAll(/<img\b[^>]*>/gi)].map((x) => x[0]);
      const getSrc = (tag) => (/src=["']([^"']+)["']/i.exec(tag || "") || [])[1] || "";
      const team1Img = imgTags.find((t) => /\/img\/team\/|alt=["']Time\s*1["']/i.test(t));
      const bgImg = imgTags.find((t) => !/\/img\/team\//i.test(t));
      const rawPoster = getSrc(team1Img) || getSrc(bgImg) || "";
      const poster = rawPoster ? resolveUrl(rawPoster, BASE_URL) : null;
      const linkM = /<a\b[^>]*href=["'](https?:\/\/[^"']+)["']/i.exec(card);
      const eventUrl = linkM ? linkM[1].trim() : "";
      if (!eventUrl) continue;

      const plain = card.replace(/<[^>]+>/g, " ");
      const timeStr = (/\b(\d{2}:\d{2})\b/.exec(plain) || [])[1] || "";
      const dateM = /\b(\d{2})\/(\d{2})\/(\d{4})\b/.exec(plain);
      const dateKey = dateM ? `${dateM[3]}${dateM[2]}${dateM[1]}` : "99999999";

      const slugPart = eventUrl
        .replace(/^https?:\/\//i, "")
        .replace(/[^a-z0-9]+/gi, "-")
        .replace(/^-+|-+$/g, "")
        .toLowerCase()
        .slice(0, 48);

      out.push({
        provider: "reidosembeds",
        providerLabel: "Rei dos Embeds",
        slug: `rde-${slugPart}`,
        title,
        competition: "Rei dos Embeds",
        isLive,
        statusBadge: isLive ? "AO VIVO" : "EM BREVE",
        timeStr,
        sortKey: `${dateKey}${timeStr || "99:99"}`,
        poster,
        eventUrl
      });
    }
    if (!foundValid) break;
  }
  return out;
}

export async function getReiDosEmbedsEvents() {
  try {
    const [liveEvents, allEvents] = await Promise.all([
      fetchAgendaStatus("live", 3),
      fetchAgendaStatus("all", 3)
    ]);
    const byUrl = new Map();
    for (const ev of [...liveEvents, ...allEvents]) {
      const existing = byUrl.get(ev.eventUrl);
      if (!existing || (ev.isLive && !existing.isLive)) {
        byUrl.set(ev.eventUrl, ev);
      }
    }
    return [...byUrl.values()];
  } catch {
    return [];
  }
}

export async function getReiDosEmbedsChannels() {
  try {
    const res = await fetchText(`${BASE_URL}/`, {
      headers: { Referer: `${BASE_URL}/`, Origin: BASE_URL },
      timeoutMs: 8000
    });
    if (!res.ok) return [];
    const html = res.text;
    const channels = [];
    const seen = new Set();
    const cardRe = /<article\b[^>]*data-channel-card[^>]*>([\s\S]*?)<\/article>/gi;
    let m;
    while ((m = cardRe.exec(html)) !== null) {
      const card = m[1];
      const h4M = /<h4\b[^>]*>([\s\S]*?)<\/h4>/i.exec(card);
      const aM = /<a\b[^>]*href=["']([^"']+)["']/i.exec(card);
      const imgM = /<img\b[^>]*src=["']([^"']+)["']/i.exec(card);
      const name = h4M ? decodeHtmlEntities(h4M[1].replace(/<[^>]+>/g, " ")).trim() : "";
      const url = aM ? resolveUrl(aM[1], BASE_URL) : "";
      if (!name || !url || isBlocked(`${name} ${url}`) || seen.has(url)) continue;
      seen.add(url);
      channels.push({
        provider: "reidosembeds",
        providerLabel: "Rei dos Embeds",
        id: encodeURIComponent(url),
        name,
        category: "Variedades",
        poster: imgM ? resolveUrl(imgM[1], BASE_URL) : null,
        target: { kind: "reidosembeds", url }
      });
    }
    return channels;
  } catch {
    return [];
  }
}

async function resolvePlayerChain(startUrl, initialReferer, label) {
  let currentUrl = startUrl;
  let referer = initialReferer;

  for (let i = 0; i <= 2; i++) {
    try {
      if (/(?:youtube\.com|youtu\.be|twitch\.tv)/i.test(currentUrl)) return [];
      const res = await fetchText(currentUrl, { headers: { Referer: referer }, timeoutMs: 8000 });
      const html = res.text || "";
      const origin = originOf(currentUrl, BASE_URL);

      const sourcesM = SOURCES_RE.exec(html);
      if (sourcesM) {
        const arr = JSON.parse(sourcesM[1]);
        const streams = [];
        for (let j = 0; j < arr.length; j++) {
          const s = arr[j];
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
          if (!src) continue;
          const st = makeStream({
            source: "Rei dos Embeds",
            label: `${label} - ${s?.label || `Fonte ${j + 1}`}`,
            url: src,
            referer: currentUrl,
            origin
          });
          if (st) streams.push(st);
        }
        if (streams.length > 0) return streams;
      }

      const ifrM =
        /<iframe[^>]*src=["']([^"']*(?:rde\.lat|play|embed)[^"']*)["']/i.exec(html) ||
        /<iframe[^>]*src=["']([^"']+)["']/i.exec(html);
      if (!ifrM) break;
      referer = currentUrl;
      currentUrl = resolveUrl(ifrM[1].replace(/&amp;/g, "&"), origin);
    } catch {
      break;
    }
  }
  return [];
}

export async function resolveReiDosEmbedsUrl(url) {
  try {
    if (url.includes("/eventos/")) {
      const res = await fetchText(url, { headers: { Referer: `${BASE_URL}/`, Origin: BASE_URL }, timeoutMs: 8000 });
      const html = res.text || "";
      const choices = [];
      const choiceRe = /class=["'][^"']*player-choice[^"']*["'][^>]*data-player-url=["']([^"']+)["'][^>]*>([\s\S]*?)<\/(?:button|a|div)>/gi;
      let m;
      while ((m = choiceRe.exec(html)) !== null) {
        const optUrl = m[1].trim();
        const optName = decodeHtmlEntities(m[2].replace(/<[^>]+>/g, " "))
          .replace(/\b(?:Ativo|Copiar)\b/gi, "")
          .replace(/\s+/g, " ")
          .trim() || "Opção";
        if (optUrl) choices.push({ optUrl, optName });
      }
      if (choices.length > 0) {
        const settled = await Promise.all(
          choices.slice(0, 4).map((c) => resolvePlayerChain(c.optUrl, url, `Rei dos Embeds • ${c.optName}`))
        );
        return settled.flat();
      }
      const mainIfr = /<iframe[^>]*id=["']event-player-frame["'][^>]*src=["']([^"']+)["']/i.exec(html);
      if (mainIfr) return resolvePlayerChain(resolveUrl(mainIfr[1], BASE_URL), url, "Rei dos Embeds");
    }

    const page = await fetchText(url, { headers: { Referer: `${BASE_URL}/`, Origin: BASE_URL }, timeoutMs: 8000 });
    const html = page.text || "";
    const ifr =
      /<iframe[^>]*id=["']play-inner-frame["'][^>]*src=["']([^"']+)["']/i.exec(html) ||
      /<iframe[^>]*src=["']([^"']+)["']/i.exec(html);
    const target = ifr ? resolveUrl(ifr[1].replace(/&amp;/g, "&"), originOf(url, BASE_URL)) : url;
    return resolvePlayerChain(target, url, "Rei dos Embeds");
  } catch {
    return [];
  }
}
