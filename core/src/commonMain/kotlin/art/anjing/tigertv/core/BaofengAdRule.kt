package art.anjing.tigertv.core

fun filterBaofengAds(site: String, playlist: String): HlsFilterResult? =
    if (site == "🎬暴风资源") filterKnownHlsAds(site, playlist) else null
