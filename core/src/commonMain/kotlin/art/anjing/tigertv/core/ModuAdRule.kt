package art.anjing.tigertv.core

/** Shared CDN assets observed on both sources; complete groups only, never directory-wide. */
fun filterModuAds(site: String, playlist: String): HlsFilterResult? {
    if (site !in listOf("🎬魔都资源", "🎬魔都动漫")) return null
    return filterKnownHlsAds(site, playlist, listOf(
        KnownHlsAdGroup(site, listOf("ba94c6f44f2c9eb6", "8be1e06b5c8debc9", "1c4ae96984b7e72a", "5acfd94666be171e", "e5d9ce4b7dc114ab", "2dcf8008f74d4391"), listOf(3.0, 3.0, 5.0, 3.0, 3.0, 0.64), allowEnd = true),
        KnownHlsAdGroup(site, listOf("777824862eb3587e", "223d8a1a63e344f2", "80beefa4cbff3dac", "9e6f5e3830e196b2", "323f02196302313c", "4c1b5dcd6a73b793", "6d70d886fce1ea34", "afc2732153d6bc10", "3300ffee83ea3e09"), listOf(3.333, 1.667, 1.667, 2.933, 1.667, 1.667, 1.667, 1.667, 1.3), allowEnd = true),
    ))
}
