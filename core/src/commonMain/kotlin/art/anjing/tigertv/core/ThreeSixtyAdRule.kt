package art.anjing.tigertv.core

/** Two URI aliases for the same offline-verified unencrypted four-segment asset. */
fun filterThreeSixtyAds(site: String, playlist: String): HlsFilterResult? = filterKnownHlsAds(site, playlist, listOf(
    KnownHlsAdGroup("🎬360 资源", listOf("9a4461710a75e235", "2e50e87425947cdb", "7fbce29088ad2044", "d46d2c0b27377340"), listOf(5.0, 5.0, 5.0, 2.567), allowEnd = true),
    KnownHlsAdGroup("🎬360 资源", listOf("97a8a9a5bdb68800", "519d8318d112a48a", "6fc4720824260c91", "6cd7acbbc09d95b1"), listOf(5.0, 5.0, 5.0, 2.567), allowEnd = true),
))
