# GuiRepo TV & Esportes BR — Addon Stremio / NuvioTV

Addon unificado de **TV ao Vivo e Agenda Esportiva** no padrão Stremio, compatível com **NuvioTV**, **Nuvio Mobile/Smart TV** e **Stremio**.

Agrega em tempo real as 5 fontes de TV ao Vivo do `guirepo`:
- **BRFlix TV** (canais + agenda esportiva)
- **EmbedTV** (canais + jogos/eventos)
- **RedeCanais TV** (canais ao vivo)
- **TV a Cabo** (canais + jogos do dia)
- **Rei dos Embeds** (canais + agenda esportiva)

Cada canal é deduplicado automaticamente: ao abrir um canal (ex.: `SporTV`, `ESPN`, `Globo`, `Premiere`), o addon consulta todas as fontes em paralelo e entrega múltiplos servidores de backup na mesma tela.

## Rodar localmente (Node.js 18+)

```bash
node server.mjs
```

Manifesto disponível em:
- No PC: `http://127.0.0.1:7000/manifest.json`
- Na rede local (Android TV / celular no mesmo Wi-Fi): `http://<IP-DO-PC>:7000/manifest.json`
- No emulador MuMu (com `adb reverse tcp:7000 tcp:7000`): `http://127.0.0.1:7000/manifest.json`

## Teste rápido

```bash
npm test
```

## Deploy gratuito no Cloudflare Workers (para usar 24h sem o PC ligado)

```bash
npx wrangler deploy
```

Depois, adicione `https://<seu-worker>.workers.dev/manifest.json` na aba **Addons** do NuvioTV.
