package art.anjing.tigertv.core

fun filterModuAds(site: String, playlist: String): HlsFilterResult? =
    if (site == "🎬魔都资源") filterKnownHlsAds(site, playlist) else null
