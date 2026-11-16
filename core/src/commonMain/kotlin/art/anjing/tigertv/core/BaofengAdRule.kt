package art.anjing.tigertv.core

/** One observed nine-segment asset. Changed URIs and unknown playlists pass through. */
fun filterBaofengAds(site: String, playlist: String): HlsFilterResult? = filterKnownHlsAd(site, playlist, KnownHlsAdGroup(
    site = "🎬暴风资源",
    uriFingerprints = listOf("5f09ccea8dc282b0", "e45d22e24d6ee625", "b2ab5cdb574c10de", "3498aad314153dbb", "260e850c0f621814", "d3d6fb05076df279", "63a7d4fc3b341682", "e55e22f3f7ce00ef", "15f55d2ec9bdf648"),
    durations = List(8) { 3.0 } + 1.76,
    pathPrefix = "/video/adjump/time/",
))
