import { USER_AGENT, fetchText, normalizeChannelKey, originOf, stripDiacritics } from "./http.mjs";
import { getBrFlixChannels, getBrFlixEvents, resolveBrFlixChannel, resolveBrFlixEvent } from "./providers/brflixtv.mjs";
import { getEmbedTvCatalog, resolveEmbedTvBySlug, resolveEmbedTvPlayer } from "./providers/embedtv.mjs";
import { getTvaCaboChannels, getTvaCaboEvents, resolveTvaCaboTarget } from "./providers/tvacabo.mjs";
import { getRedeCanaisTvChannels, resolveRedeCanaisTvChannel } from "./providers/redecanaistv.mjs";
import { getReiDosEmbedsChannels, getReiDosEmbedsEvents, resolveReiDosEmbedsUrl } from "./providers/reidosembeds.mjs";

const CHANNELS_TTL_MS = 20 * 60 * 1000;
const EVENTS_TTL_MS = 5 * 60 * 1000;

export const GENRES = [
  "Esportes",
  "TV Aberta",
  "Filmes e Séries",
  "Infantil",
  "Notícias",
  "Documentários",
  "Variedades",
  "Religiosos",
  "Músicas"
];

const PROVIDER_PRIORITY = {
  brflixtv: 1,
  embedtv: 2,
  redecanaistv: 3,
  tvacabo: 4,
  reidosembeds: 5
};

let cache = {
  channels: null,
  channelsMap: new Map(),
  channelsAt: 0,
  events: null,
  eventsMap: new Map(),
  eventsAt: 0
};

function isGenericPoster(url) {
  if (!url) return true;
  return /favicon\.png|assets\/icon\.png|\/bg-post-event\.png$/i.test(url);
}

