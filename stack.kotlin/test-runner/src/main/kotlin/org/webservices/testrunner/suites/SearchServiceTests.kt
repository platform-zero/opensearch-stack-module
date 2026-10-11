package org.webservices.testrunner.suites

import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.webservices.testrunner.framework.TestRunner
import java.util.UUID

suspend fun TestRunner.searchServiceTests() = suite("OpenSearch Retrieval Provider") {
    val json = Json { ignoreUnknownKeys = true }
    val identity = UUID.randomUUID().toString().replace("-", "")
    val index = "p0-acceptance-$identity"
    val document = "p0-native-$identity"
    val term = "p0nativebm25$identity"
    val base = endpoints.searchService.trimEnd('/')
    var created = false
    var seeded = false

    suspend fun ensureNativeFixture() {
        if (!created) {
            val response = client.putRaw("$base/$index") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    putJsonObject("settings") {
                        put("number_of_shards", 1)
                        put("number_of_replicas", 0)
                    }
                    putJsonObject("mappings") {
                        putJsonObject("_meta") { put("p0_acceptance_identity", identity) }
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "text") }
                            putJsonObject("text") { put("type", "text") }
                            putJsonObject("collection") { put("type", "keyword") }
                        }
                    }
                })
            }
            require(response.status == HttpStatusCode.OK) { "Native isolated index create failed: HTTP ${response.status.value}" }
            created = true
        }
        if (!seeded) {
            val response = client.putRaw("$base/$index/_doc/$document?refresh=true") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("id", document)
                    put("title", "Synthetic native BM25 acceptance")
                    put("text", "$term unique scoped acceptance data, not owner knowledge")
                    put("collection", "p0_acceptance")
                })
            }
            require(response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) { "Native fixture index failed: HTTP ${response.status.value}" }
            require(json.parseToJsonElement(response.bodyAsText()).jsonObject["_id"]?.jsonPrimitive?.content == document) { "Native fixture document identity mismatch" }
            seeded = true
        }
    }

    try {
        test("OpenSearch cluster is healthy") {
            val response = client.getRawResponse("$base/_cluster/health")
            require(response.status == HttpStatusCode.OK) { "OpenSearch health failed: HTTP ${response.status.value}" }
        }

        test("isolated native fixture index exists") {
            withTimeout(30_000L) { ensureNativeFixture() }
            val response = client.getRawResponse("$base/$index")
            require(response.status == HttpStatusCode.OK) { "Native fixture index missing: HTTP ${response.status.value}" }
            val metadata = json.parseToJsonElement(response.bodyAsText()).jsonObject[index]?.jsonObject
                ?.get("mappings")?.jsonObject?.get("_meta")?.jsonObject
            require(metadata?.get("p0_acceptance_identity")?.jsonPrimitive?.content == identity) { "Isolated index ownership marker mismatch" }
        }

        test("BM25 text search returns exact native fixture") {
            withTimeout(30_000L) { ensureNativeFixture() }
            val response = client.postRaw("$base/$index/_search") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("size", 5)
                    putJsonObject("query") {
                        putJsonObject("multi_match") {
                            put("query", term)
                            put("fields", JsonArray(listOf(JsonPrimitive("title^2"), JsonPrimitive("text"))))
                        }
                    }
                })
            }
            require(response.status == HttpStatusCode.OK) { "Native BM25 search failed: HTTP ${response.status.value}" }
            val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]?.jsonObject?.get("hits")?.jsonArray.orEmpty()
            require(hits.any { it.jsonObject["_id"]?.jsonPrimitive?.content == document }) { "Native BM25 did not return the exact newly indexed document" }
        }

        test("client helper normalizes exact OpenSearch fixture") {
            withTimeout(30_000L) { ensureNativeFixture() }
            val result = client.search(term, collections = listOf("p0_acceptance"), limit = 5, mode = "bm25", indexName = index)
            require(result.success) { "Native OpenSearch helper failed" }
            val rows = result.results.jsonObject["results"]?.jsonArray.orEmpty()
            require(rows.any { it.jsonObject["id"]?.jsonPrimitive?.content == document && it.jsonObject["text"]?.jsonPrimitive?.content?.contains(term) == true }) { "Helper did not normalize the exact native fixture" }
        }
        test("missing native index is an error, never success fallback") {
            val result = client.search(term, mode = "bm25", indexName = "p0-absent-$identity")
            require(!result.success) { "Native missing-index failure was replaced by synthetic success" }
        }
    } finally {
        if (created) {
            withTimeout(30_000L) {
                val response = client.deleteRaw("$base/$index")
                require(response.status == HttpStatusCode.OK) { "Native exact-index cleanup failed: HTTP ${response.status.value}" }
            }
        }
    }
}
