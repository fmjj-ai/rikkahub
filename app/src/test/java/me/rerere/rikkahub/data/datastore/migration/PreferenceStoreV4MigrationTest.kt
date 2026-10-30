package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `legacy default models are upgraded and user values are preserved`() {
        val input = """
            [
              {
                "type": "openai",
                "id": "11111111-1111-1111-1111-111111111111",
                "name": "Test",
                "unknownProviderField": "keep",
                "models": [
                  {
                    "id": "22222222-2222-2222-2222-222222222222",
                    "modelId": "legacy",
                    "contextWindowTokens": 472000,
                    "autoCompressThresholdTokens": 272000,
                    "unknownModelField": "keep"
                  },
                  {
                    "id": "33333333-3333-3333-3333-333333333333",
                    "modelId": "custom",
                    "contextWindowTokens": 128000,
                    "autoCompressThresholdTokens": 64000
                  },
                  {
                    "id": "44444444-4444-4444-4444-444444444444",
                    "modelId": "thresholdChanged",
                    "contextWindowTokens": 472000,
                    "autoCompressThresholdTokens": 100000
                  },
                  {
                    "id": "55555555-5555-5555-5555-555555555555",
                    "modelId": "windowChanged",
                    "contextWindowTokens": 128000,
                    "autoCompressThresholdTokens": 272000
                  },
                  {
                    "id": "66666666-6666-6666-6666-666666666666",
                    "modelId": "missing"
                  }
                ]
              }
            ]
        """.trimIndent()

        val migrated = migrateProviderModelDefaults(input)
        val models = JsonInstant.parseToJsonElement(migrated).jsonArray
            .first().jsonObject["models"]!!.jsonArray

        // 旧默认值：升级
        assertEquals(1_000_000, models[0].jsonObject.int("contextWindowTokens"))
        assertEquals(472_000, models[0].jsonObject.int("autoCompressThresholdTokens"))
        assertEquals("keep", models[0].jsonObject["unknownModelField"]!!.jsonPrimitive.content)

        // 用户自定义值：保持不变
        assertEquals(128_000, models[1].jsonObject.int("contextWindowTokens"))
        assertEquals(64_000, models[1].jsonObject.int("autoCompressThresholdTokens"))

        // 只改过其中一项：保持不变
        assertEquals(472_000, models[2].jsonObject.int("contextWindowTokens"))
        assertEquals(100_000, models[2].jsonObject.int("autoCompressThresholdTokens"))
        assertEquals(128_000, models[3].jsonObject.int("contextWindowTokens"))
        assertEquals(272_000, models[3].jsonObject.int("autoCompressThresholdTokens"))

        // 缺失字段：不凭空写入
        assertNull(models[4].jsonObject["contextWindowTokens"])
        assertNull(models[4].jsonObject["autoCompressThresholdTokens"])

        val provider = JsonInstant.parseToJsonElement(migrated).jsonArray.first().jsonObject
        assertEquals("keep", provider["unknownProviderField"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed providers json is left untouched`() {
        assertEquals("not json", migrateProviderModelDefaults("not json"))
    }

    @Test
    fun `providers without models are left untouched`() {
        val input = """[{"type":"openai","id":"11111111-1111-1111-1111-111111111111"}]"""
        assertEquals(input, migrateProviderModelDefaults(input))
    }

    @Test
    fun `migration is idempotent`() {
        val input = """
            [{"type":"openai","models":[
              {"id":"22222222-2222-2222-2222-222222222222","contextWindowTokens":472000,"autoCompressThresholdTokens":272000}
            ]}]
        """.trimIndent()

        val once = migrateProviderModelDefaults(input)
        assertEquals(once, migrateProviderModelDefaults(once))
    }

    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int
}
