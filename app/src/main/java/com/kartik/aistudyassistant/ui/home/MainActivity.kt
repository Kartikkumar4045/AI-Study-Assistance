package com.kartik.aistudyassistant.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.SessionType
import com.kartik.aistudyassistant.ui.chat.ChatActivity
import com.kartik.aistudyassistant.ui.flashcard.FlashcardActivity
import com.kartik.aistudyassistant.ui.flashcard.FlashcardSetupActivity
import com.kartik.aistudyassistant.ui.profile.ProfileActivity
import com.kartik.aistudyassistant.ui.upload.UploadActivity
import com.kartik.aistudyassistant.ui.quiz.QuizActivity
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
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

        loadUserData()
        updateContinueLearningSection()
        setupClickListeners()
        setupBottomNavigation()
    }

    override fun onResume() {
        super.onResume()
        updateContinueLearningSection()
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

