version = 6

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Filmes, Séries e Animes em 4K/HD Dublados e Legendados no Kairou (sem Cloudflare)"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://kairou.space/apple-touch-icon.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
