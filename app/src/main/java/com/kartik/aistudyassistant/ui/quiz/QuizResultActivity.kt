package com.kartik.aistudyassistant.ui.quiz

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.ui.home.MainActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class QuizResultActivity : AppCompatActivity() {

    private lateinit var tvScore: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnReviewAnswers: MaterialButton
    private lateinit var btnRetryQuiz: MaterialButton
    private lateinit var btnBackToHome: MaterialButton
    private lateinit var rvReview: RecyclerView
    private lateinit var toolbar: MaterialToolbar

    private var score = 0
    private var total = 0
    private var quizSource = ""
    private var topicText = ""
    private var selectedNoteId = ""
    private var questionCount = 5
    private val questions = mutableListOf<String>()
    private val userAnswers = mutableListOf<Int>()
    private val correctAnswers = mutableListOf<Int>()
    private val explanations = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_quiz_result)

        // Get extras
        score = intent.getIntExtra("score", 0)
        total = intent.getIntExtra("total", 5)
        quizSource = intent.getStringExtra("quizSource") ?: "topic"
        topicText = intent.getStringExtra("topicText") ?: ""
        selectedNoteId = intent.getStringExtra("selectedNoteId") ?: ""
        questionCount = intent.getIntExtra("questionCount", 5)
        intent.getStringArrayListExtra("questions")?.let { questions.addAll(it) }
        intent.getIntegerArrayListExtra("userAnswers")?.let { userAnswers.addAll(it) }
        intent.getIntegerArrayListExtra("correctAnswers")?.let { correctAnswers.addAll(it) }
        intent.getStringArrayListExtra("explanations")?.let { explanations.addAll(it) }

        initViews()
        displayResults()
    }

    private fun initViews() {
        tvScore = findViewById(R.id.tvScore)
        tvMessage = findViewById(R.id.tvMessage)
        btnReviewAnswers = findViewById(R.id.btnReviewAnswers)
        btnRetryQuiz = findViewById(R.id.btnRetryQuiz)
        btnBackToHome = findViewById(R.id.btnBackToHome)
        rvReview = findViewById(R.id.rvReview)
        toolbar = findViewById(R.id.toolbar)

        rvReview.layoutManager = LinearLayoutManager(this)
        rvReview.adapter = ReviewAdapter()

        btnReviewAnswers.setOnClickListener {
            rvReview.visibility = if (rvReview.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnRetryQuiz.setOnClickListener {
            // Go back to set up with same parameters
            val intent = Intent(this, QuizSetupActivity::class.java)
            intent.putExtra(QuizSetupActivity.EXTRA_PREFILL_SOURCE, quizSource)
            if (quizSource == "notes") {
                if (selectedNoteId.isNotBlank()) {
                    intent.putExtra(QuizSetupActivity.EXTRA_PREFILL_NOTE_NAME, selectedNoteId)
                }
            } else if (topicText.isNotBlank()) {
                intent.putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, topicText)
            }
            startActivity(intent)
            finish()
        }

        btnBackToHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun displayResults() {
        tvScore.text = "$score / $total"

        val percentage = if (total > 0) (score.toFloat() / total.toFloat()) * 100 else 0f
        tvMessage.text = when {
            percentage >= 80 -> "Excellent work!"
            percentage >= 60 -> "Great job!"
            percentage >= 40 -> "Good effort!"
            else -> "Keep practicing!"
        }
    }

    inner class ReviewAdapter : RecyclerView.Adapter<ReviewAdapter.ReviewViewHolder>() {

        private val optionLabels = listOf("A", "B", "C", "D")

        inner class ReviewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvQuestion: TextView = view.findViewById(R.id.tvQuestion)
            val tvUserAnswer: TextView = view.findViewById(R.id.tvUserAnswer)
            val tvCorrectAnswer: TextView = view.findViewById(R.id.tvCorrectAnswer)
            val tvExplanation: TextView = view.findViewById(R.id.tvExplanation)
            val ivStatus: ImageView = view.findViewById(R.id.ivStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReviewViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quiz_review, parent, false)
            return ReviewViewHolder(view)
        }

        override fun onBindViewHolder(holder: ReviewViewHolder, position: Int) {
            val question = questions.getOrNull(position).orEmpty()
            val userAnswer = userAnswers.getOrNull(position) ?: -1
            val correctAnswer = correctAnswers.getOrNull(position) ?: -1

            holder.tvQuestion.text = "Q${position + 1}: $question"

            val correctOption = optionLabels.getOrNull(correctAnswer) ?: "-"
            holder.tvCorrectAnswer.text = "Correct Answer: $correctOption"

            val userOption = optionLabels.getOrNull(userAnswer) ?: "Not Answered"
            holder.tvUserAnswer.text = "Your Answer: $userOption"

            // Placeholder explanations
            holder.tvExplanation.text = explanations.getOrElse(position) { "Explanation not available." }

            // Set status icon
            if (userAnswer >= 0 && userAnswer == correctAnswer) {
                holder.ivStatus.setImageResource(R.drawable.ic_correct)
                holder.ivStatus.setColorFilter(getColor(R.color.green))
            } else {
                holder.ivStatus.setImageResource(R.drawable.ic_wrong)
                holder.ivStatus.setColorFilter(getColor(R.color.red))
            }
        }

        override fun getItemCount() = questions.size
    }
}


