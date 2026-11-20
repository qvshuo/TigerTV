package art.anjing.tigertv.core

private const val ThreeSixtySite = "🎬360 资源"

// Verified unencrypted four-segment assets. The last two URI groups serve identical media bytes.
private val threeSixtyAdGroups = listOf(
    listOf("9a4461710a75e235", "2e50e87425947cdb", "7fbce29088ad2044", "d46d2c0b27377340"),
    listOf("97a8a9a5bdb68800", "519d8318d112a48a", "6fc4720824260c91", "6cd7acbbc09d95b1"),
    listOf("e9b8eb4cb96deae4", "3c2167f6e7a64f04", "3ddcef9d18abb778", "30176f9df56df020"),
    listOf("861c5b9b5f0f8285", "a308a362081606a5", "6d70cfa78c923c01", "598963f61df353e1"),
).map { KnownHlsAdGroup(ThreeSixtySite, it, listOf(5.0, 5.0, 5.0, 2.567), allowEnd = true) }

fun filterThreeSixtyAds(site: String, playlist: String): HlsFilterResult? =
    filterKnownHlsAds(site, playlist, threeSixtyAdGroups)
