package com.wludy.rolithax.launcher.desktop

import com.github.javakeyring.Keyring
import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsCredentialIntegrationTest {
    @Test
    fun windowsCredentialManagerWritesReadsAndDeletesOnlyTestRecord() {
        assumeTrue(System.getProperty("rolithax.testWindowsCredentialStore") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val service = "RolithaxLauncher.IntegrationTest"
        val account = UUID.randomUUID().toString()
        val secret = UUID.randomUUID().toString()
        Keyring.create(KeyringStorageType.WINDOWS_CREDENTIAL_STORE).use { keyring ->
            var present = false
            try {
                keyring.setPassword(service, account, secret)
                present = true
                assertEquals(secret, keyring.getPassword(service, account))
                keyring.deletePassword(service, account)
                present = false
                assertFailsWith<PasswordAccessException> { keyring.getPassword(service, account) }
            } finally {
                if (present) keyring.deletePassword(service, account)
            }
        }
    }
}
