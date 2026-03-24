package com.kartik.aistudyassistant.data.local

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

data class ContinueLearningSnapshot(
    val flashcardTopic: String,
    val flashcardCount: Int,
    val quizTopic: String,
    val quizScore: Int
)

@Serializable
enum class RecentActivityType {
    FLASHCARD,
    QUIZ,
    CHAT,
    UPLOAD
}

@Serializable
data class RecentActivityItem(
    val type: RecentActivityType,
    val topic: String,
    val scoreOrCount: Int,
    val timestamp: Long
)

enum class SessionType {
    FLASHCARD,
    QUIZ
}

enum class SessionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
}

data class SessionOverview(
    val topicId: String,
    val type: SessionType,
    val currentIndex: Int,
    val totalQuestions: Int,
    val status: SessionStatus
)

object ContinueLearningPrefs {
    private const val DEFAULT_SOURCE_TOPIC = "topic"
    private const val PREF_FILE = "continue_learning_prefs"
    const val KEY_LAST_FLASHCARD_TOPIC = "last_flashcard_topic"
    const val KEY_LAST_FLASHCARD_COUNT = "last_flashcard_count"
    const val KEY_LAST_QUIZ_TOPIC = "last_quiz_topic"
    const val KEY_LAST_QUIZ_SCORE = "last_quiz_score"
    private const val KEY_RECENT_ACTIVITY_JSON = "recent_activity_json"
    private const val MAX_RECENT_ACTIVITY_ITEMS = 20

    // Quiz in-progress keys.
    const val KEY_QUIZ_TOPIC = "quiz_topic"
    const val KEY_QUIZ_TOTAL_QUESTIONS = "quiz_total_questions"
    const val KEY_QUIZ_CURRENT_INDEX = "quiz_current_index"
    const val KEY_QUIZ_ANSWERS = "quiz_answers"
    const val KEY_QUIZ_IN_PROGRESS = "quiz_in_progress"
    private const val KEY_QUIZ_SOURCE = "quiz_source"
    private const val KEY_QUIZ_SELECTED_NOTE = "quiz_selected_note"
    private const val KEY_QUIZ_QUESTIONS_JSON = "quiz_questions_json"
    private const val KEY_QUIZ_STATUS = "quiz_status"

    // Flashcard in-progress keys.
    const val KEY_FLASHCARD_TOPIC = "flashcard_topic"
    const val KEY_FLASHCARD_TOTAL = "flashcard_total"
    const val KEY_FLASHCARD_CURRENT_INDEX = "flashcard_current_index"
    const val KEY_FLASHCARD_REVEALED_STATES = "flashcard_revealed_states"
    const val KEY_FLASHCARD_DIFFICULTY_STATES = "flashcard_difficulty_states"
    const val KEY_FLASHCARD_IN_PROGRESS = "flashcard_in_progress"
    private const val KEY_FLASHCARD_ANSWER_REVEALED_STATES = "flashcard_answer_revealed_states"
    private const val KEY_FLASHCARD_SOURCE = "flashcard_source"
    private const val KEY_FLASHCARD_SELECTED_NOTE = "flashcard_selected_note"
    private const val KEY_FLASHCARD_FLASHCARDS_JSON = "flashcard_items_json"
    private const val KEY_FLASHCARD_STATUS = "flashcard_status"

    private val json = Json { ignoreUnknownKeys = true }

    fun saveFlashcardActivity(context: Context, topic: String, cardCount: Int) {
        val safeTopic = topic.ifBlank { "General Study" }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_FLASHCARD_TOPIC, safeTopic)
            .putInt(KEY_LAST_FLASHCARD_COUNT, cardCount.coerceAtLeast(0))
            .apply()

