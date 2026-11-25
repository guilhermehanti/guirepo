version = 4

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Filmes, Séries e Animes Dublados e Legendados em HD no BRFlix"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://brflix.lat/favicon.png?v=2.3.2"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
