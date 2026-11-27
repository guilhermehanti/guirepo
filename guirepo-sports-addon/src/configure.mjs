export function renderConfigurePage(baseOrigin, prefill = {}) {
  const defaultManifest = `${baseOrigin}/manifest.json`;
  return `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Configurar GuiRepo Sports Replays (NuvioTV / Stremio)</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: #0d1117; color: #e6edf3; margin: 0; padding: 24px 16px; }
  .wrap { max-width: 680px; margin: 0 auto; background: #161b22; border: 1px solid #30363d; border-radius: 14px; padding: 24px; }
  h1 { margin: 0 0 8px; font-size: 22px; }
  p.sub { color: #8b949e; margin: 0 0 20px; line-height: 1.5; }
  .badge-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
  .badge { background: #21262d; border: 1px solid #30363d; border-radius: 999px; padding: 5px 12px; font-size: 12px; }
  .badge.ok { border-color: #238636; color: #7ee787; }
  .badge.warn { border-color: #9e6a03; color: #f2cc60; }
  .card { background: #0d1117; border: 1px solid #30363d; border-radius: 10px; padding: 16px; margin-bottom: 16px; }
  label { display: block; font-size: 13px; font-weight: 600; margin: 12px 0 6px; }
  input { width: 100%; box-sizing: border-box; padding: 11px 12px; border-radius: 8px; border: 1px solid #30363d; background: #161b22; color: #fff; font-size: 14px; }
  .row { display: flex; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
  button { cursor: pointer; border: 0; border-radius: 8px; padding: 11px 16px; font-weight: 700; font-size: 14px; }
  .btn-primary { background: #238636; color: #fff; }
  .btn-secondary { background: #21262d; color: #e6edf3; border: 1px solid #30363d; }
  .status { margin-top: 12px; font-size: 13px; min-height: 20px; }
  .status.ok { color: #7ee787; }
  .status.err { color: #ff7b72; }
  .result { display: none; margin-top: 16px; }
  .url-box { word-break: break-all; background: #010409; border: 1px solid #30363d; border-radius: 8px; padding: 12px; font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; color: #79c0ff; }
  ol { margin: 8px 0 0; padding-left: 20px; color: #c9d1d9; line-height: 1.6; font-size: 13px; }
  .note { font-size: 12px; color: #8b949e; margin-top: 10px; line-height: 1.5; }
</style>
</head>
<body>
<div class="wrap">
  <h1>GuiRepo Sports Replays — Configuração</h1>
  <p class="sub">Addon para <strong>NuvioTV (Android TV)</strong> e <strong>Stremio</strong> com replays de MMA/UFC, F1/F2/F3/MotoGP e jogos históricos de futebol.</p>

  <div class="badge-row">
    <span class="badge ok">✓ WatchMMAFull (sem login)</span>
    <span class="badge ok">✓ WatchF1Full (sem login)</span>
    <span class="badge warn">⚙️ Footballia (conta gratuita necessária)</span>
  </div>

  <div class="card">
    <strong>1. Conta gratuita do Footballia (opcional)</strong>
    <p class="note">Se você quiser assistir aos jogos do <strong>Footballia</strong>, informe abaixo o e-mail e senha da sua conta gratuita em <a href="https://footballia.net/users/sign_up" target="_blank" style="color:#58a6ff">footballia.net</a>. Se deixar em branco, WatchMMAFull e WatchF1Full funcionam normalmente.</p>

    <label for="email">E-mail do Footballia</label>
    <input id="email" type="email" placeholder="seuemail@exemplo.com" value="${(prefill.fbEmail || "").replace(/"/g, "&quot;")}" autocomplete="email">

    <label for="password">Senha do Footballia</label>
    <input id="password" type="password" placeholder="Sua senha do footballia.net" autocomplete="current-password">

    <div class="row">
      <button class="btn-primary" id="btnGen" type="button">Testar Login e Gerar URL</button>
      <button class="btn-secondary" id="btnNoLogin" type="button">Gerar URL sem Footballia</button>
    </div>
    <div id="status" class="status"></div>
    <p class="note">Privacidade: suas credenciais não são salvas em banco de dados — elas ficam embutidas apenas na sua URL pessoal de manifesto.</p>
  </div>

  <div class="card result" id="resultBox">
    <strong>2. Sua URL de instalação do Addon</strong>
    <div class="url-box" id="manifestOut" style="margin-top:8px;">${defaultManifest}</div>
    <div class="row">
      <button class="btn-primary" id="btnCopy" type="button">Copiar URL do Manifesto</button>
    </div>

    <div style="margin-top:16px;">
      <strong>Como instalar na Android TV (NuvioTV) pelo celular:</strong>
      <ol>
        <li><strong>Mais fácil na TV (QR Code no NuvioTV):</strong> no NuvioTV vá em <strong>Addons → Gerir a partir do telemóvel</strong>, leia o QR Code com o celular e cole a URL acima — aparece direto na TV!</li>
        <li><strong>Via conta Nuvio (no celular):</strong> acesse sua conta Nuvio no navegador do celular → <strong>Addons → Add Addon</strong> → cole a URL acima e salve para sincronizar automaticamente na Android TV.</li>
        <li><strong>Direto na Android TV:</strong> abra o NuvioTV → <strong>Configurações → Addons → Instalar addon</strong> e cole a URL acima (use o teclado do app Google TV no celular para colar rápido).</li>
      </ol>
    </div>
  </div>
</div>
<script>
const ORIGIN = ${JSON.stringify(baseOrigin)};
const emailEl = document.getElementById('email');
const passEl = document.getElementById('password');
const statusEl = document.getElementById('status');
const resultBox = document.getElementById('resultBox');
const manifestOut = document.getElementById('manifestOut');

function toBase64Url(str) {
  const bytes = new TextEncoder().encode(str);
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');
}

function showResult(url) {
  manifestOut.textContent = url;
  resultBox.style.display = 'block';
}

document.getElementById('btnNoLogin').addEventListener('click', () => {
  statusEl.className = 'status ok';
  statusEl.textContent = 'URL padrão gerada (WatchMMAFull + WatchF1Full prontos; Footballia exibirá aviso de configuração).';
  showResult(ORIGIN + '/manifest.json');
});

document.getElementById('btnGen').addEventListener('click', async () => {
  const fbEmail = emailEl.value.trim();
  const fbPassword = passEl.value;
  if (!fbEmail || !fbPassword) {
    statusEl.className = 'status err';
    statusEl.textContent = 'Preencha e-mail e senha do Footballia (ou clique em "Gerar URL sem Footballia").';
    return;
  }
  statusEl.className = 'status';
  statusEl.textContent = 'Testando login no footballia.net...';
  try {
    const res = await fetch(ORIGIN + '/api/test-footballia', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fbEmail, fbPassword })
    });
    const data = await res.json();
    if (!res.ok || !data.ok) {
      statusEl.className = 'status err';
      statusEl.textContent = data.error || 'Falha no login do Footballia. Confira e-mail e senha.';
      return;
    }
    const cfg = toBase64Url(JSON.stringify({ fbEmail, fbPassword }));
    statusEl.className = 'status ok';
    statusEl.textContent = '✓ Login no Footballia validado com sucesso! URL pessoal pronta abaixo.';
    showResult(ORIGIN + '/' + cfg + '/manifest.json');
  } catch (e) {
    statusEl.className = 'status err';
    statusEl.textContent = 'Erro ao validar login: ' + e.message;
  }
});

document.getElementById('btnCopy').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(manifestOut.textContent);
    statusEl.className = 'status ok';
    statusEl.textContent = '✓ URL copiada! Agora cole no Nuvio (painel web da conta ou Configurações > Addons).';
  } catch {
    statusEl.className = 'status';
    statusEl.textContent = 'Selecione e copie a URL na caixa azul acima.';
  }
});
</script>
</body>
</html>`;
}
