version = 1

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Canais de TV Ao Vivo em HD (Esportes, Filmes, Séries, Infantis, Notícias e TV Aberta) no RedeCanais"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://redecanais.ro/templates/echo/img/favicon.ico"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
