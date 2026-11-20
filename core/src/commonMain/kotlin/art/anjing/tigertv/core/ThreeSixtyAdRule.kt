package art.anjing.tigertv.core

fun filterThreeSixtyAds(site: String, playlist: String): HlsFilterResult? =
    if (site == "🎬360 资源") filterKnownHlsAds(site, playlist) else null
