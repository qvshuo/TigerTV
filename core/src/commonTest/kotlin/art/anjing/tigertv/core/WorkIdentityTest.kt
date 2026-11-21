package art.anjing.tigertv.core

import art.anjing.tigertv.domain.SearchResult
import art.anjing.tigertv.domain.workId
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkIdentityTest {
    @Test fun sharedIdentityGroupsAreStableAcrossSources() {
        val rows = Json.parseToJsonElement(contractFixtures.getValue("work-identity.sample.json")).jsonArray
        val expected = mutableMapOf<String, String>()
        for (element in rows) {
            val row = element.jsonObject
            val result = wireJson.decodeFromJsonElement<SearchResult>(row)
            val key = row.getValue("expected").jsonPrimitive.content
            val id = workId(result)
            val previous = expected.put(key, id)
            if (previous != null) assertEquals(previous, id, result.vodName)
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
