version = 5

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Agenda Esportiva (Ao Vivo e Em Breve) e Canais de TV Ao Vivo em HD no BRFlix TV"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Live")
    iconUrl = "https://raw.githubusercontent.com/guilhermehanti/guirepo/main/icons/brflix-tv.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
