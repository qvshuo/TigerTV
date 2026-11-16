package art.anjing.tigertv.core

/** Three offline-reviewed versions of the five-segment asset. Unknown URI groups pass through. */
fun filterIqiyiAds(site: String, playlist: String): HlsFilterResult? = filterKnownHlsAds(site, playlist,
    listOf(
        listOf("f00cb8577f6daf47", "1d8cabdefae59aa9", "a5241c9badbb126c", "3d886fa36295ce39", "5e09f0d5715d0171"),
        listOf("de516211586b1e81", "49d5f0d981faed7b", "bf3ffa529673451b", "5fd89cffeb1ece35", "217c5444a03b600d"),
        listOf("79b777e076233b56", "2c11f8576a09ad8b", "bae74977ed4c113c", "e4e9213441adf349", "992ee40514d54e0d"),
    ).map { KnownHlsAdGroup("🎬-爱奇艺-", it, List(4) { 4.0 } + 1.566667) },
)
