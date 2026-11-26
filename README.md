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
[![License MIT](https://img.shields.io/badge/License-MIT-8A2BE2?style=for-the-badge&logo=opensourceinitiative)](https://opensource.org/licenses/MIT)

</div>

---

## 📺 Instalação no CloudStream

No **CloudStream**, basta instalar o repositório uma única vez — ele já traz **Filmes, Séries, Animes, Desenhos e TV ao Vivo/Esportes** tudo junto:

1. Abra o **CloudStream → Configurações → Extensões → Adicionar repositório**.
2. Digite o shortcode: **`guirepo`**
3. Ou cole diretamente a URL:
   ```text
   https://github.com/guilhermehanti/guirepo/raw/refs/heads/main/builds/repo.json
   ```

[![Copiar URL do Repositório](https://img.shields.io/badge/📋_COPIAR_URL-000000?style=for-the-badge&logo=github&logoColor=white)](https://github.com/guilhermehanti/guirepo/raw/refs/heads/main/builds/repo.json)

---

## 🚀 Instalação no NuvioTV

No **NuvioTV**, Plugins (`.cs3`) e Addons (protocolo Stremio) funcionam em sistemas separados. Para ter o catálogo completo (**Filmes/Séries/Animes + TV ao Vivo/Esportes**), faça as **2 instalações abaixo nesta ordem**:

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
   *(Para uso local na sua rede Wi-Fi rodando `node server.mjs`, use `http://<IP-DO-PC>:7000/manifest.json`; no emulador MuMu, use `http://10.0.2.2:7000/manifest.json`.)*
3. Clique em **Instalar**. O addon unifica automaticamente **BRFlix TV, EmbedTV, TV a Cabo, RedeCanais TV e Rei dos Embeds** em catálogos nativos de TV ao Vivo e Agenda Esportiva (com múltiplos sinais por canal).

### Passo 3 — Organização da Tela Inicial (recomendado para Fire TV / Android TV)
1. Em **Addons → Reordenar catálogos iniciais**, mova os catálogos do **GuiRepo TV & Esportes BR** para logo abaixo de **Trending Movies** e **Trending Series**.
2. **Sincronização:** se estiver logado na sua conta Nuvio, os dois (repositório de Plugins + Addon de TV) sincronizam automaticamente para outros aparelhos novos.

---

# ⚠️ Disclaimer Legal

Este projeto **não promove, hospeda, transmite, armazena ou distribui** qualquer mídia protegida por direitos autorais (filmes, séries, animes etc.).

Os plugins **somente indexam** links públicos de terceiros. Todo conteúdo vem de sites externos, que são os únicos responsáveis legais. Qualquer solicitação DMCA deve ser enviada diretamente ao provedor do conteúdo, e não a este repositório ou desenvolvedor.

---

# 📜 Licença

[![MIT License](https://img.shields.io/badge/License-MIT-8A2BE2?style=for-the-badge&logo=opensourceinitiative)](https://opensource.org/licenses/MIT)

Licença **MIT** — código livre para usar, modificar e distribuir.  
Só mantenha os créditos!

---

# Créditos e Relacionados

Este projeto nasceu originalmente como um fork do **lietrepo**, porém foi amplamente reformulado: diversos plugins novos foram implementados do zero e os plugins remanescentes foram atualizados e corrigidos.

Repositórios e links de referência:
- [![lietrepo](https://img.shields.io/badge/lietrepo-Fork_Original-9C27B0?style=for-the-badge&logo=github)](https://github.com/lawlietbr/lietrepo)
- [![saimuelrepo](https://img.shields.io/badge/saimuelrepo-Repositório-FF6B6B?style=for-the-badge&logo=github)](https://github.com/saimuelbr/saimuelrepo)
- [![CloudStream Official](https://img.shields.io/badge/CloudStream_Official-GitHub-7289DA?style=for-the-badge&logo=github)](https://github.com/recloudstream/cloudstream)
- [![CloudStream App](https://img.shields.io/badge/🌐_Baixar_CloudStream-2196F3?style=for-the-badge&logo=android)](https://cloudstream-apk.com/)
- [![Documentação CloudStream](https://img.shields.io/badge/📚_Documentação-4CAF50?style=for-the-badge&logo=readthedocs)](https://recloudstream.github.io/csdocs/)
