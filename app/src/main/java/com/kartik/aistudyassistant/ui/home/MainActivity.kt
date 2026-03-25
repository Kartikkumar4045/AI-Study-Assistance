package com.kartik.aistudyassistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import com.kartik.aistudyassistant.data.local.SessionType
import com.kartik.aistudyassistant.ui.chat.ChatActivity
import com.kartik.aistudyassistant.ui.flashcard.FlashcardActivity
import com.kartik.aistudyassistant.ui.flashcard.FlashcardSetupActivity
import com.kartik.aistudyassistant.ui.profile.ProfileActivity
import com.kartik.aistudyassistant.ui.upload.UploadActivity
import com.kartik.aistudyassistant.ui.quiz.QuizActivity
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var tvWelcome: TextView
    private lateinit var layoutContinueLearningSection: LinearLayout
    private lateinit var itemContinueFlashcard: LinearLayout
    private lateinit var itemContinueQuiz: LinearLayout
    private lateinit var viewContinueDivider: View
    private lateinit var tvContinueFlashcardTitle: TextView
    private lateinit var tvContinueFlashcardSubtitle: TextView
    private lateinit var tvContinueQuizTitle: TextView
    private lateinit var tvContinueQuizSubtitle: TextView
    private lateinit var llRecentActivity: LinearLayout
    private lateinit var tvRecentActivityEmpty: TextView
    private lateinit var tvRecentActivityClear: TextView

    private var flashcardInProgress = false
    private var flashcardCurrentIndex = 0
    private var flashcardTotal = 0
    private var flashcardTopic = ""
    private var quizInProgress = false
    private var quizCurrentIndex = 0
    private var quizTotal = 0
    private var quizTopic = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        tvWelcome = findViewById(R.id.tvWelcome)
        initContinueLearningViews()
        initRecentActivityViews()

        loadUserData()
        updateContinueLearningSection()
        updateRecentActivitySection()
        setupClickListeners()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        updateContinueLearningSection()
        updateRecentActivitySection()
    }

    private fun initContinueLearningViews() {
        layoutContinueLearningSection = findViewById(R.id.layoutContinueLearningSection)
        itemContinueFlashcard = findViewById(R.id.itemContinueFlashcard)
        itemContinueQuiz = findViewById(R.id.itemContinueQuiz)
        viewContinueDivider = findViewById(R.id.viewContinueDivider)
        tvContinueFlashcardTitle = findViewById(R.id.tvContinueFlashcardTitle)
        tvContinueFlashcardSubtitle = findViewById(R.id.tvContinueFlashcardSubtitle)
        tvContinueQuizTitle = findViewById(R.id.tvContinueQuizTitle)
        tvContinueQuizSubtitle = findViewById(R.id.tvContinueQuizSubtitle)
    }

    private fun initRecentActivityViews() {
        llRecentActivity = findViewById(R.id.llRecentActivity)
        tvRecentActivityEmpty = findViewById(R.id.tvRecentActivityEmpty)
        tvRecentActivityClear = findViewById(R.id.tvRecentActivityClear)
    }

    private fun updateContinueLearningSection() {
        val activeSessions = ContinueLearningPrefs.readActiveSessions(this)
        val quizProgress = ContinueLearningPrefs.readQuizProgress(this)
        val flashcardProgress = ContinueLearningPrefs.readFlashcardProgress(this)

        flashcardInProgress = activeSessions.any { it.type == SessionType.FLASHCARD } &&
            flashcardProgress.inProgress &&
            flashcardProgress.totalCards > 0 &&
            flashcardProgress.flashcardsJson.isNotBlank()
        flashcardCurrentIndex = flashcardProgress.currentIndex
        flashcardTotal = flashcardProgress.totalCards
        flashcardTopic = flashcardProgress.topic

        quizInProgress = activeSessions.any { it.type == SessionType.QUIZ } &&
            quizProgress.inProgress &&
            quizProgress.totalQuestions > 0 &&
            quizProgress.questionsJson.isNotBlank()
        quizCurrentIndex = quizProgress.currentIndex
        quizTotal = quizProgress.totalQuestions
        quizTopic = quizProgress.topic

        val hasFlashcard = flashcardInProgress
        val hasQuiz = quizInProgress

        if (hasFlashcard) {
            val topic = flashcardTopic.ifBlank { "General Study" }
            val current = (flashcardCurrentIndex + 1).coerceAtLeast(1)
            val total = flashcardTotal.coerceAtLeast(1)
            tvContinueFlashcardTitle.text = "Resume Flashcards"
            tvContinueFlashcardSubtitle.text = "Flashcards: $topic (Card $current/$total)"
            itemContinueFlashcard.visibility = View.VISIBLE
        } else {
            itemContinueFlashcard.visibility = View.GONE
        }

        if (hasQuiz) {
            val topic = quizTopic.ifBlank { "General Quiz" }
            val current = (quizCurrentIndex + 1).coerceAtLeast(1)
            val total = quizTotal.coerceAtLeast(1)
            tvContinueQuizTitle.text = "Resume Quiz"
            tvContinueQuizSubtitle.text = "Quiz: $topic (Q$current/$total)"
            itemContinueQuiz.visibility = View.VISIBLE
        } else {
            itemContinueQuiz.visibility = View.GONE
        }

        viewContinueDivider.visibility = if (hasFlashcard && hasQuiz) View.VISIBLE else View.GONE
        layoutContinueLearningSection.visibility = if (hasFlashcard || hasQuiz) View.VISIBLE else View.GONE
    }

    private fun updateRecentActivitySection() {
        val activities = ContinueLearningPrefs.readRecentActivities(this, limit = 5)

        llRecentActivity.removeAllViews()
        if (activities.isEmpty()) {
            tvRecentActivityEmpty.visibility = View.VISIBLE
            tvRecentActivityClear.visibility = View.GONE
            return
        }

        tvRecentActivityEmpty.visibility = View.GONE
        tvRecentActivityClear.visibility = View.VISIBLE
        activities.forEachIndexed { index, item ->
            llRecentActivity.addView(createRecentActivityRow(item))
            if (index < activities.lastIndex) {
                llRecentActivity.addView(createRecentActivityDivider())
            }
        }
    }

    private fun createRecentActivityRow(item: RecentActivityItem): View {
        val row = LayoutInflater.from(this).inflate(
            R.layout.item_recent_activity,
            llRecentActivity,
            false
        )

        val icon = row.findViewById<ImageView>(R.id.ivRecentIcon)
        val actions = row.findViewById<ImageView>(R.id.ivRecentActions)
        val title = row.findViewById<TextView>(R.id.tvRecentTitle)
        val subtitle = row.findViewById<TextView>(R.id.tvRecentSubtitle)

        val topic = resolveRecentTopic(item)

        when (item.type) {
            RecentActivityType.QUIZ -> {
                icon.setImageResource(android.R.drawable.ic_menu_edit)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_orange))
                title.text = "Quiz - $topic"
                row.setOnClickListener {
                    startActivity(
                        Intent(this, QuizSetupActivity::class.java).apply {
                            putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, topic)
                        }
                    )
                }
            }
            RecentActivityType.FLASHCARD -> {
                icon.setImageResource(android.R.drawable.ic_menu_gallery)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
                title.text = "Flashcards - $topic"
                row.setOnClickListener {
                    startActivity(
                        Intent(this, FlashcardSetupActivity::class.java).apply {
                            putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, topic)
                        }
                    )
                }
            }
            RecentActivityType.CHAT -> {
                icon.setImageResource(android.R.drawable.ic_menu_send)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_purple))
                title.text = "AI Chat - $topic"
                row.setOnClickListener {
                    startActivity(
                        Intent(this, ChatActivity::class.java).apply {
                            if (item.sessionId.isNotBlank()) {
                                putExtra(ChatActivity.EXTRA_SESSION_ID, item.sessionId)
                            }
                        }
                    )
                }
            }
            RecentActivityType.UPLOAD -> {
                icon.setImageResource(android.R.drawable.ic_menu_upload)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.primary))
                title.text = "Upload - $topic"
                row.setOnClickListener {
                    startActivity(Intent(this, UploadActivity::class.java))
                }
            }
        }

        subtitle.text = buildRecentSubtitle(item)
        actions.setOnClickListener {
            showDeleteRecentActivityDialog(item)
        }

        return row
    }

    private fun resolveRecentTopic(item: RecentActivityItem): String {
        return item.topic.ifBlank {
            when (item.type) {
                RecentActivityType.QUIZ -> "General Quiz"
                RecentActivityType.FLASHCARD -> "General Study"
                RecentActivityType.CHAT -> "General Chat"
                RecentActivityType.UPLOAD -> "Study File"
            }
        }
    }

    private fun buildRecentTitle(item: RecentActivityItem, topic: String): String {
        return when (item.type) {
            RecentActivityType.QUIZ -> "Quiz - $topic"
            RecentActivityType.FLASHCARD -> "Flashcards - $topic"
            RecentActivityType.CHAT -> "AI Chat - $topic"
            RecentActivityType.UPLOAD -> "Upload - $topic"
        }
    }

    private fun showDeleteRecentActivityDialog(item: RecentActivityItem) {
        val title = buildRecentTitle(item, resolveRecentTopic(item))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Remove from Recent Activity")
            .setMessage("Remove \"$title\" from Recent Activity?")
            .setPositiveButton("Remove") { _, _ ->
                val removed = ContinueLearningPrefs.removeRecentActivity(this, item.id)
                if (!removed) {
                    showToast("Could not remove this activity")
                    return@setPositiveButton
                }
                updateRecentActivitySection()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                ContextCompat.getColor(this, R.color.red)
            )
        }
        dialog.show()
    }

    private fun buildRecentSubtitle(item: RecentActivityItem): String {
        val relativeTime = DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()

        val contextualText = RecentActivitySubtitleFormatter.buildContextualText(item)

        return "$contextualText • $relativeTime"
    }


    private fun createRecentActivityDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
                marginEnd = resources.getDimensionPixelSize(R.dimen.space_12)
            }
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
        }
    }

    private fun loadUserData() {
        val user = auth.currentUser
        if (user != null) {
            val userRef = FirebaseDatabase.getInstance().reference.child("Users").child(user.uid)
            userRef.child("email").get().addOnSuccessListener { snapshot ->
                val email = snapshot.value?.toString() ?: user.email ?: "Student"
                val name = email.substringBefore("@")
                tvWelcome.text = "Hello, ${name.replaceFirstChar { it.uppercase() }} 👋"
            }
        }
    }

    private fun setupClickListeners() {
        findViewById<CardView>(R.id.cvProfile).setOnClickListener {
            openProfile()
        }

        findViewById<CardView>(R.id.cvAskAi).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<CardView>(R.id.cardChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<CardView>(R.id.cardUpload).setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java))
        }

        findViewById<CardView>(R.id.cardQuiz).setOnClickListener {
            startActivity(Intent(this, QuizSetupActivity::class.java))
        }

        findViewById<CardView>(R.id.cardFlashcards).setOnClickListener {
            startActivity(Intent(this, FlashcardSetupActivity::class.java))
        }

        tvRecentActivityClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear recent activity")
                .setMessage("Remove all items from Recent Activity?")
                .setPositiveButton("Clear") { _, _ ->
                    ContinueLearningPrefs.clearRecentActivities(this)
                    updateRecentActivitySection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        itemContinueFlashcard.setOnClickListener {
            if (!flashcardInProgress) {
                showToast("No flashcard session to resume")
                return@setOnClickListener
            }

            showResumeDialog(
                onResume = {
                    startActivity(Intent(this, FlashcardActivity::class.java))
                },
                onStartOver = {
                    ContinueLearningPrefs.clearFlashcardProgress(this)
                    startActivity(
                        Intent(this, FlashcardSetupActivity::class.java).apply {
                            putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, flashcardTopic)
                        }
                    )
                }
            )
        }

        itemContinueQuiz.setOnClickListener {
            if (!quizInProgress) {
                showToast("No quiz session to resume")
                return@setOnClickListener
            }

            showResumeDialog(
                onResume = {
                    startActivity(Intent(this, QuizActivity::class.java))
                },
                onStartOver = {
                    ContinueLearningPrefs.clearQuizProgress(this)
                    startActivity(
                        Intent(this, QuizSetupActivity::class.java).apply {
                            putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, quizTopic)
                        }
                    )
                }
            )
        }
    }

    private fun showResumeDialog(onResume: () -> Unit, onStartOver: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Continue Learning")
            .setMessage("Resume where you left?")
            .setPositiveButton("Resume") { _, _ -> onResume() }
            .setNegativeButton("Start Over") { _, _ -> onStartOver() }
            .show()
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }
                R.id.nav_upload -> {
                    startActivity(Intent(this, UploadActivity::class.java))
                    true
                }
                R.id.nav_quiz -> {
                    startActivity(Intent(this, QuizSetupActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    openProfile()
                    true
                }
                else -> false
            }
        }
    }

    private fun openProfile() {
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

