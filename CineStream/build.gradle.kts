import org.jetbrains.kotlin.konan.properties.Properties

version = 559
android {
    defaultConfig {
        val properties = Properties()
        properties.load(project.rootProject.file("local.properties").inputStream())
        android.buildFeatures.buildConfig=true
        buildConfigField("String", "SIMKL_API", "\"${properties.getProperty("SIMKL_API")}\"")
        buildConfigField("String", "TMDB_KEY", "\"${properties.getProperty("TMDB_KEY")}\"")
        buildConfigField("String", "FANART_KEY", "\"${properties.getProperty("FANART_KEY")}\"")
        buildConfigField("String", "CASTLE_KEY", "\"${properties.getProperty("CASTLE_KEY")}\"")
        buildConfigField("String", "MOVIEBLAST_TOKEN", "\"${properties.getProperty("MOVIEBLAST_TOKEN")}\"")
        buildConfigField("String", "MOVIEBLAST_API", "\"${properties.getProperty("MOVIEBLAST_API")}\"")
        buildConfigField("String", "MOVIEBLAST_KEY", "\"${properties.getProperty("MOVIEBLAST_KEY")}\"")
    }
}

dependencies {
    // The Cloudflare bypass bottom sheet is a real BottomSheetDialogFragment,
    // so these must be on the compile classpath. The app already ships them
    // (appcompat 1.7.1, fragment-ktx 1.8.9, material 1.14.0); compileOnly
    // keeps them out of the plugin dex.
    val compileOnly by configurations
    compileOnly("androidx.appcompat:appcompat:1.7.1")
    compileOnly("androidx.fragment:fragment-ktx:1.8.9")
    compileOnly("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "en"
    description = "One stop solution for Movies, Series, Anime, AsianDrama and Torrents"
    authors = listOf("megix")
    status = 1
    // The plugin ships no Android resources (classes.dex only): every screen
    // is built programmatically. Declaring this avoids requiring
    // src/main/res, which does not exist.
    requiresResources = false
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime",
        "Torrent"
    )

    iconUrl = "https://github.com/SaurabhKaperwan/CSX/raw/refs/heads/master/CineStream/icon.png"
}
