package com.kartik.aistudyassistant.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuItemCompat
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.core.utils.SmartSensorManager
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import com.kartik.aistudyassistant.data.local.SessionType
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.model.AuthResult
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.ui.auth.SignInActivity
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
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import java.util.WeakHashMap

class MainActivity : AppCompatActivity() {

    private data class BottomSheetSensorUi(
        val eyeCareOverlay: View,
        val focusBanner: TextView,
        var hideFocusRunnable: Runnable? = null
    )

    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager
    private lateinit var tvWelcome: TextView
    private lateinit var ivProfileImage: ImageView
    private lateinit var bottomNav: BottomNavigationView
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
    private lateinit var tvProgressDayStreak: TextView
    private lateinit var tvProgressQuizzes: TextView
    private lateinit var tvProgressTopics: TextView
    private lateinit var tileProgressStreak: LinearLayout
    private lateinit var tileProgressQuizzes: LinearLayout
    private lateinit var tileProgressTopics: LinearLayout

    private lateinit var sensorManager: SmartSensorManager
    private lateinit var cardSensorStatus: CardView
    private lateinit var tvSensorStatus: TextView
    private lateinit var viewEyeCareOverlay: View
    private val bottomSheetSensorUi = WeakHashMap<BottomSheetDialog, BottomSheetSensorUi>()
    private var isFocusModeActive = false
    private var isEyeCareActive = false

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
        authManager = AuthManager(this)
        tvWelcome = findViewById(R.id.tvWelcome)
        ivProfileImage = findViewById(R.id.ivProfileImage)
        
        cardSensorStatus = findViewById(R.id.cardSensorStatus)
        tvSensorStatus = findViewById(R.id.tvSensorStatus)
        viewEyeCareOverlay = findViewById(R.id.viewEyeCareOverlay)

        initContinueLearningViews()
        initRecentActivityViews()
        initStudyProgressViews()

