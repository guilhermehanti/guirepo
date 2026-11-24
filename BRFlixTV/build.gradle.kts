version = 1

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Agenda Esportiva (Ao Vivo e Em Breve) e Canais de TV Ao Vivo em HD no BRFlix TV"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://brflix.online/favicon.svg"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