        appendRecentActivity(
            context = context,
            type = RecentActivityType.FLASHCARD,
            topic = safeTopic,
            scoreOrCount = cardCount.coerceAtLeast(0)
        )
    }

    fun saveQuizActivity(context: Context, topic: String, score: Int) {
        val safeTopic = topic.ifBlank { "General Quiz" }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_QUIZ_TOPIC, safeTopic)
            .putInt(KEY_LAST_QUIZ_SCORE, score.coerceAtLeast(0))
            .apply()

        appendRecentActivity(
            context = context,
            type = RecentActivityType.QUIZ,
            topic = safeTopic,
            scoreOrCount = score.coerceAtLeast(0)
        )
    }

    fun saveChatActivity(context: Context, topic: String) {
        val safeTopic = topic.ifBlank { "General Chat" }
        appendRecentActivity(
            context = context,
            type = RecentActivityType.CHAT,
            topic = safeTopic,
            scoreOrCount = 0
        )
    }

    fun saveUploadActivity(context: Context, fileName: String) {
        val safeName = fileName.ifBlank { "Study File" }
        appendRecentActivity(
            context = context,
            type = RecentActivityType.UPLOAD,
            topic = safeName,
            scoreOrCount = 0
        )
    }

    fun readRecentActivities(context: Context, limit: Int = 5): List<RecentActivityItem> {
        val safeLimit = limit.coerceAtLeast(1)
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_ACTIVITY_JSON, "[]").orEmpty()
        return decodeRecentActivityList(raw)
            .sortedByDescending { it.timestamp }
            .take(safeLimit)
    }

    fun clearRecentActivities(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_RECENT_ACTIVITY_JSON)
            .apply()
    }

    fun read(context: Context): ContinueLearningSnapshot {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return ContinueLearningSnapshot(
            flashcardTopic = prefs.getString(KEY_LAST_FLASHCARD_TOPIC, "").orEmpty(),
            flashcardCount = prefs.getInt(KEY_LAST_FLASHCARD_COUNT, 0),
            quizTopic = prefs.getString(KEY_LAST_QUIZ_TOPIC, "").orEmpty(),
            quizScore = prefs.getInt(KEY_LAST_QUIZ_SCORE, 0)
        )
    }

    data class QuizProgressSnapshot(
        val topic: String,
        val source: String,
        val selectedNoteId: String,
        val totalQuestions: Int,
        val currentIndex: Int,
        val answers: List<Int>,
        val questionsJson: String,
        val inProgress: Boolean
    )

    data class FlashcardProgressSnapshot(
        val topic: String,
        val source: String,
        val selectedNote: String,
        val totalCards: Int,
        val currentIndex: Int,
        val revealedStates: List<Boolean>,
        val answerRevealedStates: List<Boolean>,
        val difficultyStates: List<Int>,
        val flashcardsJson: String,
        val inProgress: Boolean
    )

    fun saveQuizProgress(
        context: Context,
        topic: String,
        source: String,
        selectedNoteId: String,
        totalQuestions: Int,
        currentIndex: Int,
        answers: List<Int>,
        questionsJson: String,
        inProgress: Boolean
    ) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUIZ_TOPIC, topic)
            .putString(KEY_QUIZ_SOURCE, source)
            .putString(KEY_QUIZ_SELECTED_NOTE, selectedNoteId)
            .putInt(KEY_QUIZ_TOTAL_QUESTIONS, totalQuestions.coerceAtLeast(0))
            .putInt(KEY_QUIZ_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
            .putString(KEY_QUIZ_ANSWERS, encodeIntList(answers))
            .putString(KEY_QUIZ_QUESTIONS_JSON, questionsJson)
            .putBoolean(KEY_QUIZ_IN_PROGRESS, inProgress)
            .putString(KEY_QUIZ_STATUS, if (inProgress) SessionStatus.IN_PROGRESS.name else SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun readQuizProgress(context: Context): QuizProgressSnapshot {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return QuizProgressSnapshot(
            topic = prefs.getString(KEY_QUIZ_TOPIC, "").orEmpty(),
            source = prefs.getString(KEY_QUIZ_SOURCE, "topic").orEmpty().ifBlank { "topic" },
            selectedNoteId = prefs.getString(KEY_QUIZ_SELECTED_NOTE, "").orEmpty(),
            totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0),
            currentIndex = prefs.getInt(KEY_QUIZ_CURRENT_INDEX, 0),
            answers = decodeIntList(prefs.getString(KEY_QUIZ_ANSWERS, "[]").orEmpty()),
            questionsJson = prefs.getString(KEY_QUIZ_QUESTIONS_JSON, "").orEmpty(),
            inProgress = prefs.getBoolean(KEY_QUIZ_IN_PROGRESS, false)
        )
    }

    fun clearQuizProgress(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUIZ_TOPIC)
            .remove(KEY_QUIZ_SOURCE)
            .remove(KEY_QUIZ_SELECTED_NOTE)
            .remove(KEY_QUIZ_TOTAL_QUESTIONS)
            .remove(KEY_QUIZ_CURRENT_INDEX)
            .remove(KEY_QUIZ_ANSWERS)
            .remove(KEY_QUIZ_QUESTIONS_JSON)
            .putBoolean(KEY_QUIZ_IN_PROGRESS, false)
            .putString(KEY_QUIZ_STATUS, SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun markQuizCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0)
        prefs.edit()
            .putInt(KEY_QUIZ_CURRENT_INDEX, totalQuestions.coerceAtLeast(0))
            .putBoolean(KEY_QUIZ_IN_PROGRESS, false)
            .putString(KEY_QUIZ_STATUS, SessionStatus.COMPLETED.name)
            .remove(KEY_QUIZ_ANSWERS)
            .remove(KEY_QUIZ_QUESTIONS_JSON)
            .apply()
    }

    fun saveFlashcardProgress(
        context: Context,
        topic: String,
        source: String,
        selectedNote: String,
        totalCards: Int,
        currentIndex: Int,
        revealedStates: List<Boolean>,
        answerRevealedStates: List<Boolean>,
        difficultyStates: List<Int>,
        flashcardsJson: String,
        inProgress: Boolean
    ) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FLASHCARD_TOPIC, topic)
            .putString(KEY_FLASHCARD_SOURCE, source)
            .putString(KEY_FLASHCARD_SELECTED_NOTE, selectedNote)
            .putInt(KEY_FLASHCARD_TOTAL, totalCards.coerceAtLeast(0))
            .putInt(KEY_FLASHCARD_CURRENT_INDEX, currentIndex.coerceAtLeast(0))
            .putString(KEY_FLASHCARD_REVEALED_STATES, encodeBooleanList(revealedStates))
            .putString(KEY_FLASHCARD_ANSWER_REVEALED_STATES, encodeBooleanList(answerRevealedStates))
            .putString(KEY_FLASHCARD_DIFFICULTY_STATES, encodeIntList(difficultyStates))
            .putString(KEY_FLASHCARD_FLASHCARDS_JSON, flashcardsJson)
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, inProgress)
            .putString(KEY_FLASHCARD_STATUS, if (inProgress) SessionStatus.IN_PROGRESS.name else SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun readFlashcardProgress(context: Context): FlashcardProgressSnapshot {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return FlashcardProgressSnapshot(
            topic = prefs.getString(KEY_FLASHCARD_TOPIC, "").orEmpty(),
            source = prefs.getString(KEY_FLASHCARD_SOURCE, DEFAULT_SOURCE_TOPIC)
                .orEmpty()
                .ifBlank { DEFAULT_SOURCE_TOPIC },
            selectedNote = prefs.getString(KEY_FLASHCARD_SELECTED_NOTE, "").orEmpty(),
            totalCards = prefs.getInt(KEY_FLASHCARD_TOTAL, 0),
            currentIndex = prefs.getInt(KEY_FLASHCARD_CURRENT_INDEX, 0),
            revealedStates = decodeBooleanList(
                prefs.getString(KEY_FLASHCARD_REVEALED_STATES, "[]").orEmpty()
            ),
            answerRevealedStates = decodeBooleanList(
                prefs.getString(KEY_FLASHCARD_ANSWER_REVEALED_STATES, "[]").orEmpty()
            ),
            difficultyStates = decodeIntList(
                prefs.getString(KEY_FLASHCARD_DIFFICULTY_STATES, "[]").orEmpty()
            ),
            flashcardsJson = prefs.getString(KEY_FLASHCARD_FLASHCARDS_JSON, "").orEmpty(),
            inProgress = prefs.getBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
        )
    }

    fun clearFlashcardProgress(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FLASHCARD_TOPIC)
            .remove(KEY_FLASHCARD_SOURCE)
            .remove(KEY_FLASHCARD_SELECTED_NOTE)
            .remove(KEY_FLASHCARD_TOTAL)
            .remove(KEY_FLASHCARD_CURRENT_INDEX)
            .remove(KEY_FLASHCARD_REVEALED_STATES)
            .remove(KEY_FLASHCARD_ANSWER_REVEALED_STATES)
            .remove(KEY_FLASHCARD_DIFFICULTY_STATES)
            .remove(KEY_FLASHCARD_FLASHCARDS_JSON)
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
            .putString(KEY_FLASHCARD_STATUS, SessionStatus.NOT_STARTED.name)
            .apply()
    }

    fun markFlashcardCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val totalCards = prefs.getInt(KEY_FLASHCARD_TOTAL, 0)
        prefs.edit()
            .putInt(KEY_FLASHCARD_CURRENT_INDEX, totalCards.coerceAtLeast(0))
            .putBoolean(KEY_FLASHCARD_IN_PROGRESS, false)
            .putString(KEY_FLASHCARD_STATUS, SessionStatus.COMPLETED.name)
            .remove(KEY_FLASHCARD_REVEALED_STATES)
            .remove(KEY_FLASHCARD_ANSWER_REVEALED_STATES)
            .remove(KEY_FLASHCARD_DIFFICULTY_STATES)
            .remove(KEY_FLASHCARD_FLASHCARDS_JSON)
            .apply()
    }

    fun readActiveSessions(context: Context): List<SessionOverview> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val sessions = mutableListOf<SessionOverview>()

        val flashStatus = parseStatus(prefs.getString(KEY_FLASHCARD_STATUS, SessionStatus.NOT_STARTED.name))
        if (flashStatus == SessionStatus.IN_PROGRESS) {
            sessions.add(
                SessionOverview(
                    topicId = prefs.getString(KEY_FLASHCARD_TOPIC, "").orEmpty(),
                    type = SessionType.FLASHCARD,
                    currentIndex = prefs.getInt(KEY_FLASHCARD_CURRENT_INDEX, 0),
                    totalQuestions = prefs.getInt(KEY_FLASHCARD_TOTAL, 0),
                    status = flashStatus
                )
            )
        }

        val quizStatus = parseStatus(prefs.getString(KEY_QUIZ_STATUS, SessionStatus.NOT_STARTED.name))
        if (quizStatus == SessionStatus.IN_PROGRESS) {
            sessions.add(
                SessionOverview(
                    topicId = prefs.getString(KEY_QUIZ_TOPIC, "").orEmpty(),
                    type = SessionType.QUIZ,
                    currentIndex = prefs.getInt(KEY_QUIZ_CURRENT_INDEX, 0),
                    totalQuestions = prefs.getInt(KEY_QUIZ_TOTAL_QUESTIONS, 0),
                    status = quizStatus
                )
            )
        }

        return sessions
    }

    private fun encodeIntList(values: List<Int>): String {
        return try {
            json.encodeToString<List<Int>>(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeRecentActivityList(values: List<RecentActivityItem>): String {
        return try {
            json.encodeToString<List<RecentActivityItem>>(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun encodeBooleanList(values: List<Boolean>): String {
        return try {
            json.encodeToString<List<Boolean>>(values)
        } catch (_: Exception) {
            "[]"
        }
    }

    private fun decodeIntList(raw: String): List<Int> {
        return try {
            json.decodeFromString<List<Int>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeBooleanList(raw: String): List<Boolean> {
        return try {
            json.decodeFromString<List<Boolean>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeRecentActivityList(raw: String): List<RecentActivityItem> {
        return try {
            json.decodeFromString<List<RecentActivityItem>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendRecentActivity(
        context: Context,
        type: RecentActivityType,
        topic: String,
        scoreOrCount: Int
    ) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val existing = decodeRecentActivityList(
            prefs.getString(KEY_RECENT_ACTIVITY_JSON, "[]").orEmpty()
        )
        val updated = listOf(
            RecentActivityItem(
                type = type,
                topic = topic,
                scoreOrCount = scoreOrCount,
                timestamp = System.currentTimeMillis()
            )
        ) + existing

        prefs.edit()
            .putString(
                KEY_RECENT_ACTIVITY_JSON,
                encodeRecentActivityList(updated.take(MAX_RECENT_ACTIVITY_ITEMS))
            )
            .apply()
    }

    private fun parseStatus(raw: String?): SessionStatus {
        return try {
            SessionStatus.valueOf(raw.orEmpty())
        } catch (_: Exception) {
            SessionStatus.NOT_STARTED
        }
    }
}






