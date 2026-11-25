version = 4

cloudstream {
    setRepo("https://github.com/guilhermehanti/guirepo")
    description = "Filmes, Séries e Animações Dublados e Legendados em HD no PobreFlixTV"
    language = "pt-br"
    authors = listOf("lawlietbr", "guilhermehanti")
    status = 1
    tvTypes = listOf("Movies", "Series", "Cartoon")
    iconUrl = "https://www.pobreflixtvfilmes.com/assets/img/favicon.png"
}

dependencies {
    implementation("androidx.webkit:webkit:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

// CloudStream does not export AndroidX WebKit on all supported app versions.
val bundledWebKit by configurations.creating { isTransitive = false }
dependencies { bundledWebKit("androidx.webkit:webkit:1.9.0") }
val unpackWebKit by tasks.registering(Sync::class) {
    from(provider { zipTree(bundledWebKit.singleFile) }) { include("classes.jar") }
    into(layout.buildDirectory.dir("bundled-webkit"))
}
val unpackWebKitClasses by tasks.registering(Sync::class) {
    dependsOn(unpackWebKit)
    from(provider { zipTree(unpackWebKit.get().destinationDir.resolve("classes.jar")) }) { include("**/*.class") }
    into(layout.buildDirectory.dir("bundled-webkit-classes"))
}
tasks.withType<com.lagradost.cloudstream3.gradle.tasks.CompileDexTask>().configureEach {
    dependsOn(unpackWebKitClasses)
    input.from(unpackWebKitClasses.map { it.destinationDir })
}
