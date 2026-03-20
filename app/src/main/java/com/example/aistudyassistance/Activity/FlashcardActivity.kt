package com.example.aistudyassistance.Activity

import android.content.Intent
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.aistudyassistance.ContinueLearningPrefs
import com.example.aistudyassistance.Flashcard
import com.example.aistudyassistance.FlashcardPagerAdapter
import com.example.aistudyassistance.MainActivity
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FlashcardActivity : AppCompatActivity() {

    private lateinit var tvFlashcardTitle: TextView
    private lateinit var tvStudyModeLabel: TextView
    private lateinit var tvCardTimer: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var viewPager: ViewPager2
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnFlip: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var layoutFlashcardContent: LinearLayout
    private lateinit var layoutDeckCompleted: LinearLayout
    private lateinit var tvCompletedCount: TextView
    private lateinit var tvCompletedDifficulty: TextView
    private lateinit var tvCompletedAvgTime: TextView
    private lateinit var tvCompletedUnattempted: TextView
    private lateinit var btnRestartDeck: MaterialButton
    private lateinit var btnBackHome: MaterialButton
    private lateinit var btnReviewHardCards: MaterialButton
    private lateinit var btnReviewUnattemptedCards: MaterialButton
    private lateinit var toggleStudyMode: MaterialButtonToggleGroup
    private lateinit var btnModeQuickReview: MaterialButton
    private lateinit var btnModeActiveRecall: MaterialButton

    private lateinit var adapter: FlashcardPagerAdapter
    private val flashcards = mutableListOf<Flashcard>()
    private var revealedStates = mutableListOf<Boolean>()
    private var answerRevealedStates = mutableListOf<Boolean>()
    private var difficultyStates = mutableListOf<Int>()
    private var timeSpentMs = mutableListOf<Long>()

    private var source: String = FlashcardSetupActivity.SOURCE_TOPIC
    private var topicText: String = ""
    private var selectedNote: String = ""
    private var cardCount: Int = 10
    private var generatedFlashcards: List<Flashcard> = emptyList()
    private var activeRecallEnabled = false
    private var currentCardIndex = 0
    private var swipeHintShown = false
    private var isFlipping = false
    private var isDeckCompleted = false
    private var pendingRevealedStates: List<Boolean> = emptyList()
    private var pendingAnswerRevealedStates: List<Boolean> = emptyList()
    private var pendingDifficultyStates: List<Int> = emptyList()
    private var currentCardTimerStartMs = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerText(currentCardIndex)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_flashcard)

        readIntentData()
        initViews()
        setupFlashcards()
        restoreState(savedInstanceState)
        setupViewPager()
        setupListeners()
        showSwipeHintOnce()
        if (isDeckCompleted) {
            stopTimer()
            showCompletionView()
        } else {
            val targetIndex = currentCardIndex.coerceIn(0, flashcards.lastIndex)
            viewPager.setCurrentItem(targetIndex, false)
            updateProgress(targetIndex)
            updateNavigationButtons(targetIndex)
            startTimerForCard(targetIndex)
        }
    }

    private fun readIntentData() {
        source = intent.getStringExtra(FlashcardSetupActivity.EXTRA_SOURCE)
            ?: FlashcardSetupActivity.SOURCE_TOPIC
        topicText = intent.getStringExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT) ?: ""
        selectedNote = intent.getStringExtra(FlashcardSetupActivity.EXTRA_SELECTED_NOTE) ?: ""
        cardCount = intent.getIntExtra(FlashcardSetupActivity.EXTRA_CARD_COUNT, 10).coerceIn(1, 20)
        activeRecallEnabled = intent.getBooleanExtra(EXTRA_ACTIVE_RECALL_MODE, false)

        val flashcardsJson = intent.getStringExtra(FlashcardSetupActivity.EXTRA_FLASHCARDS_JSON).orEmpty()
        if (flashcardsJson.isBlank()) {
            val saved = ContinueLearningPrefs.readFlashcardProgress(this)
            if (saved.inProgress && saved.totalCards > 0 && saved.flashcardsJson.isNotBlank()) {
                val restored = try {
                    Json.decodeFromString<List<Flashcard>>(saved.flashcardsJson)
                } catch (_: Exception) {
                    emptyList()
                }

                if (restored.isNotEmpty()) {
                    source = saved.source.ifBlank { FlashcardSetupActivity.SOURCE_TOPIC }
                    topicText = saved.topic
                    selectedNote = saved.selectedNote
                    generatedFlashcards = restored
                    cardCount = restored.size
                    currentCardIndex = saved.currentIndex.coerceIn(0, restored.lastIndex)
                    pendingRevealedStates = saved.revealedStates
                    pendingAnswerRevealedStates = saved.answerRevealedStates
                    pendingDifficultyStates = saved.difficultyStates
                    return
                }
            }
        }

        generatedFlashcards = try {
            if (flashcardsJson.isBlank()) {
                emptyList()
            } else {
                Json.decodeFromString(flashcardsJson)
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (generatedFlashcards.isNotEmpty()) {
            cardCount = generatedFlashcards.size
        }
    }

    private fun initViews() {
        tvFlashcardTitle = findViewById(R.id.tvFlashcardTitle)
        tvStudyModeLabel = findViewById(R.id.tvStudyModeLabel)
        tvCardTimer = findViewById(R.id.tvCardTimer)
        tvProgress = findViewById(R.id.tvFlashcardProgress)
        progressBar = findViewById(R.id.flashcardProgressBar)
        viewPager = findViewById(R.id.viewPagerFlashcards)
        btnPrevious = findViewById(R.id.btnFlashcardPrevious)
        btnFlip = findViewById(R.id.btnFlashcardFlip)
        btnNext = findViewById(R.id.btnFlashcardNext)
        layoutFlashcardContent = findViewById(R.id.layoutFlashcardContent)
        layoutDeckCompleted = findViewById(R.id.layoutDeckCompleted)
        tvCompletedCount = findViewById(R.id.tvCompletedCount)
        tvCompletedDifficulty = findViewById(R.id.tvCompletedDifficulty)
        tvCompletedAvgTime = findViewById(R.id.tvCompletedAvgTime)
        tvCompletedUnattempted = findViewById(R.id.tvCompletedUnattempted)
        btnRestartDeck = findViewById(R.id.btnRestartDeck)
        btnBackHome = findViewById(R.id.btnBackHome)
        btnReviewHardCards = findViewById(R.id.btnReviewHardCards)
        btnReviewUnattemptedCards = findViewById(R.id.btnReviewUnattemptedCards)
        toggleStudyMode = findViewById(R.id.toggleStudyMode)
        btnModeQuickReview = findViewById(R.id.btnModeQuickReview)
        btnModeActiveRecall = findViewById(R.id.btnModeActiveRecall)

        val titleName = if (source == FlashcardSetupActivity.SOURCE_TOPIC) {
            topicText.ifBlank { "General Study" }
        } else {
            selectedNote.ifBlank { "My Notes" }
        }
        tvFlashcardTitle.text = "Flashcards: $titleName"
        updateStudyModeSelection()

        findViewById<ImageView>(R.id.ivBack).setOnClickListener {
            finish()
        }
    }

    private fun setupFlashcards() {
        flashcards.clear()
        if (generatedFlashcards.isNotEmpty()) {
            flashcards.addAll(generatedFlashcards)
        } else {
            val contextLabel = if (source == FlashcardSetupActivity.SOURCE_TOPIC) {
                topicText.ifBlank { "this topic" }
            } else {
                selectedNote.ifBlank { "your selected note" }
            }

            for (index in 1..cardCount) {
                flashcards.add(
                    Flashcard(
                        question = "Flashcard $index question from $contextLabel",
                        answer = "This is a placeholder answer for flashcard $index."
                    )
                )
            }
        }

        if (flashcards.isEmpty()) {
            flashcards.add(
                Flashcard(
                    question = "Flashcard generation failed",
                    answer = "Please go back and try again."
                )
            )
        }

        cardCount = flashcards.size

        revealedStates = MutableList(flashcards.size) { false }.toMutableList()
        answerRevealedStates = MutableList(flashcards.size) { false }.toMutableList()
        difficultyStates = MutableList(flashcards.size) { FlashcardPagerAdapter.DIFFICULTY_NONE }.toMutableList()
        timeSpentMs = MutableList(flashcards.size) { 0L }.toMutableList()
        applyPendingProgressState()
    }

    private fun applyPendingProgressState() {
        pendingRevealedStates.forEachIndexed { index, value ->
            if (index in revealedStates.indices) revealedStates[index] = value
        }
        pendingAnswerRevealedStates.forEachIndexed { index, value ->
            if (index in answerRevealedStates.indices) answerRevealedStates[index] = value
        }
        pendingDifficultyStates.forEachIndexed { index, value ->
            if (index in difficultyStates.indices) difficultyStates[index] = value
        }

        if (!activeRecallEnabled) {
            revealedStates.forEachIndexed { index, revealed ->
                if (revealed && index in answerRevealedStates.indices) {
                    answerRevealedStates[index] = true
                }
            }
        }

        currentCardIndex = currentCardIndex.coerceIn(0, flashcards.lastIndex.coerceAtLeast(0))
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        currentCardIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX, 0)
        swipeHintShown = savedInstanceState.getBoolean(KEY_SWIPE_HINT_SHOWN, false)
        isDeckCompleted = savedInstanceState.getBoolean(KEY_DECK_COMPLETED, false)
        activeRecallEnabled = savedInstanceState.getBoolean(KEY_ACTIVE_RECALL, activeRecallEnabled)
        updateStudyModeSelection()

        savedInstanceState.getBooleanArray(KEY_REVEALED_STATES)?.let { restored ->
            restored.forEachIndexed { index, value ->
                if (index in revealedStates.indices) {
                    revealedStates[index] = value
                }
            }
        }

        savedInstanceState.getIntArray(KEY_DIFFICULTY_STATES)?.let { restored ->
            restored.forEachIndexed { index, value ->
                if (index in difficultyStates.indices) {
                    difficultyStates[index] = value
                }
            }
        }

        savedInstanceState.getBooleanArray(KEY_ANSWER_REVEALED_STATES)?.let { restored ->
            restored.forEachIndexed { index, value ->
                if (index in answerRevealedStates.indices) {
                    answerRevealedStates[index] = value
                }
            }
        }

        savedInstanceState.getLongArray(KEY_TIME_SPENT_MS)?.let { restored ->
            restored.forEachIndexed { index, value ->
                if (index in timeSpentMs.indices) {
                    timeSpentMs[index] = value
                }
            }
        }

        if (!activeRecallEnabled) {
            revealedStates.forEachIndexed { index, revealed ->
                if (revealed && index in answerRevealedStates.indices) {
                    answerRevealedStates[index] = true
                }
            }
        }
    }

    private fun setupViewPager() {
        adapter = FlashcardPagerAdapter(
            flashcards = flashcards,
            revealedStates = revealedStates,
            answerRevealedStates = answerRevealedStates,
            difficultyStates = difficultyStates,
            isActiveRecallEnabled = { activeRecallEnabled },
            onCardTapped = { position ->
                if (!isFlipping) {
                    viewPager.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val changed = adapter.toggleRevealState(position)
                    if (changed) persistFlashcardProgress()
                }
            },
            onShowAnswerClicked = { position ->
                if (position == currentCardIndex) {
                    stopTimerForCard(position)
                }
                persistFlashcardProgress()
            },
            onDifficultyClicked = { _, _ ->
                viewPager.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                persistFlashcardProgress()
            },
            onAnswerRevealed = { position ->
                if (position == currentCardIndex) {
                    stopTimerForCard(position)
                }
                persistFlashcardProgress()
            },
            onFlipStateChanged = { position, flipping ->
                if (position == viewPager.currentItem) {
                    isFlipping = flipping
                    updateFlipButtonState()
                }
            }
        )

        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (currentCardIndex in timeSpentMs.indices) {
                    stopTimerForCard(currentCardIndex)
                }
                currentCardIndex = position
                updateProgress(position)
                updateNavigationButtons(position)
                isFlipping = adapter.isCardFlipping(position)
                updateFlipButtonState()
                startTimerForCard(position)
                persistFlashcardProgress()
            }
        })
    }

    private fun setupListeners() {
        toggleStudyMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val selectedActiveRecall = checkedId == R.id.btnModeActiveRecall
            applyStudyMode(selectedActiveRecall)
        }

        btnPrevious.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.currentItem = current - 1
            }
        }

        btnFlip.setOnClickListener {
            if (!isFlipping) {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                adapter.toggleRevealState(viewPager.currentItem)
            }
        }

        btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val current = viewPager.currentItem
            if (current < flashcards.lastIndex) {
                viewPager.currentItem = current + 1
            } else {
                showCompletionView()
            }
        }

        btnRestartDeck.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            restartDeck()
        }

        btnReviewHardCards.setOnClickListener {
            reviewHardCards()
        }

        btnReviewUnattemptedCards.setOnClickListener {
            reviewUnattemptedCards()
        }

        btnBackHome.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            finish()
        }
    }

    private fun updateProgress(position: Int) {
        val total = flashcards.size.coerceAtLeast(1)
        val current = position + 1
        tvProgress.text = "Card $current / $total"

        val progress = (current / total.toFloat())
        val percentage = (progress * 100).toInt()
        progressBar.setProgressCompat(percentage, true)
    }

    private fun updateNavigationButtons(position: Int) {
        btnPrevious.isEnabled = position > 0
        btnNext.isEnabled = true
        btnNext.alpha = 1f
        updateFlipButtonState()
    }

    private fun updateFlipButtonState() {
        val enabled = !isFlipping && !isDeckCompleted
        btnFlip.isEnabled = enabled
        btnFlip.alpha = if (enabled) 1f else 0.6f
    }

    private fun updateStudyModeSelection() {
        val selectedId = if (activeRecallEnabled) R.id.btnModeActiveRecall else R.id.btnModeQuickReview
        if (toggleStudyMode.checkedButtonId != selectedId) {
            toggleStudyMode.check(selectedId)
        }
    }

    private fun applyStudyMode(enableActiveRecall: Boolean) {
        if (activeRecallEnabled == enableActiveRecall) return

        activeRecallEnabled = enableActiveRecall
        if (!activeRecallEnabled) {
            revealedStates.forEachIndexed { index, revealed ->
                if (revealed && !answerRevealedStates.getOrElse(index) { false }) {
                    answerRevealedStates[index] = true
                }
            }
            adapter.notifyDataSetChanged()

            if (currentCardIndex in revealedStates.indices && revealedStates[currentCardIndex]) {
                stopTimerForCard(currentCardIndex)
            }
        } else {
            adapter.notifyDataSetChanged()
        }
        persistFlashcardProgress()
    }

    private fun startTimerForCard(index: Int) {
        if (index !in timeSpentMs.indices) return
        if (answerRevealedStates.getOrElse(index) { false }) {
            updateTimerText(index)
            return
        }

        stopTimer()
        currentCardTimerStartMs = SystemClock.elapsedRealtime()
        updateTimerText(index)
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun stopTimerForCard(index: Int) {
        if (index !in timeSpentMs.indices) {
            stopTimer()
            return
        }

        if (currentCardTimerStartMs > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - currentCardTimerStartMs
            timeSpentMs[index] = timeSpentMs[index] + elapsed
        }
        stopTimer()
        updateTimerText(index)
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        currentCardTimerStartMs = 0L
    }

    private fun getElapsedMsForCard(index: Int): Long {
        if (index !in timeSpentMs.indices) return 0L
        val base = timeSpentMs[index]
        val live = if (index == currentCardIndex && currentCardTimerStartMs > 0L) {
            SystemClock.elapsedRealtime() - currentCardTimerStartMs
        } else {
            0L
        }
        return base + live
    }

    private fun updateTimerText(index: Int) {
        val seconds = (getElapsedMsForCard(index) / 1000L).coerceAtLeast(0L)
        tvCardTimer.text = "⏱ ${seconds}s"
    }

    private fun showSwipeHintOnce() {
        if (swipeHintShown) return
        Toast.makeText(this, "Swipe left or right to navigate cards →", Toast.LENGTH_SHORT).show()
        swipeHintShown = true
    }

    private fun showCompletionView() {
        stopTimerForCard(currentCardIndex)
        isDeckCompleted = true
        val summaryTopic = if (source == FlashcardSetupActivity.SOURCE_TOPIC) topicText else selectedNote
        ContinueLearningPrefs.saveFlashcardActivity(this, summaryTopic.ifBlank { "General Study" }, flashcards.size)
        ContinueLearningPrefs.markFlashcardCompleted(this)
        tvStudyModeLabel.visibility = View.GONE
        toggleStudyMode.visibility = View.GONE
        layoutFlashcardContent.visibility = View.GONE
        layoutDeckCompleted.visibility = View.VISIBLE

        val easyCount = difficultyStates.count { it == FlashcardPagerAdapter.DIFFICULTY_EASY }
        val mediumCount = difficultyStates.count { it == FlashcardPagerAdapter.DIFFICULTY_MEDIUM }
        val hardCount = difficultyStates.count { it == FlashcardPagerAdapter.DIFFICULTY_HARD }
        val unattemptedCount = answerRevealedStates.count { !it }
        val avgSeconds = if (timeSpentMs.isEmpty()) 0 else (timeSpentMs.sum() / timeSpentMs.size) / 1000L

        tvCompletedCount.text = "Total Cards: ${flashcards.size}"
        tvCompletedDifficulty.text = "Easy: $easyCount | Medium: $mediumCount | Hard: $hardCount"
        tvCompletedAvgTime.text = "Avg Time: $avgSeconds sec"
        tvCompletedUnattempted.text = "Unattempted Cards: $unattemptedCount"
        updateFlipButtonState()
    }

    private fun reviewHardCards() {
        val hardCards = flashcards.filterIndexed { index, _ ->
            difficultyStates.getOrElse(index) { FlashcardPagerAdapter.DIFFICULTY_NONE } ==
                FlashcardPagerAdapter.DIFFICULTY_HARD
        }

        if (hardCards.isEmpty()) {
            Toast.makeText(this, "No hard cards to review", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent(this, FlashcardActivity::class.java).apply {
                putExtra(FlashcardSetupActivity.EXTRA_SOURCE, source)
                putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, topicText)
                putExtra(FlashcardSetupActivity.EXTRA_SELECTED_NOTE, selectedNote)
                putExtra(FlashcardSetupActivity.EXTRA_CARD_COUNT, hardCards.size)
                putExtra(FlashcardSetupActivity.EXTRA_FLASHCARDS_JSON, Json.encodeToString(hardCards))
                putExtra(EXTRA_ACTIVE_RECALL_MODE, activeRecallEnabled)
            }
        )
        finish()
    }

    private fun reviewUnattemptedCards() {
        val unattemptedCards = flashcards.filterIndexed { index, _ ->
            !answerRevealedStates.getOrElse(index) { false }
        }

        if (unattemptedCards.isEmpty()) {
            Toast.makeText(this, "No unattempted cards to review", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent(this, FlashcardActivity::class.java).apply {
                putExtra(FlashcardSetupActivity.EXTRA_SOURCE, source)
                putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, topicText)
                putExtra(FlashcardSetupActivity.EXTRA_SELECTED_NOTE, selectedNote)
                putExtra(FlashcardSetupActivity.EXTRA_CARD_COUNT, unattemptedCards.size)
                putExtra(FlashcardSetupActivity.EXTRA_FLASHCARDS_JSON, Json.encodeToString(unattemptedCards))
                putExtra(EXTRA_ACTIVE_RECALL_MODE, activeRecallEnabled)
            }
        )
        finish()
    }

    private fun restartDeck() {
        stopTimer()
        isDeckCompleted = false
        tvStudyModeLabel.visibility = View.VISIBLE
        toggleStudyMode.visibility = View.VISIBLE
        revealedStates = MutableList(flashcards.size) { false }.toMutableList()
        answerRevealedStates = MutableList(flashcards.size) { false }.toMutableList()
        difficultyStates = MutableList(flashcards.size) { FlashcardPagerAdapter.DIFFICULTY_NONE }.toMutableList()
        timeSpentMs = MutableList(flashcards.size) { 0L }.toMutableList()
        currentCardIndex = 0
        isFlipping = false

        adapter = FlashcardPagerAdapter(
            flashcards = flashcards,
            revealedStates = revealedStates,
            answerRevealedStates = answerRevealedStates,
            difficultyStates = difficultyStates,
            isActiveRecallEnabled = { activeRecallEnabled },
            onCardTapped = { position ->
                if (!isFlipping) {
                    val changed = adapter.toggleRevealState(position)
                    if (changed) persistFlashcardProgress()
                }
            },
            onShowAnswerClicked = { position ->
                if (position == currentCardIndex) {
                    stopTimerForCard(position)
                }
                persistFlashcardProgress()
            },
            onDifficultyClicked = { _, _ ->
                persistFlashcardProgress()
            },
            onAnswerRevealed = { position ->
                if (position == currentCardIndex) {
                    stopTimerForCard(position)
                }
                persistFlashcardProgress()
            },
            onFlipStateChanged = { position, flipping ->
                if (position == viewPager.currentItem) {
                    isFlipping = flipping
                    updateFlipButtonState()
                }
            }
        )

        viewPager.adapter = adapter
        layoutDeckCompleted.visibility = View.GONE
        layoutFlashcardContent.visibility = View.VISIBLE
        viewPager.setCurrentItem(0, false)
        updateProgress(0)
        updateNavigationButtons(0)
        startTimerForCard(0)
        persistFlashcardProgress()
    }

    private fun persistFlashcardProgress() {
        if (isDeckCompleted || flashcards.isEmpty()) return
        val safeIndex = if (::viewPager.isInitialized) {
            viewPager.currentItem.coerceIn(0, flashcards.lastIndex)
        } else {
            currentCardIndex.coerceIn(0, flashcards.lastIndex)
        }

        val flashcardsJson = try {
            Json.encodeToString(flashcards)
        } catch (_: Exception) {
            ""
        }

        ContinueLearningPrefs.saveFlashcardProgress(
            context = this,
            topic = topicText,
            source = source,
            selectedNote = selectedNote,
            totalCards = flashcards.size,
            currentIndex = safeIndex,
            revealedStates = revealedStates.toList(),
            answerRevealedStates = answerRevealedStates.toList(),
            difficultyStates = difficultyStates.toList(),
            flashcardsJson = flashcardsJson,
            inProgress = true
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stopTimerForCard(currentCardIndex)
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_INDEX, viewPager.currentItem)
        outState.putBooleanArray(KEY_REVEALED_STATES, revealedStates.toBooleanArray())
        outState.putIntArray(KEY_DIFFICULTY_STATES, difficultyStates.toIntArray())
        outState.putBooleanArray(KEY_ANSWER_REVEALED_STATES, answerRevealedStates.toBooleanArray())
        outState.putLongArray(KEY_TIME_SPENT_MS, timeSpentMs.toLongArray())
        outState.putBoolean(KEY_SWIPE_HINT_SHOWN, swipeHintShown)
        outState.putBoolean(KEY_DECK_COMPLETED, isDeckCompleted)
        outState.putBoolean(KEY_ACTIVE_RECALL, activeRecallEnabled)
    }

    override fun onDestroy() {
        stopTimer()
        super.onDestroy()
    }

    override fun onPause() {
        stopTimerForCard(currentCardIndex)
        persistFlashcardProgress()
        super.onPause()
    }

    companion object {
        const val EXTRA_ACTIVE_RECALL_MODE = "activeRecallMode"
        private const val KEY_CURRENT_INDEX = "current_card_index"
        private const val KEY_REVEALED_STATES = "revealed_states"
        private const val KEY_DIFFICULTY_STATES = "difficulty_states"
        private const val KEY_ANSWER_REVEALED_STATES = "answer_revealed_states"
        private const val KEY_TIME_SPENT_MS = "time_spent_ms"
        private const val KEY_SWIPE_HINT_SHOWN = "swipe_hint_shown"
        private const val KEY_DECK_COMPLETED = "deck_completed"
        private const val KEY_ACTIVE_RECALL = "active_recall"
    }
}


