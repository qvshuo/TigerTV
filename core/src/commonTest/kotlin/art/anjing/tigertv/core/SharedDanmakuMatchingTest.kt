package art.anjing.tigertv.core

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDanmakuMatchingTest {
    @Test fun titleAndEpisodeContractsMatchWeb() {
        val fixture = Json.parseToJsonElement(contractFixtures.getValue("danmaku-matching.sample.json")).jsonObject
        for (element in fixture.getValue("titleCases").jsonArray) {
            val row = element.jsonObject
            assertEquals(row.getValue("rank").jsonPrimitive.intOrNull,
                danmakuTitleRank(row.text("query"), row.text("candidate"), row.text("year")))
        }
        val episodes = fixture.getValue("episodes").jsonArray.map {
            val row = it.jsonObject
            DanmakuEpisode(row.getValue("id").jsonPrimitive.long, row.text("title"), row.text("number"),
                seasonId = row.text("seasonId"))
        }
        for (element in fixture.getValue("episodeCases").jsonArray) {
            val row = element.jsonObject
            assertEquals(row.getValue("id").jsonPrimitive.longOrNull, matchingDanmakuEpisode(episodes, row.text("label"), null)?.id)
        }
    }
}
