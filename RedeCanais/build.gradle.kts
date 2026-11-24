version = 1

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Filmes, Séries, Desenhos e Animes Dublados e Legendados em HD no RedeCanais"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Cartoon", "TvSeries", "Anime", "Movie")
    iconUrl = "https://redecanais.ro/templates/echo/img/favicon.ico"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
