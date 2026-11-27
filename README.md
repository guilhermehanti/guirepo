<div align="center">

# GuiRepo
## 🇧🇷 Repositório BR para CloudStream e NuvioTV

**Deixe uma ⭐ se esse repo te ajudou**

[![Deixe uma Star](https://img.shields.io/badge/⭐_Deixe_uma_Star-FFD700?style=for-the-badge&logo=github&logoColor=black)](https://github.com/guilhermehanti/guirepo/stargazers)

<br>

### Estatísticas
[![Último Commit](https://img.shields.io/github/last-commit/guilhermehanti/guirepo?style=for-the-badge&logo=github&color=9C27B0&label=Último%20commit)](https://github.com/guilhermehanti/guirepo/activity)
[![Tamanho do Repo](https://img.shields.io/github/repo-size/guilhermehanti/guirepo?style=for-the-badge&logo=github&color=FF9800&label=Tamanho)](https://github.com/guilhermehanti/guirepo)
[![Stars](https://img.shields.io/github/stars/guilhermehanti/guirepo?style=for-the-badge&color=FFD700&logo=github&label=Stars)](https://github.com/guilhermehanti/guirepo/stargazers)
[![Forks](https://img.shields.io/github/forks/guilhermehanti/guirepo?style=for-the-badge&color=orange&logo=github&label=Forks)](https://github.com/guilhermehanti/guirepo/network/members)
[![License AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-8A2BE2?style=for-the-badge&logo=gnu)](LICENSE)

</div>

---

## 📺 Instalação no CloudStream

No **CloudStream**, basta instalar o repositório uma única vez — ele já traz **Filmes, Séries, Animes, Desenhos, TV ao Vivo/Esportes e Replays Esportivos (MMA/UFC, F1/MotoGP e Futebol Histórico)** tudo junto:

1. Abra o **CloudStream → Configurações → Extensões → Adicionar repositório**.
2. Digite o shortcode: **`guirepo`**
3. Ou cole diretamente a URL:
   ```text
   https://github.com/guilhermehanti/guirepo/raw/refs/heads/main/builds/repo.json
   ```

[![Copiar URL do Repositório](https://img.shields.io/badge/📋_COPIAR_URL-000000?style=for-the-badge&logo=github&logoColor=white)](https://github.com/guilhermehanti/guirepo/raw/refs/heads/main/builds/repo.json)

### 🥊🏎️⚽ Plugins de Replays Esportivos no CloudStream
- **WatchMMAFull:** cada evento abre como uma **Série**. Escolha em **Temporadas** se deseja ver **Main Card** ou **Prelims**, e em **Episódios** selecione a luta desejada.
- **WatchF1Full:** GPs agrupados separadamente por categoria (**F1, F2, F3, MotoGP, Moto2 e Moto3**). Escolha em **Episódios** qual sessão deseja assistir (*Treino Livre / Practice, Classificação / Qualifying, Sprint, Corrida / Race, etc.*). Nas sessões que usam o redirecionador `lkss.site`, aguarde cerca de ~11s ao dar Play enquanto os servidores são desbloqueados automaticamente.
- **Footballia (Jogos Históricos de Futebol):** exige uma conta gratuita em [footballia.net](https://footballia.net/users/sign_up).
  1. Vá em **Configurações → Extensões → GuiRepo → Footballia → ⚙️**.
  2. Informe seu **e-mail e senha** do Footballia e toque em **Salvar** *(os dados ficam gravados apenas no seu aparelho)*.
  3. A busca do plugin pesquisa diretamente por **Time** (ex.: *Corinthians*, *Ponte Preta*, *Real Madrid*), abrindo todas as partidas históricas da equipe como episódios.

---

## 🚀 Instalação no NuvioTV

No **NuvioTV**, Plugins (`.cs3`) e Addons (protocolo Stremio) funcionam em sistemas separados. Para ter o catálogo completo (**Filmes/Séries/Animes + TV ao Vivo/Esportes + Replays Esportivos**), faça as instalações abaixo:

### Passo 1 — Filmes, Séries, Desenhos e Animes (aba `Plugins`)
1. Abra **Definições → Plugins → Adicionar repositório**.
2. Informe o shortcode **`guirepo`** ou cole a URL:
   ```text
   https://github.com/guilhermehanti/guirepo/raw/refs/heads/main/builds/repo.json
   ```

### Passo 2 — TV ao Vivo e Agenda Esportiva (aba `Addons`)
1. Abra **Definições → Addons → Instalar addon**.
2. Cole a URL do manifesto do addon **GuiRepo TV & Esportes BR**:
   ```text
   https://guirepo-tv-addon.mgaengenhariapr.workers.dev/manifest.json
   ```
3. Clique em **Instalar**. O addon unifica automaticamente **BRFlix TV, EmbedTV, TV a Cabo, RedeCanais TV e Rei dos Embeds** em catálogos nativos de TV ao Vivo e Agenda Esportiva (com múltiplos sinais por canal).

### Passo 3 — Replays Esportivos: MMA/UFC, F1/MotoGP e Footballia (aba `Addons`)
O addon **GuiRepo Sports Replays** traz **WatchMMAFull** (Eventos = Séries, `Season 1 = Main Card`, `Season 2 = Prelims`), **WatchF1Full** (GPs separados por categoria `F1/F2/F3/MotoGP/Moto2/Moto3`, sessões como episódios) e **Footballia** (jogos históricos com busca por time):

- **Instalação rápida (sem conta do Footballia — WatchMMAFull e WatchF1Full já funcionam na hora):**
  ```text
  https://guirepo-sports-addon.mgaengenhariapr.workers.dev/manifest.json
  ```
- **Instalação completa COM Footballia (requer conta gratuita em [footballia.net](https://footballia.net/users/sign_up)):**
  1. Pelo navegador do **celular** (ou PC), abra a página de configuração:
     ```text
     https://guirepo-sports-addon.mgaengenhariapr.workers.dev/configure
     ```
  2. Digite o **e-mail e senha** da sua conta gratuita do Footballia e clique em **Testar Login e Gerar URL** *(suas credenciais não são salvas no servidor — ficam apenas na sua URL pessoal)*.
  3. Copie a URL gerada e instale na sua **Android TV / NuvioTV** de um dos 3 jeitos:
     - **Mais fácil na TV (QR Code):** no NuvioTV vá em **Addons → Gerir a partir do telemóvel**, leia o QR Code com o celular e cole a URL gerada.
     - **Via conta Nuvio:** cole a URL no painel web da sua conta Nuvio pelo celular em **Addons → Add Addon** para sincronizar automaticamente na TV.
     - **Direto na TV:** vá em **Definições → Addons → Instalar addon** e cole a URL gerada.

### Passo 4 — Organização da Tela Inicial (recomendado para Fire TV / Android TV)
1. Em **Addons → Reordenar catálogos iniciais**, mova os catálogos do **GuiRepo TV & Esportes BR** e do **GuiRepo Sports Replays** para a posição que preferir na tela inicial.
2. **Sincronização:** se estiver logado na sua conta Nuvio, o repositório de Plugins e os Addons sincronizam automaticamente para outros aparelhos novos.

---

# ⚠️ Disclaimer Legal

Este projeto **não promove, hospeda, transmite, armazena ou distribui** qualquer mídia protegida por direitos autorais (filmes, séries, animes etc.).

Os plugins **somente indexam** links públicos de terceiros. Todo conteúdo vem de sites externos, que são os únicos responsáveis legais. Qualquer solicitação DMCA deve ser enviada diretamente ao provedor do conteúdo, e não a este repositório ou desenvolvedor.

---

# 📜 Licença

[![AGPL-3.0 License](https://img.shields.io/badge/License-AGPL--3.0-8A2BE2?style=for-the-badge&logo=gnu)](LICENSE)

Licença **GNU AGPL-3.0** — você pode usar, estudar, modificar e distribuir o código livremente, **desde que mantenha os créditos a este repositório e publique gratuitamente o código-fonte de quaisquer modificações sob a mesma licença** (inclusive caso hospede versões modificadas do addon de TV em servidores ou Cloudflare Workers).

---

# Créditos e Relacionados

Este projeto nasceu originalmente como um fork do **lietrepo**, porém foi amplamente reformulado: diversos plugins novos foram implementados do zero e os plugins remanescentes foram atualizados e corrigidos. Além disso, foi implementada compatibilidade completa com o **NuvioTV**, tanto nos plugins de Filmes, Séries, Desenhos e Animes (`.cs3`) quanto por meio de um addon dedicado de TV ao Vivo e Agenda Esportiva hospedado em Cloudflare Workers.

Repositórios e links de referência:
- [![lietrepo](https://img.shields.io/badge/lietrepo-Fork_Original-9C27B0?style=for-the-badge&logo=github)](https://github.com/lawlietbr/lietrepo)
- [![saimuelrepo](https://img.shields.io/badge/saimuelrepo-Repositório-FF6B6B?style=for-the-badge&logo=github)](https://github.com/saimuelbr/saimuelrepo)
- [![CloudStream Official](https://img.shields.io/badge/CloudStream_Official-GitHub-7289DA?style=for-the-badge&logo=github)](https://github.com/recloudstream/cloudstream)
- [![CloudStream App](https://img.shields.io/badge/🌐_Baixar_CloudStream-2196F3?style=for-the-badge&logo=android)](https://cloudstream-apk.com/)
- [![Documentação CloudStream](https://img.shields.io/badge/📚_Documentação_CloudStream-4CAF50?style=for-the-badge&logo=readthedocs)](https://recloudstream.github.io/csdocs/)
- [![NuvioTV GitHub](https://img.shields.io/badge/NuvioTV-GitHub-111111?style=for-the-badge&logo=github)](https://github.com/NuvioMedia/NuvioTV)
- [![Baixar NuvioTV](https://img.shields.io/badge/📺_Baixar_NuvioTV-00BCD4?style=for-the-badge&logo=android)](https://github.com/NuvioMedia/NuvioTV/releases)
