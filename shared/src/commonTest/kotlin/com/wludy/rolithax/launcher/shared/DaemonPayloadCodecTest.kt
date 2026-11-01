package com.wludy.rolithax.launcher.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DaemonPayloadCodecTest {
    @Test
    fun decodesTheExistingEnvelopeAndPreservesInstanceFields() {
        val normalized = normalizeInstanceList(
            """{"code":200,"data":[{"id":42,"name":"生存服","statusText":"运行中","unknown":true}]}"""
        )
        val instance = Json.parseToJsonElement(normalized).jsonArray.single().jsonObject
        assertEquals("42", instance["id"]?.jsonPrimitive?.content)
        assertEquals("生存服", instance["name"]?.jsonPrimitive?.content)
        assertEquals("运行中", instance["statusText"]?.jsonPrimitive?.content)
    }

    @Test
    fun rejectsErrorsMissingDataAndInvalidJson() {
        assertEquals("", normalizeInstanceList("""{"code":403,"data":[]}"""))
        assertEquals("", normalizeInstanceList("""{"code":200,"data":null}"""))
        assertEquals("", normalizeInstanceList("not json"))
        assertEquals("[]", normalizeInstanceList("""{"code":200,"data":[]}"""))
    }

    @Test
    fun normalizesDetailsFileListAndEmptyFileContent() {
        val info = Json.parseToJsonElement(normalizeInstanceInfo("""{"code":200,"data":{"id":7,"name":"测试"}}""")).jsonObject
        assertEquals("7", info["id"]?.jsonPrimitive?.content)
        val file = Json.parseToJsonElement(normalizeFileList("""{"code":200,"data":[{"name":"a.txt","type":"file"}]}""")).jsonArray.single().jsonObject
        assertEquals("a.txt", file["name"]?.jsonPrimitive?.content)
        assertEquals("\"\"", normalizeFileContent("""{"code":200,"data":""}"""))
        assertEquals("", normalizeFileContent("""{"code":403,"data":""}"""))
    }
}
