version = 3

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Canais de TV Ao Vivo em HD (Esportes, Filmes, Séries, Infantis, Notícias e TV Aberta) no RedeCanais"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://raw.githubusercontent.com/guilhermehanti/guirepo/main/icons/redecanais-tv.png?v=2"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
