version = 2

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Filmes e Séries Dublados e Legendados no NetCine (maskara.lat)"
    language = "pt-br"
    authors = listOf("guilhermehanti")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://cdn.bcdn.zip/wp-content/uploads/2016/09/favicon-nc-1.png"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