function posterQualityRank(url) {
  if (!url || isGenericPoster(url)) return 0;
  // Priority 1: Custom match banners (e.g. TV a Cabo / imgur match art like São Paulo x Remo)
  if (/i\.imgur\.com|esportesembed|reidoscanais/i.test(url)) return 5;
  // Priority 2: Specific team crests or competition logos (365scores HD / Rei dos Embeds team badges)
  if (/imagecache\.365scores\.com|\/img\/team\//i.test(url)) return 4;
  // Priority 3: Sport-specific themed banners (basketball, mma, american-football, etc.)
  if (/bg-post-event-[a-z]/i.test(url)) return 2;
  // Priority 4: Channel logo / other real image
  return 3;
}

function fallbackEventPoster(ev, channelsByKey) {
  for (const entry of ev.entries || []) {
    if (entry.provider === "brflixtv") {
      for (const t of entry.targets || []) {
        const key = normalizeChannelKey(t.slug || t.label || "");
        const ch = channelsByKey.get(key) || channelsByKey.get(key.replace(/1$/, ""));
        if (ch?.poster && !isGenericPoster(ch.poster)) return ch.poster;
      }
    }
  }
  return null;
}

function chooseBetterCategory(current, candidate) {
  if (!current || current === "Variedades") return candidate || "Variedades";
  return current;
}

export async function getUnifiedChannels(force = false) {
  const now = Date.now();
  if (!force && cache.channels && now - cache.channelsAt < CHANNELS_TTL_MS) {
    return cache.channels;
  }

  const [brRes, emRes, rcRes, tvRes, rdeRes] = await Promise.allSettled([
    getBrFlixChannels(),
    getEmbedTvCatalog(),
    getRedeCanaisTvChannels(),
    getTvaCaboChannels(),
    getReiDosEmbedsChannels()
  ]);

  const rawLists = [
    brRes.status === "fulfilled" ? brRes.value : [],
    emRes.status === "fulfilled" ? emRes.value.channels : [],
    rcRes.status === "fulfilled" ? rcRes.value : [],
    tvRes.status === "fulfilled" ? tvRes.value : [],
    rdeRes.status === "fulfilled" ? rdeRes.value : []
  ];

  const byKey = new Map();
  for (const list of rawLists) {
    for (const item of list) {
      const key = normalizeChannelKey(item.name);
      if (!key) continue;
      let group = byKey.get(key);
      if (!group) {
        group = {
          id: `grtv:ch:${key}`,
          key,
          name: item.name,
          category: item.category || "Variedades",
          poster: item.poster || null,
          sources: []
        };
        byKey.set(key, group);
      } else {
        // Upgrade poster if current one is missing or generic favicon
        if (isGenericPoster(group.poster) && item.poster && !isGenericPoster(item.poster)) {
          group.poster = item.poster;
        }
        // Prefer cleaner shorter display name without FHD/HD noise if from higher-priority source
        group.category = chooseBetterCategory(group.category, item.category);
      }
      // Keep at most 2 targets per provider for the same channel
      const sameProvCount = group.sources.filter((s) => s.provider === item.provider).length;
      if (sameProvCount < 2) {
        group.sources.push({
          provider: item.provider,
          providerLabel: item.providerLabel,
          name: item.name,
          target: item.target
        });
      }
    }
  }

  const unified = [...byKey.values()].map((ch) => {
    ch.sources.sort((a, b) => (PROVIDER_PRIORITY[a.provider] || 99) - (PROVIDER_PRIORITY[b.provider] || 99));
    return ch;
  });

  // Sort: multi-source channels first within category order, then alphabetically
  const catRank = new Map(GENRES.map((g, i) => [g, i]));
  unified.sort((a, b) => {
    const ca = catRank.has(a.category) ? catRank.get(a.category) : 99;
    const cb = catRank.has(b.category) ? catRank.get(b.category) : 99;
    if (ca !== cb) return ca - cb;
    if (b.sources.length !== a.sources.length) return b.sources.length - a.sources.length;
    return a.name.localeCompare(b.name, "pt-BR");
  });

  if (unified.length > 0) {
    cache.channels = unified;
    cache.channelsMap = new Map(unified.map((c) => [c.id, c]));
    cache.channelsAt = now;
  }
  return unified;
}

function normalizeTeamName(raw) {
  return stripDiacritics(String(raw || "").toLowerCase())
    .replace(/\bolympiakos\b/g, "olympiacos")
    .replace(/\bathletico\b/g, "atletico")
    .replace(/\b(fc|cf|ac|sc|ec|afc|sfc|club|clube|de|do|da)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeEventKey(title) {
  const cleaned = stripDiacritics(String(title || "").toLowerCase())
    .replace(/\s+(?:vs\.?|v\.|×)\s+/g, " x ");
  const parts = cleaned.split(/\s+x\s+/);
  if (parts.length === 2) {
    const a = normalizeTeamName(parts[0]).replace(/\s+/g, "-");
    const b = normalizeTeamName(parts[1]).replace(/\s+/g, "-");
    if (a && b) return [a, b].sort().join("-x-");
  }
  return cleaned.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

export async function getUnifiedEvents(force = false) {
  const now = Date.now();
  if (!force && cache.events && now - cache.eventsAt < EVENTS_TTL_MS) {
    return cache.events;
  }

  const [brRes, tvRes, emRes, rdeRes] = await Promise.allSettled([
    getBrFlixEvents(),
    getTvaCaboEvents(),
    getEmbedTvCatalog(),
    getReiDosEmbedsEvents()
  ]);

  const rawEvents = [
    ...(brRes.status === "fulfilled" ? brRes.value : []),
    ...(tvRes.status === "fulfilled" ? tvRes.value : []),
    ...(emRes.status === "fulfilled" ? emRes.value.events : []),
    ...(rdeRes.status === "fulfilled" ? rdeRes.value : [])
  ];

  // Deduplicate similar events across providers by normalized team/title key
  const byKey = new Map();
  for (const ev of rawEvents) {
    const normTitle = normalizeEventKey(ev.title);
    if (!normTitle) continue;

    let group = byKey.get(normTitle);
    if (!group) {
      group = {
        id: `grtv:ev:${normTitle.slice(0, 64)}`,
        title: ev.title,
        competition: ev.competition || "",
        isLive: ev.isLive,
        statusBadge: ev.statusBadge,
        timeStr: ev.timeStr || "",
        sortKey: ev.sortKey || "9999999999:99",
        poster: ev.poster || null,
        entries: []
      };
      byKey.set(normTitle, group);
    } else {
      if (ev.isLive && !group.isLive) {
        group.isLive = true;
        group.statusBadge = "AO VIVO";
      }
      if (!group.timeStr && ev.timeStr) group.timeStr = ev.timeStr;
      if ((!group.competition || group.competition === "Rei dos Embeds") && ev.competition && ev.competition !== "Rei dos Embeds") {
        group.competition = ev.competition;
      }
      if (posterQualityRank(ev.poster) > posterQualityRank(group.poster)) {
        group.poster = ev.poster;
      }
    }
    group.entries.push(ev);
  }

  const channels = await getUnifiedChannels();
  const channelsByKey = new Map(channels.map((c) => [c.key, c]));

  const unified = [...byKey.values()].map((ev) => {
    if (isGenericPoster(ev.poster)) {
      ev.poster = fallbackEventPoster(ev, channelsByKey);
    }
    return ev;
  }).sort((a, b) => {
    if (a.isLive !== b.isLive) return a.isLive ? -1 : 1;
    return String(a.sortKey).localeCompare(String(b.sortKey));
  });

  cache.events = unified;
  cache.eventsMap = new Map(unified.map((e) => [e.id, e]));
  cache.eventsAt = now;
  return unified;
}

export function formatEventTitle(ev) {
  const prefix = ev.timeStr ? `[${ev.statusBadge} - ${ev.timeStr}]` : `[${ev.statusBadge}]`;
  const comp = ev.competition ? ` (${ev.competition})` : "";
  return `${prefix} ${ev.title}${comp}`;
}

export async function getMetaById(id) {
  if (id.startsWith("grtv:ev:")) {
    if (!cache.eventsMap.has(id)) await getUnifiedEvents();
    const ev = cache.eventsMap.get(id);
    if (!ev) return null;
    const providers = [...new Set(ev.entries.map((e) => e.providerLabel))].join(", ");
    return {
      id: ev.id,
      type: "channel",
      name: formatEventTitle(ev),
      poster: ev.poster || undefined,
      background: ev.poster || undefined,
      posterShape: "landscape",
      genres: ["Esportes", ev.statusBadge],
      description: `${ev.competition ? `${ev.competition} • ` : ""}Fontes ao vivo: ${providers}`
    };
  }

  if (id.startsWith("grtv:ch:")) {
    if (!cache.channelsMap.has(id)) await getUnifiedChannels();
    const ch = cache.channelsMap.get(id);
    if (!ch) return null;
    const providers = [...new Set(ch.sources.map((s) => s.providerLabel))].join(", ");
    return {
      id: ch.id,
      type: "channel",
      name: ch.name,
      poster: ch.poster || undefined,
      background: ch.poster || undefined,
      posterShape: "landscape",
      genres: [ch.category],
      description: `Canal ao vivo (${ch.category}) • Fontes disponíveis: ${providers}`
    };
  }
  return null;
}

async function resolveSingleChannelSource(src) {
  const t = src.target;
  if (!t) return [];
  switch (t.kind) {
    case "channel":
      return resolveBrFlixChannel(t.slug);
    case "embedtv":
      return resolveEmbedTvPlayer(t.playerUrl, `EmbedTV • ${src.name}`);
    case "redecanaistv":
      return resolveRedeCanaisTvChannel(t.url);
    case "tvacabo":
      return resolveTvaCaboTarget(t.url, `TV a Cabo • ${src.name}`);
    case "reidosembeds":
      return resolveReiDosEmbedsUrl(t.url);
    default:
      return [];
  }
}

async function resolveSingleEventEntry(ev) {
  switch (ev.provider) {
    case "brflixtv":
      return resolveBrFlixEvent(ev);
    case "embedtv":
      return resolveEmbedTvPlayer(ev.playerUrl, "EmbedTV");
    case "tvacabo": {
      const settled = await Promise.allSettled(
        (ev.embeds || []).slice(0, 4).map((em) => resolveTvaCaboTarget(em.url, `TV a Cabo • ${em.provider}`))
      );
      return settled.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
    }
    case "reidosembeds":
      return resolveReiDosEmbedsUrl(ev.eventUrl);
    default:
      return [];
  }
}

async function resolveCrossProviderChannelsForEvent(ev) {
  const channelCandidates = [];
  const seenKeys = new Set();

  for (const entry of ev.entries || []) {
    if (entry.provider !== "brflixtv") continue;
    for (const t of entry.targets || []) {
      const raw = t.slug || t.label || "";
      const key = normalizeChannelKey(raw);
      if (!key || key.length < 3 || seenKeys.has(key)) continue;
      // Also normalize cazetv1 -> cazetv, sportv1 -> sportv, espn1 -> espn
      const altKey = key.replace(/1$/, "");
      if (seenKeys.has(altKey)) continue;
      seenKeys.add(key);
      channelCandidates.push({ key, altKey, label: t.label || raw });
      if (channelCandidates.length >= 2) break;
    }
  }
  if (channelCandidates.length === 0) return [];

  const channels = await getUnifiedChannels();
  const byKey = new Map(channels.map((c) => [c.key, c]));
  const tasks = [];

  for (const cand of channelCandidates) {
    const matched = byKey.get(cand.key) || byKey.get(cand.altKey);
    if (matched) {
      const hasEmbedTv = matched.sources.some((s) => s.provider === "embedtv");
      for (const src of matched.sources) {
        if (src.provider === "brflixtv") continue;
        tasks.push(
          resolveSingleChannelSource(src).then((list) =>
            list.map((st) => ({ ...st, label: `${st.label} (${matched.name})` }))
          )
        );
      }
      if (!hasEmbedTv) {
        tasks.push(resolveEmbedTvBySlug(matched.key, `EmbedTV • ${matched.name}`));
      }
    } else {
      tasks.push(resolveEmbedTvBySlug(cand.altKey || cand.key, `EmbedTV • ${cand.label}`));
    }
  }

  const settled = await Promise.allSettled(tasks);
  return settled.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
}

async function probeStreamLive(stream) {
  try {
    const hdrs = { "User-Agent": USER_AGENT, Accept: "*/*", ...(stream.headers || {}) };
    const pl = await fetchText(stream.url, { headers: hdrs, timeoutMs: 5000 });
    if (!pl.ok || !pl.text || !pl.text.trimStart().startsWith("#EXTM3U")) {
      return false;
    }
    const base = pl.url || stream.url;
    const firstUriLine = pl.text
      .split(/\r?\n/)
      .map((l) => l.trim())
      .find((l) => l && !l.startsWith("#"));
    if (!firstUriLine) return false;

    const firstAbs = new URL(firstUriLine, base).toString();
    const isVariant = /\.m3u8(\?|$)|__index\.txt|error\.txt/i.test(firstAbs) || pl.text.includes("#EXT-X-STREAM-INF");
    const segRef = stream.referer || `${originOf(base)}/`;
    const segHdrs = { "User-Agent": USER_AGENT, Accept: "*/*" };
    if (segRef) {
      segHdrs.Referer = segRef;
      const o = originOf(segRef);
      if (o) segHdrs.Origin = o;
    }

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 4500);
    const resp = await fetch(firstAbs, {
      method: "GET",
      headers: isVariant ? segHdrs : { ...segHdrs, Range: "bytes=0-63" },
      redirect: "follow",
      signal: ctrl.signal
    });
    clearTimeout(timer);
    try { resp.body?.cancel(); } catch {}
    if (resp.status >= 200 && resp.status < 300) return true;
    // Some live IPTV servers reject Range header with 400/405/416 — retry plain GET headers
    if (!isVariant && (resp.status === 400 || resp.status === 405 || resp.status === 416)) {
      const ctrl2 = new AbortController();
      const timer2 = setTimeout(() => ctrl2.abort(), 4000);
      const r2 = await fetch(firstAbs, { method: "GET", headers: segHdrs, redirect: "follow", signal: ctrl2.signal });
      clearTimeout(timer2);
      try { r2.body?.cancel(); } catch {}
      return r2.status >= 200 && r2.status < 300;
    }
    return false;
  } catch {
    return false;
  }
}

export async function resolveStreamsById(id) {
  let rawStreams = [];

  if (id.startsWith("grtv:ev:")) {
    if (!cache.eventsMap.has(id)) await getUnifiedEvents();
    const ev = cache.eventsMap.get(id);
    if (!ev) return [];
    const [directSettled, crossStreams] = await Promise.all([
      Promise.allSettled(ev.entries.map((entry) => resolveSingleEventEntry(entry))),
      resolveCrossProviderChannelsForEvent(ev)
    ]);
    rawStreams = [
      ...directSettled.flatMap((r) => (r.status === "fulfilled" ? r.value : [])),
      ...crossStreams
    ];
  } else if (id.startsWith("grtv:ch:")) {
    if (!cache.channelsMap.has(id)) await getUnifiedChannels();
    const ch = cache.channelsMap.get(id);
    if (!ch) return [];
    const hasEmbedTv = ch.sources.some((s) => s.provider === "embedtv");
    const tasks = ch.sources.map((src) => resolveSingleChannelSource(src));
    if (!hasEmbedTv) {
      tasks.push(resolveEmbedTvBySlug(ch.key, `EmbedTV • ${ch.name}`));
    }
    const settled = await Promise.allSettled(tasks);
    rawStreams = settled.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
  }

  // Deduplicate by URL path (ignoring rotating query tokens), merging source badges
  const byUrl = new Map();
  const deduped = [];
  for (const s of rawStreams) {
    if (!s || !s.url) continue;
    const dedupKey = s.url.split("?")[0];
    const existing = byUrl.get(dedupKey);
    if (existing) {
      if (s.source && !existing.source.includes(s.source)) {
        existing.source = `${existing.source} + ${s.source}`;
      }
      continue;
    }
    const copy = { ...s };
    byUrl.set(dedupKey, copy);
    deduped.push(copy);
  }

  deduped.sort((a, b) => {
    const aTxt = /\.txt(\?|$)|error\.txt|bombrilhoso\.lat|cloudfront-net\.lat/i.test(a.url) ? 1 : 0;
    const bTxt = /\.txt(\?|$)|error\.txt|bombrilhoso\.lat|cloudfront-net\.lat/i.test(b.url) ? 1 : 0;
    return aTxt - bTxt;
  });

  // Pre-flight probe in parallel so dead 404/403/offline streams are filtered out before NuvioTV displays them
  const probed = await Promise.all(
    deduped.map(async (s) => ({ s, ok: await probeStreamLive(s) }))
  );
  const alive = probed.filter((p) => p.ok).map((p) => p.s);
  return alive.length > 0 ? alive : deduped;
}
