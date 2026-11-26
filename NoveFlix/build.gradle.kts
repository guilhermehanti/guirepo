version = 4

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Novelas Brasileiras, Turcas, Mexicanas e Programas completos em HD no NoveFlix"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://noveflix.lol/wp-content/uploads/2026/09/cropped-download-192x192.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
