package com.wludy.rolithax.launcher.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServerPropertiesEditorTest {
    @Test
    fun preservesCommentsUnknownPropertiesAndLineEndings() {
        val source = "# comment\r\nmotd = old\r\nunknown=x\r\n"
        assertEquals("# comment\r\nmotd = new\r\nunknown=x\r\n", updateProperty(source, "motd", "new"))
    }

    @Test
    fun updatesOnlyEffectiveDuplicateAndSkipsContinuedValues() {
        val source = "motd=first\nmotd=last\nlevel-name=long\\\n continued\n"
        assertEquals("motd=first\nmotd=updated\nlevel-name=long\\\n continued\n", updateProperty(source, "motd", "updated"))
        assertFalse("level-name" in editableProperties(source))
        assertEquals(source, updateProperty(source, "level-name", "world"))
    }
}
