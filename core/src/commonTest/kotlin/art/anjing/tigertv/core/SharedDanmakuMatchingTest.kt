package art.anjing.tigertv.core

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDanmakuMatchingTest {
    @Test fun measuresBothEndsOfTheSharedCollisionWindow() {
        for (item in Json.parseToJsonElement(contractFixtures.getValue("danmaku-collision.sample.json")).jsonArray) {
            val fixture = item.jsonObject
            fun dimension(name: String) = fixture.getValue(name).jsonPrimitive.float
            assertEquals(fixture.getValue("fits").jsonPrimitive.boolean, danmakuRenderRules.fits(
                dimension("width"), dimension("previous"), dimension("incoming"),
                fixture.getValue("elapsedMs").jsonPrimitive.long, dimension("gap")
            ))
        }
    }

    @Test fun titleAndEpisodeContractsMatchWeb() {
        val fixture = Json.parseToJsonElement(contractFixtures.getValue("danmaku-matching.sample.json")).jsonObject
        for (element in fixture.getValue("titleCases").jsonArray) {
            val row = element.jsonObject
            assertEquals(row.getValue("rank").jsonPrimitive.intOrNull,
                danmakuTitleRank(row.text("query"), row.text("candidate"), row.text("year")))
        }
        fun episodes(items: JsonArray) = items.map {
            val row = it.jsonObject
            DanmakuEpisode(row.getValue("id").jsonPrimitive.long, row.text("title"), row.text("number"),
                season = row["season"]?.jsonPrimitive?.intOrNull, seasonId = row.text("seasonId"))
        }
        val commonEpisodes = episodes(fixture.getValue("episodes").jsonArray)
        val cases = fixture.getValue("episodeCases").jsonArray + fixture.getValue("localEpisodeCases").jsonArray
        for (element in cases) {
            val row = element.jsonObject
            val local = row["episodes"]?.jsonArray?.let(::episodes) ?: commonEpisodes
            assertEquals(row.getValue("id").jsonPrimitive.longOrNull,
                matchingDanmakuEpisode(local, row.text("label"), row["workSeason"]?.jsonPrimitive?.intOrNull)?.id, row.text("label"))
        }
        for (element in fixture.getValue("titleCandidateCases").jsonArray) {
            val row = element.jsonObject
            val titles = row.getValue("titles").jsonArray.mapIndexed { index, title ->
                DanmakuTitle(index + 1L, title.jsonPrimitive.content, "test")
            }
            assertEquals(row.getValue("ids").jsonArray.map { it.jsonPrimitive.long },
                danmakuTitleCandidates(row.text("query"), titles, "", null).map { it.second.id }, row.text("query"))
        }
    }
}
