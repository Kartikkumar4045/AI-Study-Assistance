package com.kartik.aistudyassistant.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PersistedChatMessage(
    val text: String,
    val isUser: Boolean,
    val messageType: String,
    val suggestions: List<String>,
    val isHelpful: Boolean
)

data class PersistedChatSession(
    val mode: String,
    val lastTopicQuery: String,
    val draftText: String,
    val activeDocumentText: String,
    val activeDocumentName: String,
    val activeImageSource: String,
    val updatedAtEpochMs: Long,
    val messages: List<PersistedChatMessage>
)

data class PersistedChatSessionSummary(
    val id: String,
    val title: String,
    val mode: String,
    val messageCount: Int,
    val updatedAtEpochMs: Long,
    val isActive: Boolean
)

object ChatSessionPrefs {
    private const val PREF_FILE = "chat_session_prefs"
    private const val KEY_SESSIONS = "chat_session_list"
    private const val KEY_ACTIVE_SESSION_ID = "chat_active_session_id"

    // Legacy keys from the previous active/previous implementation.
    private const val LEGACY_KEY_ACTIVE_SNAPSHOT = "chat_session_active_snapshot"
    private const val LEGACY_KEY_PREVIOUS_SNAPSHOT = "chat_session_previous_snapshot"

    private data class StoredSession(
        val id: String,
        val snapshot: PersistedChatSession
    )

    fun save(context: Context, snapshot: PersistedChatSession) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        val activeId = ensureActiveSessionId(prefs, sessions)
        val existingIndex = sessions.indexOfFirst { it.id == activeId }

        if (existingIndex >= 0) {
            sessions[existingIndex] = StoredSession(activeId, snapshot)
        } else {
            sessions.add(0, StoredSession(activeId, snapshot))
        }