        setupSensors()
        loadUserData()
        updateContinueLearningSection()
        updateRecentActivitySection()
        updateProfileAvatarUi()
        setupClickListeners()
        setupBottomNavigation()
        enforceVerificationGate()
    }

    private fun setupSensors() {
        sensorManager = SmartSensorManager(this)
        sensorManager.onFocusModeChanged = { isFocusActive ->
            isFocusModeActive = isFocusActive
            runOnUiThread {
                if (isFocusActive) {
                    cardSensorStatus.visibility = View.VISIBLE
                    cardSensorStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.accent_purple))
                    tvSensorStatus.text = getString(R.string.sensor_focus_mode_on)
                    tvSensorStatus.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_lock_idle_lock, 0, 0, 0)
                } else {
                    cardSensorStatus.visibility = View.VISIBLE
                    cardSensorStatus.setCardBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
                    tvSensorStatus.text = getString(R.string.sensor_focus_mode_off)
                    tvSensorStatus.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_lock_idle_alarm, 0, 0, 0)
                    
                    // Hide after 3 seconds
                    cardSensorStatus.postDelayed({
                        cardSensorStatus.visibility = View.GONE
                    }, 3000)
                }
                tvSensorStatus.compoundDrawableTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
                applyBottomSheetSensorUi(fromSensorEvent = true)
            }
        }

        sensorManager.onEyeCareChanged = { isEyeCareActive ->
            this.isEyeCareActive = isEyeCareActive
            runOnUiThread {
                viewEyeCareOverlay.visibility = if (isEyeCareActive) View.VISIBLE else View.GONE
                if (isEyeCareActive) {
                    Toast.makeText(this, getString(R.string.sensor_low_light_detected), Toast.LENGTH_SHORT).show()
                }
                applyBottomSheetSensorUi(fromSensorEvent = true)
            }
        }
    }

    private fun bindSensorUiToBottomSheet(dialog: BottomSheetDialog) {
        dialog.setOnShowListener {
            val ui = ensureBottomSheetSensorUi(dialog) ?: return@setOnShowListener
            bottomSheetSensorUi[dialog] = ui
            applyBottomSheetSensorUi(dialog, ui, fromSensorEvent = false)
        }
        dialog.setOnDismissListener {
            removeBottomSheetSensorUi(dialog)
        }
    }

    private fun ensureBottomSheetSensorUi(dialog: BottomSheetDialog): BottomSheetSensorUi? {
        bottomSheetSensorUi[dialog]?.let { return it }

        val decorView = dialog.window?.decorView as? FrameLayout ?: return null
        val eyeCareOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.sensor_eye_care_overlay))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = dpToPx(80).toFloat()
        }

        val focusBanner = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(24)
            }
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            elevation = dpToPx(82).toFloat()
            visibility = View.GONE
        }

        decorView.addView(eyeCareOverlay)
        decorView.addView(focusBanner)
        return BottomSheetSensorUi(eyeCareOverlay = eyeCareOverlay, focusBanner = focusBanner)
    }

    private fun applyBottomSheetSensorUi(fromSensorEvent: Boolean) {
        val entries = bottomSheetSensorUi.entries.toList()
        entries.forEach { (dialog, ui) ->
            if (!dialog.isShowing) return@forEach
            applyBottomSheetSensorUi(dialog, ui, fromSensorEvent)
        }
    }

    private fun applyBottomSheetSensorUi(
        dialog: BottomSheetDialog,
        ui: BottomSheetSensorUi,
        fromSensorEvent: Boolean
    ) {
        if (!dialog.isShowing) return

        ui.eyeCareOverlay.visibility = if (isEyeCareActive) View.VISIBLE else View.GONE

        ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }
        ui.hideFocusRunnable = null

        if (isFocusModeActive) {
            ui.focusBanner.visibility = View.VISIBLE
            ui.focusBanner.text = getString(R.string.sensor_focus_mode_on)
            ui.focusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_purple))
            return
        }

        if (!fromSensorEvent) {
            ui.focusBanner.visibility = View.GONE
            return
        }

        ui.focusBanner.visibility = View.VISIBLE
        ui.focusBanner.text = getString(R.string.sensor_focus_mode_off)
        ui.focusBanner.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_green))
        val hideRunnable = Runnable {
            ui.focusBanner.visibility = View.GONE
        }
        ui.hideFocusRunnable = hideRunnable
        ui.focusBanner.postDelayed(hideRunnable, 3000)
    }

    private fun removeBottomSheetSensorUi(dialog: BottomSheetDialog) {
        val ui = bottomSheetSensorUi.remove(dialog) ?: return
        ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }
        (ui.eyeCareOverlay.parent as? ViewGroup)?.removeView(ui.eyeCareOverlay)
        (ui.focusBanner.parent as? ViewGroup)?.removeView(ui.focusBanner)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun enforceVerificationGate() {
        authManager.checkCurrentUserVerification { result ->
            when (result) {
                is AuthResult.Success -> Unit
                is AuthResult.VerificationRequired -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    authManager.signOut()
                    startActivity(Intent(this, SignInActivity::class.java))
                    finish()
                }
                else -> {
                    authManager.signOut()
                    startActivity(Intent(this, SignInActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.start()
        loadUserData()
        updateContinueLearningSection()
        updateRecentActivitySection()
        updateProfileAvatarUi()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.stop()
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

    private fun initStudyProgressViews() {
        tvProgressDayStreak = findViewById(R.id.tvProgressDayStreak)
        tvProgressQuizzes = findViewById(R.id.tvProgressQuizzes)
        tvProgressTopics = findViewById(R.id.tvProgressTopics)
        tileProgressStreak = findViewById(R.id.tileProgressStreak)
        tileProgressQuizzes = findViewById(R.id.tileProgressQuizzes)
        tileProgressTopics = findViewById(R.id.tileProgressTopics)
    }

    private fun updateStudyProgressSection() {
        val progress = ContinueLearningPrefs.readStudyProgress(this)

        tvProgressDayStreak.text = getString(R.string.home_progress_day_streak, progress.dayStreak)
        tvProgressQuizzes.text = getString(R.string.home_progress_quizzes, progress.totalQuizzes)
        tvProgressTopics.text = getString(R.string.home_progress_topics, progress.totalTopics)
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
            val topic = flashcardTopic.ifBlank { getString(R.string.home_general_study) }
            val current = (flashcardCurrentIndex + 1).coerceAtLeast(1)
            val total = flashcardTotal.coerceAtLeast(1)
            tvContinueFlashcardTitle.text = getString(R.string.home_resume_flashcards)
            tvContinueFlashcardSubtitle.text =
                getString(R.string.home_continue_flashcards_subtitle, topic, current, total)
            itemContinueFlashcard.visibility = View.VISIBLE
        } else {
            itemContinueFlashcard.visibility = View.GONE
        }

        if (hasQuiz) {
            val topic = quizTopic.ifBlank { getString(R.string.home_general_quiz) }
            val current = (quizCurrentIndex + 1).coerceAtLeast(1)
            val total = quizTotal.coerceAtLeast(1)
            tvContinueQuizTitle.text = getString(R.string.home_resume_quiz)
            tvContinueQuizSubtitle.text =
                getString(R.string.home_continue_quiz_subtitle, topic, current, total)
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
            updateStudyProgressSection()
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
        updateStudyProgressSection()
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
                title.text = getString(R.string.home_recent_quiz_title, topic)
                row.setOnClickListener {
                    startActivity(
                        Intent(this, QuizSetupActivity::class.java).apply {
                            putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, topic)
                            if (item.source.isNotEmpty()) {
                                putExtra(QuizSetupActivity.EXTRA_PREFILL_SOURCE, item.source)
                            }
                            if (item.noteName.isNotEmpty()) {
                                putExtra(QuizSetupActivity.EXTRA_PREFILL_NOTE_NAME, item.noteName)
                            }
                        }
                    )
                }
            }
            RecentActivityType.FLASHCARD -> {
                icon.setImageResource(android.R.drawable.ic_menu_gallery)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
                title.text = getString(R.string.home_recent_flashcards_title, topic)
                row.setOnClickListener {
                    startActivity(
                        Intent(this, FlashcardSetupActivity::class.java).apply {
                            putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, topic)
                            if (item.source.isNotEmpty()) {
                                putExtra(FlashcardSetupActivity.EXTRA_SOURCE, item.source)
                            }
                            if (item.noteName.isNotEmpty()) {
                                putExtra(FlashcardSetupActivity.EXTRA_PREFILL_NOTE_NAME, item.noteName)
                            }
                        }
                    )
                }
            }
            RecentActivityType.CHAT -> {
                icon.setImageResource(android.R.drawable.ic_menu_send)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_purple))
                title.text = getString(R.string.home_recent_chat_title, topic)
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
                title.text = getString(R.string.home_recent_upload_title, topic)
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
                RecentActivityType.QUIZ -> getString(R.string.home_general_quiz)
                RecentActivityType.FLASHCARD -> getString(R.string.home_general_study)
                RecentActivityType.CHAT -> getString(R.string.home_general_chat)
                RecentActivityType.UPLOAD -> getString(R.string.home_study_file)
            }
        }
    }

    private fun buildRecentTitle(item: RecentActivityItem, topic: String): String {
        return when (item.type) {
            RecentActivityType.QUIZ -> getString(R.string.home_recent_quiz_title, topic)
            RecentActivityType.FLASHCARD -> getString(R.string.home_recent_flashcards_title, topic)
            RecentActivityType.CHAT -> getString(R.string.home_recent_chat_title, topic)
            RecentActivityType.UPLOAD -> getString(R.string.home_recent_upload_title, topic)
        }
    }

    private fun showDeleteRecentActivityDialog(item: RecentActivityItem) {
        val title = buildRecentTitle(item, resolveRecentTopic(item))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.home_remove_recent_title))
            .setMessage(getString(R.string.home_remove_recent_message, title))
            .setPositiveButton(getString(R.string.home_remove)) { _, _ ->
                val removed = ContinueLearningPrefs.removeRecentActivity(this, item.id)
                if (!removed) {
                    showToast(getString(R.string.home_remove_recent_failed))
                    return@setPositiveButton
                }
                updateRecentActivitySection()
            }
            .setNegativeButton(getString(R.string.profile_logout_cancel_action), null)
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

        return getString(R.string.home_recent_subtitle_format, contextualText, relativeTime)
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
        val cached = UserProfilePrefs.read(this)

        if (cached.name.isNotBlank()) {
            tvWelcome.text = getString(R.string.home_welcome, cached.name)
        }

        if (user != null) {
            val userRef = FirebaseDatabase.getInstance().reference.child("Users").child(user.uid)
            userRef.get().addOnSuccessListener { snapshot ->
                val dbName = snapshot.child("name").getValue(String::class.java).orEmpty()
                val dbEmail = snapshot.child("email").getValue(String::class.java).orEmpty()

                val resolvedName = dbName
                    .ifBlank { user.displayName.orEmpty() }
                    .ifBlank { cached.name }
                    .ifBlank { dbEmail.ifBlank { user.email.orEmpty() }.substringBefore("@") }
                    .ifBlank { getString(R.string.profile_default_name) }

                tvWelcome.text = getString(
                    R.string.home_welcome,
                    resolvedName.replaceFirstChar { it.uppercase() }
                )

                UserProfilePrefs.save(
                    context = this,
                    name = resolvedName,
                    email = cached.email.ifBlank { dbEmail.ifBlank { user.email.orEmpty() } },
                    phone = cached.phone,
                    photoUri = cached.photoUri
                )
            }
        }
    }

    private fun setupClickListeners() {
        findViewById<CardView>(R.id.cvProfile).setOnClickListener {
            refreshHomeData()
        }

        ivProfileImage.setOnLongClickListener {
            openProfile()
            true
        }

        tileProgressStreak.setOnClickListener {
            showStreakDetailsBottomSheet()
        }

        tileProgressQuizzes.setOnClickListener {
            startActivity(Intent(this, QuizPerformanceActivity::class.java))
        }

        tileProgressTopics.setOnClickListener {
            startActivity(Intent(this, TopicsMasteryActivity::class.java))
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
                .setTitle(getString(R.string.home_clear_recent_title))
                .setMessage(getString(R.string.home_clear_recent_message))
                .setPositiveButton(getString(R.string.home_clear)) { _, _ ->
                    ContinueLearningPrefs.clearRecentActivities(this)
                    updateRecentActivitySection()
                }
                .setNegativeButton(getString(R.string.profile_logout_cancel_action), null)
                .show()
        }

        itemContinueFlashcard.setOnClickListener {
            if (!flashcardInProgress) {
                showToast(getString(R.string.home_no_flashcard_session))
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
                showToast(getString(R.string.home_no_quiz_session))
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
            .setTitle(getString(R.string.home_continue_learning_title))
            .setMessage(getString(R.string.home_continue_learning_message))
            .setPositiveButton(getString(R.string.home_resume)) { _, _ -> onResume() }
            .setNegativeButton(getString(R.string.home_start_over)) { _, _ -> onStartOver() }
            .show()
    }

    private fun setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottomNavigation)
        bottomNav.itemIconTintList = null
        applyBaseBottomNavIcons()
        bottomNav.selectedItemId = R.id.nav_home
        updateBottomNavProfileIcon(UserProfilePrefs.read(this).photoUri)

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

    private fun updateProfileAvatarUi() {
        val photoUri = UserProfilePrefs.read(this).photoUri
        
        // Ensure ivProfileImage remains the graduation cap image
        ivProfileImage.setImageResource(R.drawable.graduationcap201)

        if (::bottomNav.isInitialized) {
            updateBottomNavProfileIcon(photoUri)
        }
    }

    private fun updateBottomNavProfileIcon(photoUri: String) {
        val profileItem = bottomNav.menu.findItem(R.id.nav_profile)
        if (photoUri.isBlank()) {
            profileItem.icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_myplaces)
            return
        }

        MenuItemCompat.setIconTintList(profileItem, null)
        this.imageLoader.enqueue(
            ImageRequest.Builder(this)
                .data(photoUri)
                .crossfade(true)
                .transformations(CircleCropTransformation())
                .target(
                    onSuccess = { drawable ->
                        profileItem.icon = drawable
                    },
                    onError = {
                        profileItem.icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_myplaces)
                    }
                )
                .build()
        )
    }

    private fun applyBaseBottomNavIcons() {
        bottomNav.menu.findItem(R.id.nav_home).icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_today)
        bottomNav.menu.findItem(R.id.nav_chat).icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_send)
        bottomNav.menu.findItem(R.id.nav_upload).icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_add)
        bottomNav.menu.findItem(R.id.nav_quiz).icon = buildTintedBottomNavIcon(android.R.drawable.ic_menu_edit)
    }

    private fun buildTintedBottomNavIcon(resId: Int): Drawable? {
        val drawable = AppCompatResources.getDrawable(this, resId)?.mutate() ?: return null
        val tintList: ColorStateList = ContextCompat.getColorStateList(this, R.color.bottom_nav_tint) ?: return drawable
        DrawableCompat.setTintList(drawable, tintList)
        return drawable
    }

    private fun refreshHomeData() {
        loadUserData()
        updateContinueLearningSection()
        updateRecentActivitySection()
        updateProfileAvatarUi()
        showToast(getString(R.string.common_refreshed))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showStreakDetailsBottomSheet() {
        val details = ContinueLearningPrefs.readStudyStreakDetails(this)
        val dialog = BottomSheetDialog(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_streak_details, root, false)

        content.findViewById<TextView>(R.id.tvStreakValue).text =
            getString(R.string.home_streak_value, details.dayStreak)
        content.findViewById<TextView>(R.id.tvLastActive).text = buildLastActiveText(details.lastActiveDaysAgo)
        content.findViewById<TextView>(R.id.tvStreakHint).text =
            if (details.lastActiveDaysAgo == 0) getString(R.string.home_streak_hint_today)
            else getString(R.string.home_streak_hint_keep)

        val dotContainer = content.findViewById<LinearLayout>(R.id.llStreakDots)
        details.recentSevenDaysActive.forEach { isActive ->
            dotContainer.addView(createStreakDot(isActive))
        }

        content.findViewById<View>(R.id.btnStreakStartQuiz).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, QuizSetupActivity::class.java))
        }

        content.findViewById<View>(R.id.btnStreakReviewFlashcards).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, FlashcardSetupActivity::class.java))
        }

        dialog.setContentView(content)
        bindSensorUiToBottomSheet(dialog)
        dialog.show()
    }

    private fun buildLastActiveText(daysAgo: Int?): String {
        return when (daysAgo) {
            null -> getString(R.string.home_last_active_never)
            0 -> getString(R.string.home_last_active_today)
            1 -> getString(R.string.home_last_active_yesterday)
            else -> getString(R.string.home_last_active_days_ago, daysAgo)
        }
    }

    private fun createStreakDot(isActive: Boolean): View {
        val size = resources.getDimensionPixelSize(R.dimen.space_12)
        val margin = resources.getDimensionPixelSize(R.dimen.space_4)

        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = margin
            }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isActive) R.drawable.bg_streak_dot_active else R.drawable.bg_streak_dot_inactive
            )
        }
    }
}
