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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.webservices.testrunner.framework.TestRunner

suspend fun TestRunner.searchServiceTests() = suite("OpenSearch Retrieval Provider") {
    val json = Json { ignoreUnknownKeys = true }
    var sampleQuery = "Podman runtime"

    suspend fun openSearchGet(path: String) =
        client.getRawResponse("${endpoints.searchService.trimEnd('/')}$path")

    suspend fun openSearchPost(path: String, body: kotlinx.serialization.json.JsonObject) =
        client.postRaw("${endpoints.searchService.trimEnd('/')}$path") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun ensureSampleDocuments() {
        val initial = openSearchPost(
            "/knowledge/_search",
            buildJsonObject {
                put("size", 1)
                putJsonObject("query") {
                    putJsonObject("match_all") {}
                }
            }
        )
        require(initial.status == HttpStatusCode.OK) {
            "OpenSearch sample lookup failed: HTTP ${initial.status.value} ${initial.bodyAsText()}"
        }
        val firstSource = json.parseToJsonElement(initial.bodyAsText()).jsonObject["hits"]?.jsonObject
            ?.get("hits")?.jsonArray?.firstOrNull()?.jsonObject?.get("_source")?.jsonObject
        require(firstSource != null) { "OpenSearch knowledge index contains no sample documents" }
        val searchable = listOf("title", "text")
            .mapNotNull { firstSource[it]?.jsonPrimitive?.content }
            .joinToString(" ")
        sampleQuery = Regex("[A-Za-z0-9]{4,}").find(searchable)?.value
            ?: error("OpenSearch sample document has no searchable term")
    }

    test("OpenSearch cluster is healthy") {
        val response = openSearchGet("/_cluster/health")
        require(response.status == HttpStatusCode.OK) {
            "OpenSearch health failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
    }

    test("knowledge index exists") {
        val response = openSearchGet("/knowledge")
        require(response.status == HttpStatusCode.OK) {
            "OpenSearch knowledge index is missing: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
    }

    test("BM25 text search returns indexed knowledge") {
        withTimeout(180_000L) {
            ensureSampleDocuments()
        }
        val response = openSearchPost(
            "/knowledge/_search",
            buildJsonObject {
                put("size", 5)
                putJsonObject("query") {
                    putJsonObject("multi_match") {
                        put("query", sampleQuery)
                        put("fields", JsonArray(listOf(JsonPrimitive("title^2"), JsonPrimitive("text"))))
                    }
                }
            }
        )
        require(response.status == HttpStatusCode.OK) {
            "OpenSearch search failed: HTTP ${response.status.value} ${response.bodyAsText()}"
        }
        val hits = json.parseToJsonElement(response.bodyAsText()).jsonObject["hits"]?.jsonObject
            ?.get("hits")?.jsonArray.orEmpty()
        require(hits.isNotEmpty()) { "OpenSearch returned no documents for stack knowledge sample" }
    }

    test("client helper normalizes OpenSearch results") {
        withTimeout(180_000L) {
            ensureSampleDocuments()
        }
        val result = client.search(sampleQuery, collections = listOf("stack_knowledge"), limit = 5, mode = "bm25")
        require(result.success) { "ServiceClient OpenSearch helper failed: ${result.results}" }
        val rows = result.results.jsonObject["results"]?.jsonArray.orEmpty()
        require(rows.isNotEmpty()) { "ServiceClient OpenSearch helper returned no normalized results" }
    }
}