        moveSessionToFront(sessions, activeId)
        trimToMaxSize(sessions)
        persistAll(prefs, sessions, activeId)
    }

    fun read(context: Context): PersistedChatSession? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        if (sessions.isEmpty()) return null

        val activeId = ensureActiveSessionId(prefs, sessions)
        val activeSession = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
        return activeSession?.snapshot
    }

    fun getActiveSessionId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        if (sessions.isEmpty()) return null
        return ensureActiveSessionId(prefs, sessions)
    }

    fun createNewSession(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        val newId = UUID.randomUUID().toString()
        sessions.add(
            0,
            StoredSession(
                id = newId,
                snapshot = emptySnapshot()
            )
        )

        trimToMaxSize(sessions)
        persistAll(prefs, sessions, newId)
        return newId
    }

    fun setActiveSession(context: Context, sessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        if (sessions.none { it.id == sessionId }) return false

        moveSessionToFront(sessions, sessionId)
        persistAll(prefs, sessions, sessionId)
        return true
    }

    fun getRecentSessions(context: Context, includeActive: Boolean = true): List<PersistedChatSessionSummary> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        if (sessions.isEmpty()) return emptyList()

        val activeId = ensureActiveSessionId(prefs, sessions)

        return sessions
            .filter { includeActive || it.id != activeId }
            .map { session ->
                PersistedChatSessionSummary(
                    id = session.id,
                    title = resolveTitle(session.snapshot),
                    mode = session.snapshot.mode,
                    messageCount = session.snapshot.messages.size,
                    updatedAtEpochMs = session.snapshot.updatedAtEpochMs,
                    isActive = session.id == activeId
                )
            }
    }

    fun readSession(context: Context, sessionId: String): PersistedChatSession? {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)
        return readSessions(prefs).firstOrNull { it.id == sessionId }?.snapshot
    }

    fun deleteSession(context: Context, sessionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        val deleteIndex = sessions.indexOfFirst { it.id == sessionId }
        if (deleteIndex < 0) return false

        sessions.removeAt(deleteIndex)

        val existingActiveId = prefs.getString(KEY_ACTIVE_SESSION_ID, null)
        val nextActiveId = when {
            sessions.isEmpty() -> null
            existingActiveId == sessionId -> sessions.first().id
            else -> existingActiveId
        }

        persistAll(prefs, sessions, nextActiveId)
        return true
    }

    fun findSessionIdByTopic(context: Context, topic: String): String? {
        if (topic.isBlank()) return null
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)
        val sessions = readSessions(prefs)

        // Sort sessions by updatedAtEpochMs descending
        val matchedSession = sessions
            .filter { it.snapshot.lastTopicQuery.equals(topic, ignoreCase = true) }
            .maxByOrNull { it.snapshot.updatedAtEpochMs }

        return matchedSession?.id
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)

        val sessions = readSessions(prefs)
        if (sessions.isEmpty()) return

        val activeId = ensureActiveSessionId(prefs, sessions)
        val filtered = sessions.filterNot { it.id == activeId }.toMutableList()
        val nextActiveId = filtered.firstOrNull()?.id
        persistAll(prefs, filtered, nextActiveId)
    }

    private fun emptySnapshot(): PersistedChatSession {
        return PersistedChatSession(
            mode = "EXPLAIN",
            lastTopicQuery = "",
            draftText = "",
            activeDocumentText = "",
            activeDocumentName = "",
            activeImageSource = "",
            updatedAtEpochMs = System.currentTimeMillis(),
            messages = emptyList()
        )
    }

    private fun resolveTitle(snapshot: PersistedChatSession): String {
        val userMessage = snapshot.messages.firstOrNull { it.isUser }?.text.orEmpty().trim()
        val topic = snapshot.lastTopicQuery.trim()
        
        if (topic.isNotBlank() && userMessage.isNotBlank()) {
            return "Topic: ${topic.take(20)} - ${userMessage.take(30)}"
        }
        if (topic.isNotBlank()) {
            return "Topic: ${topic.take(30)}"
        }
        if (snapshot.activeDocumentName.isNotBlank()) {
            return "File: ${snapshot.activeDocumentName.take(30)}"
        }
        if (userMessage.isNotBlank()) {
            return userMessage.take(40)
        }
        return "Chat session"
    }

    private fun migrateLegacyIfNeeded(prefs: android.content.SharedPreferences) {
        if (prefs.contains(KEY_SESSIONS)) return

        val sessions = mutableListOf<StoredSession>()
        val legacyActive = prefs.getString(LEGACY_KEY_ACTIVE_SNAPSHOT, null)
        val legacyPrevious = prefs.getString(LEGACY_KEY_PREVIOUS_SNAPSHOT, null)

        decodeFromRaw(legacyActive)?.let { active ->
            sessions.add(StoredSession(UUID.randomUUID().toString(), active))
        }
        decodeFromRaw(legacyPrevious)?.let { previous ->
            sessions.add(StoredSession(UUID.randomUUID().toString(), previous))
        }

        trimToMaxSize(sessions)
        val activeId = sessions.firstOrNull()?.id
        persistAll(prefs, sessions, activeId)

        prefs.edit()
            .remove(LEGACY_KEY_ACTIVE_SNAPSHOT)
            .remove(LEGACY_KEY_PREVIOUS_SNAPSHOT)
            .apply()
    }

    private fun decodeFromRaw(raw: String?): PersistedChatSession? {
        if (raw.isNullOrBlank()) return null
        return try {
            decode(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureActiveSessionId(
        prefs: android.content.SharedPreferences,
        sessions: MutableList<StoredSession>
    ): String {
        val existing = prefs.getString(KEY_ACTIVE_SESSION_ID, null)
        if (!existing.isNullOrBlank() && sessions.any { it.id == existing }) {
            return existing
        }

        if (sessions.isEmpty()) {
            val newId = UUID.randomUUID().toString()
            sessions.add(StoredSession(newId, emptySnapshot()))
            persistAll(prefs, sessions, newId)
            return newId
        }

        val fallback = sessions.first().id
        prefs.edit().putString(KEY_ACTIVE_SESSION_ID, fallback).apply()
        return fallback
    }

    private fun moveSessionToFront(sessions: MutableList<StoredSession>, sessionId: String) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index <= 0) return
        val selected = sessions.removeAt(index)
        sessions.add(0, selected)
    }

    private fun trimToMaxSize(sessions: MutableList<StoredSession>) {
        // No longer limiting session count.
    }

    private fun readSessions(prefs: android.content.SharedPreferences): MutableList<StoredSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return mutableListOf()
        return try {
            val root = JSONArray(raw)
            val sessions = mutableListOf<StoredSession>()
            for (index in 0 until root.length()) {
                val item = root.optJSONObject(index) ?: continue
                val id = item.optString("id").orEmpty()
                val snapshotJson = item.optJSONObject("snapshot") ?: continue
                if (id.isBlank()) continue
                sessions.add(StoredSession(id, decode(snapshotJson)))
            }
            sessions
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persistAll(
        prefs: android.content.SharedPreferences,
        sessions: List<StoredSession>,
        activeId: String?
    ) {
        val payload = JSONArray().apply {
            sessions.forEach { session ->
                put(
                    JSONObject().apply {
                        put("id", session.id)
                        put("snapshot", encode(session.snapshot))
                    }
                )
            }
        }

        prefs.edit().apply {
            putString(KEY_SESSIONS, payload.toString())
            if (activeId.isNullOrBlank()) {
                remove(KEY_ACTIVE_SESSION_ID)
            } else {
                putString(KEY_ACTIVE_SESSION_ID, activeId)
            }
            apply()
        }
    }

    private fun encode(snapshot: PersistedChatSession): JSONObject {
        return JSONObject().apply {
            put("mode", snapshot.mode)
            put("lastTopicQuery", snapshot.lastTopicQuery)
            put("draftText", snapshot.draftText)
            put("activeDocumentText", snapshot.activeDocumentText)
            put("activeDocumentName", snapshot.activeDocumentName)
            put("activeImageSource", snapshot.activeImageSource)
            put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
            put("messages", JSONArray().apply {
                snapshot.messages.forEach { message ->
                    put(
                        JSONObject().apply {
                            put("text", message.text)
                            put("isUser", message.isUser)
                            put("messageType", message.messageType)
                            put("suggestions", JSONArray(message.suggestions))
                            put("isHelpful", message.isHelpful)
                        }
                    )
                }
            })
        }
    }

    private fun decode(root: JSONObject): PersistedChatSession {
        val messages = mutableListOf<PersistedChatMessage>()
        val messagesArray = root.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until messagesArray.length()) {
            val item = messagesArray.optJSONObject(index) ?: continue
            val suggestionsArray = item.optJSONArray("suggestions") ?: JSONArray()
            val suggestions = mutableListOf<String>()
            for (suggestionIndex in 0 until suggestionsArray.length()) {
                suggestions.add(suggestionsArray.optString(suggestionIndex).orEmpty())
            }
            messages.add(
                PersistedChatMessage(
                    text = item.optString("text").orEmpty(),
                    isUser = item.optBoolean("isUser", false),
                    messageType = item.optString("messageType").ifBlank { "AI" },
                    suggestions = suggestions.filter { it.isNotBlank() },
                    isHelpful = item.optBoolean("isHelpful", false)
                )
            )
        }

        return PersistedChatSession(
            mode = root.optString("mode").ifBlank { "EXPLAIN" },
            lastTopicQuery = root.optString("lastTopicQuery").orEmpty(),
            draftText = root.optString("draftText").orEmpty(),
            activeDocumentText = root.optString("activeDocumentText").orEmpty(),
            activeDocumentName = root.optString("activeDocumentName").orEmpty(),
            activeImageSource = root.optString("activeImageSource").orEmpty(),
            updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L),
            messages = messages
        )
    }
}
