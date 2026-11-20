package art.anjing.tigertv.core

fun filterIqiyiAds(site: String, playlist: String): HlsFilterResult? =
    if (site == "🎬-爱奇艺-") filterKnownHlsAds(site, playlist) else null
