package com.wludy.rolithax.launcher.desktop

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopConnectionStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun metadataDoesNotContainCredentialAndCanBeRestored() {
        val file = temp.root.toPath().resolve("daemons.json")
        val secrets = FakeSecrets()
        val store = DesktopConnectionStore(file, secrets)
        val saved = store.add("测试", "https://example.invalid", false, "", "test-secret-value")
        assertFalse(java.nio.file.Files.readString(file).contains("test-secret-value"))
        assertEquals("test-secret-value", store.password(saved.id))
        assertEquals(saved, store.list().single())
        store.remove(saved.id)
        assertTrue(store.list().isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun keyringFailureDoesNotPersistConfiguration() {
        val file = temp.root.toPath().resolve("daemons.json")
        val secrets = FakeSecrets().apply { failPut = true }
        val store = DesktopConnectionStore(file, secrets)
        assertFailsWith<IllegalStateException> { store.add("测试", "https://example.invalid", false, "", "test-secret-value") }
        assertFalse(java.nio.file.Files.exists(file))
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun metadataWriteFailureCleansCredential() {
        val parent = temp.newFile("not-a-directory").toPath()
        val secrets = FakeSecrets()
        val store = DesktopConnectionStore(parent.resolve("daemons.json"), secrets)
        assertFailsWith<Exception> { store.add("测试", "https://example.invalid", false, "", "test-secret-value") }
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun failedRollbackProvidesOnlyCleanupIdentifier() {
        val parent = temp.newFile("not-a-directory").toPath()
        val secrets = FakeSecrets().apply { failDelete = true }
        val store = DesktopConnectionStore(parent.resolve("daemons.json"), secrets)
        val error = assertFailsWith<OrphanedCredentialException> {
            store.add("测试", "https://example.invalid", false, "", "test-secret-value")
        }
        assertFalse(error.message.orEmpty().contains("test-secret-value"))
        assertEquals("test-secret-value", secrets.values[error.credentialId])
        secrets.failDelete = false
        store.cleanupOrphan(error.credentialId)
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun keyringDeleteFailureKeepsMetadata() {
        val file = temp.root.toPath().resolve("daemons.json")
        val secrets = FakeSecrets()
        val store = DesktopConnectionStore(file, secrets)
        val saved = store.add("测试", "https://example.invalid", false, "", "test-secret-value")
        secrets.failDelete = true
        assertFailsWith<IllegalStateException> { store.remove(saved.id) }
        assertEquals(saved, store.list().single())
        assertEquals("test-secret-value", store.password(saved.id))
    }
}

private class FakeSecrets : DesktopSecrets {
    val values = mutableMapOf<String, String>()
    var failPut = false
    var failDelete = false
    override fun put(id: String, key: String) { if (failPut) error("unavailable"); values[id] = key }
    override fun get(id: String): String = values.getValue(id)
    override fun delete(id: String) { if (failDelete) error("unavailable"); values.remove(id) }
}
