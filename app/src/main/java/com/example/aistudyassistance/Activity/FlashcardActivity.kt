package com.example.aistudyassistance.Activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.example.aistudyassistance.Flashcard
import com.example.aistudyassistance.FlashcardPagerAdapter
import com.example.aistudyassistance.MainActivity
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class FlashcardActivity : AppCompatActivity() {

    private lateinit var tvFlashcardTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var viewPager: ViewPager2
    private lateinit var btnPrevious: MaterialButton
    private lateinit var btnFlip: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var layoutFlashcardContent: LinearLayout
    private lateinit var layoutDeckCompleted: LinearLayout
    private lateinit var tvCompletedCount: TextView
    private lateinit var btnRestartDeck: MaterialButton
    private lateinit var btnBackHome: MaterialButton

    private lateinit var adapter: FlashcardPagerAdapter
    private val flashcards = mutableListOf<Flashcard>()
    private var revealedStates = mutableListOf<Boolean>()
    private var difficultyStates = mutableListOf<Int>()

    private var source: String = FlashcardSetupActivity.SOURCE_TOPIC
    private var topicText: String = ""
    private var selectedNote: String = ""
    private var cardCount: Int = 10
    private var generatedFlashcards: List<Flashcard> = emptyList()
    private var currentCardIndex = 0
    private var swipeHintShown = false
    private var isFlipping = false
    private var isDeckCompleted = false

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
            showCompletionView()
        } else {
            val targetIndex = currentCardIndex.coerceIn(0, flashcards.lastIndex)
            viewPager.setCurrentItem(targetIndex, false)
            updateProgress(targetIndex)
            updateNavigationButtons(targetIndex)
        }
    }

    private fun readIntentData() {
        source = intent.getStringExtra(FlashcardSetupActivity.EXTRA_SOURCE)
            ?: FlashcardSetupActivity.SOURCE_TOPIC
        topicText = intent.getStringExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT) ?: ""
        selectedNote = intent.getStringExtra(FlashcardSetupActivity.EXTRA_SELECTED_NOTE) ?: ""
        cardCount = intent.getIntExtra(FlashcardSetupActivity.EXTRA_CARD_COUNT, 10).coerceIn(1, 20)

        val flashcardsJson = intent.getStringExtra(FlashcardSetupActivity.EXTRA_FLASHCARDS_JSON)
        generatedFlashcards = try {
            if (flashcardsJson.isNullOrBlank()) {
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
        tvProgress = findViewById(R.id.tvFlashcardProgress)
        progressBar = findViewById(R.id.flashcardProgressBar)
        viewPager = findViewById(R.id.viewPagerFlashcards)
        btnPrevious = findViewById(R.id.btnFlashcardPrevious)
        btnFlip = findViewById(R.id.btnFlashcardFlip)
        btnNext = findViewById(R.id.btnFlashcardNext)
        layoutFlashcardContent = findViewById(R.id.layoutFlashcardContent)
        layoutDeckCompleted = findViewById(R.id.layoutDeckCompleted)
        tvCompletedCount = findViewById(R.id.tvCompletedCount)
        btnRestartDeck = findViewById(R.id.btnRestartDeck)
        btnBackHome = findViewById(R.id.btnBackHome)

        val titleName = if (source == FlashcardSetupActivity.SOURCE_TOPIC) {
            topicText.ifBlank { "General Study" }
        } else {
            selectedNote.ifBlank { "My Notes" }
        }
        tvFlashcardTitle.text = "Flashcards: $titleName"

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
        difficultyStates = MutableList(flashcards.size) { FlashcardPagerAdapter.DIFFICULTY_NONE }.toMutableList()
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return

        currentCardIndex = savedInstanceState.getInt(KEY_CURRENT_INDEX, 0)
        swipeHintShown = savedInstanceState.getBoolean(KEY_SWIPE_HINT_SHOWN, false)
        isDeckCompleted = savedInstanceState.getBoolean(KEY_DECK_COMPLETED, false)

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
    }

    private fun setupViewPager() {
        adapter = FlashcardPagerAdapter(
            flashcards = flashcards,
            revealedStates = revealedStates,
            difficultyStates = difficultyStates,
            onCardTapped = { position ->
                if (!isFlipping) {
                    adapter.toggleRevealState(position)
                }
            },
            onDifficultyClicked = { _, difficulty ->
                val label = when (difficulty) {
                    FlashcardPagerAdapter.DIFFICULTY_EASY -> "Easy"
                    FlashcardPagerAdapter.DIFFICULTY_MEDIUM -> "Medium"
                    FlashcardPagerAdapter.DIFFICULTY_HARD -> "Hard"
                    else -> ""
                }
                Toast.makeText(this, "$label selected", Toast.LENGTH_SHORT).show()
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
                currentCardIndex = position
                updateProgress(position)
                updateNavigationButtons(position)
                isFlipping = adapter.isCardFlipping(position)
                updateFlipButtonState()
            }
        })
    }

    private fun setupListeners() {
        btnPrevious.setOnClickListener {
            val current = viewPager.currentItem
            if (current > 0) {
                viewPager.currentItem = current - 1
            }
        }

        btnFlip.setOnClickListener {
            if (!isFlipping) {
                adapter.toggleRevealState(viewPager.currentItem)
            }
        }

        btnNext.setOnClickListener {
            val current = viewPager.currentItem
            if (current < flashcards.lastIndex) {
                viewPager.currentItem = current + 1
            } else {
                showCompletionView()
            }
        }

        btnRestartDeck.setOnClickListener {
            restartDeck()
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

    private fun showSwipeHintOnce() {
        if (swipeHintShown) return
        Toast.makeText(this, "Swipe left or right to navigate cards →", Toast.LENGTH_SHORT).show()
        swipeHintShown = true
    }

    private fun showCompletionView() {
        isDeckCompleted = true
        layoutFlashcardContent.visibility = View.GONE
        layoutDeckCompleted.visibility = View.VISIBLE
        tvCompletedCount.text = "Total cards reviewed: ${flashcards.size}"
        updateFlipButtonState()
    }

    private fun restartDeck() {
        isDeckCompleted = false
        revealedStates = MutableList(flashcards.size) { false }.toMutableList()
        difficultyStates = MutableList(flashcards.size) { FlashcardPagerAdapter.DIFFICULTY_NONE }.toMutableList()
        currentCardIndex = 0
        isFlipping = false

        adapter = FlashcardPagerAdapter(
            flashcards = flashcards,
            revealedStates = revealedStates,
            difficultyStates = difficultyStates,
            onCardTapped = { position ->
                if (!isFlipping) {
                    adapter.toggleRevealState(position)
                }
            },
            onDifficultyClicked = { _, difficulty ->
                val label = when (difficulty) {
                    FlashcardPagerAdapter.DIFFICULTY_EASY -> "Easy"
                    FlashcardPagerAdapter.DIFFICULTY_MEDIUM -> "Medium"
                    FlashcardPagerAdapter.DIFFICULTY_HARD -> "Hard"
                    else -> ""
                }
                Toast.makeText(this, "$label selected", Toast.LENGTH_SHORT).show()
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_INDEX, viewPager.currentItem)
        outState.putBooleanArray(KEY_REVEALED_STATES, revealedStates.toBooleanArray())
        outState.putIntArray(KEY_DIFFICULTY_STATES, difficultyStates.toIntArray())
        outState.putBoolean(KEY_SWIPE_HINT_SHOWN, swipeHintShown)
        outState.putBoolean(KEY_DECK_COMPLETED, isDeckCompleted)
    }

    companion object {
        private const val KEY_CURRENT_INDEX = "current_card_index"
        private const val KEY_REVEALED_STATES = "revealed_states"
        private const val KEY_DIFFICULTY_STATES = "difficulty_states"
        private const val KEY_SWIPE_HINT_SHOWN = "swipe_hint_shown"
        private const val KEY_DECK_COMPLETED = "deck_completed"
    }
}


