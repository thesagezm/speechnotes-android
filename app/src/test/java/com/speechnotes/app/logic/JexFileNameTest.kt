package com.speechnotes.app.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The .jex archive file name — iOS `JexExporter.fileName(scopeSuffix:)`
 * parity. The name carries the scope and the DATE, so two backups never
 * silently replace one another.
 */
class JexFileNameTest {

    @Test
    fun `all notes archive is named with the date`() {
        assertEquals(
            "speechnotes-2026-11-07.jex",
            JexExport.fileName("", LocalDate.of(2026, 11, 7)),
        )
    }

    @Test
    fun `scope slug rides in the name`() {
        assertEquals(
            "speechnotes-work-2026-11-07.jex",
            JexExport.fileName("-work", LocalDate.of(2026, 11, 7)),
        )
    }

    @Test
    fun `scope suffix slugs the first notebook name`() {
        assertEquals("-my-notes", JexExport.scopeSuffix(listOf("My Notes", "Other")))
        assertEquals("-a-b", JexExport.scopeSuffix(listOf("A B")))
        // Nothing usable in the name → the generic suffix, never an empty
        // one (an empty scope would mean "the whole library").
        assertEquals("-notebooks", JexExport.scopeSuffix(listOf("***")))
        assertEquals("-notebooks", JexExport.scopeSuffix(emptyList()))
    }

    @Test
    fun `scope suffix strips path and shell characters`() {
        // iOS parity: the slug keeps letters, digits and dashes, so
        // "../../etc/passwd" becomes "etcpasswd" — no dot, no slash, no
        // traversal left in the file name.
        assertEquals("-etcpasswd", JexExport.scopeSuffix(listOf("../../etc/passwd")))
        assertEquals("-rm--rf", JexExport.scopeSuffix(listOf("rm -rf")))
    }
}
