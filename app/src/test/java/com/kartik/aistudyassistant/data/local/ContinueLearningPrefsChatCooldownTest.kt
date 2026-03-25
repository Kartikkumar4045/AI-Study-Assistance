package com.kartik.aistudyassistant.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueLearningPrefsChatCooldownTest {

    @Test
    fun shouldLogChatForSession_returnsFalseWhenSessionAlreadyLogged() {
        val shouldLog = ContinueLearningPrefs.shouldLogChatForSession(
            existingLoggedSessionIds = setOf("session-1"),
            sessionId = "session-1"
        )

        assertFalse(shouldLog)
    }

    @Test
    fun shouldLogChatForSession_returnsTrueForNewSession() {
        val shouldLog = ContinueLearningPrefs.shouldLogChatForSession(
            existingLoggedSessionIds = setOf("session-1"),
            sessionId = "session-2"
        )

        assertTrue(shouldLog)
    }

    @Test
    fun shouldLogChatForSession_returnsTrueForBlankSessionId() {
        val shouldLog = ContinueLearningPrefs.shouldLogChatForSession(
            existingLoggedSessionIds = setOf("session-1"),
            sessionId = ""
        )

        assertTrue(shouldLog)
    }

    @Test
    fun updatedLoggedSessionIds_addsSessionOnce() {
        val updated = ContinueLearningPrefs.updatedLoggedSessionIds(
            existingLoggedSessionIds = setOf("session-1"),
            sessionId = "session-2"
        )

        assertEquals(2, updated.size)
        assertTrue(updated.contains("session-1"))
        assertTrue(updated.contains("session-2"))
    }

    @Test
    fun updatedLoggedSessionIds_keepsSetUnchangedForBlankSession() {
        val existing = setOf("session-1")

        val updated = ContinueLearningPrefs.updatedLoggedSessionIds(
            existingLoggedSessionIds = existing,
            sessionId = ""
        )

        assertEquals(existing, updated)
    }
}


