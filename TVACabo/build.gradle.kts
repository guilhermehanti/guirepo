version = 2

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Agenda Esportiva (Jogos do Dia) e +200 Canais de TV Ao Vivo em HD no TV a Cabo"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://tvacabo.top/favicon.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
