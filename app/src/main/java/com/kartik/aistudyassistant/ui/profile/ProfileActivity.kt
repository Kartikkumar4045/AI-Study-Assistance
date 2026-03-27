package com.kartik.aistudyassistant.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import com.kartik.aistudyassistant.AIStudyAssistanceApp
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.RecentActivityItem
import com.kartik.aistudyassistant.data.local.RecentActivityType
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.ui.auth.SignInActivity
import com.kartik.aistudyassistant.ui.chat.ChatActivity
import com.kartik.aistudyassistant.ui.flashcard.FlashcardSetupActivity
import com.kartik.aistudyassistant.ui.home.RecentActivitySubtitleFormatter
import com.kartik.aistudyassistant.ui.home.TopicsMasteryActivity
import com.kartik.aistudyassistant.ui.quiz.QuizSetupActivity
import com.kartik.aistudyassistant.ui.upload.UploadActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

class ProfileActivity : AppCompatActivity() {

    private lateinit var ivAvatarPhoto: ImageView
    private lateinit var tvAvatarInitial: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserEmail: TextView
    private lateinit var tvUserPhone: TextView
    private lateinit var tvLearningStreakValue: TextView
    private lateinit var tvLearningStudyTimeValue: TextView
    private lateinit var tvLearningQuizValue: TextView
    private lateinit var tvLearningLastActiveValue: TextView
    private lateinit var tvLearningInsight: TextView
    
    private lateinit var tvPerformanceAvgScore: TextView
    private lateinit var tvPerformanceHighScore: TextView
    private lateinit var tvPerformanceTopicsMastered: TextView
    private lateinit var tvPerformanceScoreHelper: TextView
    private lateinit var tvPerformanceGuidance: TextView
    private lateinit var tvPerformanceWeeklyInsight: TextView
    private lateinit var btnPracticeQuiz: MaterialButton
    private lateinit var btnReviewFlashcards: MaterialButton
    private lateinit var tvMaterialsCount: TextView
    private lateinit var btnManageMaterials: MaterialButton
    private lateinit var tvProfileRecentActivityEmpty: TextView
    private lateinit var llProfileRecentActivity: LinearLayout

    private lateinit var llConsistencyDots: LinearLayout

    private lateinit var authManager: AuthManager
    private var currentDisplayName: String = ""
    private var currentEmail: String = ""
    private var currentPhone: String = ""
    private var currentPhotoUri: String = ""
    
    private var materialsListener: ListenerRegistration? = null

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }

        val previousPhotoUri = currentPhotoUri
        currentPhotoUri = uri.toString()
        if (previousPhotoUri.isNotBlank() && previousPhotoUri != currentPhotoUri) {
            revokePersistedPhotoPermission(previousPhotoUri)
        }
        persistCachedProfile()
        updateAvatarView(currentDisplayName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)

        authManager = AuthManager(this)

        ivAvatarPhoto = findViewById(R.id.ivAvatarPhoto)
        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        tvUserName = findViewById(R.id.tvUserName)
        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvUserPhone = findViewById(R.id.tvUserPhone)
        tvLearningStreakValue = findViewById(R.id.tvLearningStreakValue)
        tvLearningStudyTimeValue = findViewById(R.id.tvLearningStudyTimeValue)
        tvLearningQuizValue = findViewById(R.id.tvLearningQuizValue)
        tvLearningLastActiveValue = findViewById(R.id.tvLearningLastActiveValue)
        tvLearningInsight = findViewById(R.id.tvLearningInsight)
        
        tvPerformanceAvgScore = findViewById(R.id.tvPerformanceAvgScore)
        tvPerformanceHighScore = findViewById(R.id.tvPerformanceHighScore)
        tvPerformanceTopicsMastered = findViewById(R.id.tvPerformanceTopicsMastered)
        tvPerformanceScoreHelper = findViewById(R.id.tvPerformanceScoreHelper)
        tvPerformanceGuidance = findViewById(R.id.tvPerformanceGuidance)
        tvPerformanceWeeklyInsight = findViewById(R.id.tvPerformanceWeeklyInsight)
        btnPracticeQuiz = findViewById(R.id.btnPracticeQuiz)
        btnReviewFlashcards = findViewById(R.id.btnReviewFlashcards)
        tvMaterialsCount = findViewById(R.id.tvMaterialsCount)
        btnManageMaterials = findViewById(R.id.btnManageMaterials)
        tvProfileRecentActivityEmpty = findViewById(R.id.tvProfileRecentActivityEmpty)
        llProfileRecentActivity = findViewById(R.id.llProfileRecentActivity)

        llConsistencyDots = findViewById(R.id.llConsistencyDots)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { finish() }
        findViewById<MaterialCardView>(R.id.cardAvatar).setOnClickListener { showPhotoActionsDialog() }
        findViewById<View>(R.id.layoutNameEdit).setOnClickListener { showEditNameDialog() }
        findViewById<ImageView>(R.id.ivEditName).setOnClickListener { showEditNameDialog() }
        findViewById<View>(R.id.layoutPerformanceTopicsMastered).setOnClickListener {
            startActivity(Intent(this, TopicsMasteryActivity::class.java))
        }
        tvPerformanceTopicsMastered.setOnClickListener {
            startActivity(Intent(this, TopicsMasteryActivity::class.java))
        }
        tvMaterialsCount.setOnClickListener { startActivity(Intent(this, UploadActivity::class.java)) }
        btnPracticeQuiz.setOnClickListener { startActivity(Intent(this, QuizSetupActivity::class.java)) }
        btnReviewFlashcards.setOnClickListener { startActivity(Intent(this, FlashcardSetupActivity::class.java)) }
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener { showLogoutConfirmationDialog() }
        btnManageMaterials.setOnClickListener { startActivity(Intent(this, UploadActivity::class.java)) }

        if (!authManager.isUserLoggedIn()) {
            logoutUser()
            return
        }

        bindUserProfile()
        bindLearningSnapshot()
        bindPerformance()
        observeMaterialsCount()
        bindConsistencyHeatmap()
        bindRecentActivity()
    }

    override fun onResume() {
        super.onResume()
        bindLearningSnapshot()
        bindPerformance()
        bindConsistencyHeatmap()
        bindRecentActivity()
    }

    override fun onDestroy() {
        super.onDestroy()
        materialsListener?.remove()
    }

    private fun bindUserProfile() {
        val user = authManager.getCurrentUser()
        val cached = UserProfilePrefs.read(this)

        val displayName = user?.displayName.orEmpty().ifBlank {
            cached.name.ifBlank {
                user?.email.orEmpty().substringBefore("@").replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            }
        }.ifBlank { getString(R.string.profile_default_name) }

        val email = user?.email.orEmpty().ifBlank {
            cached.email
        }.ifBlank { getString(R.string.profile_not_available) }

        val phone = user?.phoneNumber.orEmpty().ifBlank {
            cached.phone
        }.ifBlank { getString(R.string.profile_not_available) }

        val photoUri = user?.photoUrl?.toString().orEmpty().ifBlank {
            cached.photoUri
        }

        tvUserName.text = displayName
        tvUserEmail.text = email
        tvUserPhone.text = phone

        currentDisplayName = displayName
        currentEmail = email
        currentPhone = phone
        currentPhotoUri = photoUri

        updateAvatarView(displayName)

        val notAvailable = getString(R.string.profile_not_available)
        UserProfilePrefs.save(
            context = this,
            name = displayName,
            email = if (email == notAvailable) "" else email,
            phone = if (phone == notAvailable) "" else phone,
            photoUri = photoUri
        )
    }

    private fun bindLearningSnapshot() {
        val snapshot = ContinueLearningPrefs.readProfileLearningSnapshot(this)
        val streakDetails = ContinueLearningPrefs.readStudyStreakDetails(this)

        tvLearningStreakValue.text = getString(R.string.profile_learning_streak_value, snapshot.dayStreak)
        tvLearningStudyTimeValue.text = formatStudyTime(snapshot.totalStudyTimeMs)
        tvLearningQuizValue.text = getString(R.string.profile_learning_quizzes_value, snapshot.totalQuizzes)
        tvLearningLastActiveValue.text = resolveLastActiveDate(snapshot.lastActiveTimestamp, streakDetails.lastActiveDaysAgo)

        tvLearningInsight.text = when {
            snapshot.dayStreak == 0 -> getString(R.string.profile_learning_insight_start)
            (streakDetails.lastActiveDaysAgo ?: 0) >= 1 -> getString(R.string.profile_learning_insight_risk)
            else -> getString(R.string.profile_learning_insight_safe)
        }
    }

    private fun bindPerformance() {
        val perf = ContinueLearningPrefs.readQuizPerformance(this, null, null)
        val weeklyPerf = ContinueLearningPrefs.readQuizPerformance(this, 7, null)
        val progress = ContinueLearningPrefs.readStudyProgress(this)
        val hasQuizData = perf.totalQuizzes > 0
        
        tvPerformanceAvgScore.text = if (hasQuizData) {
            getString(R.string.profile_performance_score_format, perf.averageScore)
        } else {
            getString(R.string.profile_performance_score_empty)
        }
        tvPerformanceHighScore.text = if (hasQuizData) {
            getString(R.string.profile_performance_score_format, perf.bestScore)
        } else {
            getString(R.string.profile_performance_score_empty)
        }
        tvPerformanceTopicsMastered.text = progress.totalTopics.toString()
        tvPerformanceScoreHelper.text = getString(R.string.profile_performance_score_helper, perf.totalQuizzes)
        tvPerformanceGuidance.text = if (hasQuizData) {
            getString(R.string.profile_performance_guidance_with_data)
        } else {
            getString(R.string.profile_performance_guidance_empty)
        }
        tvPerformanceWeeklyInsight.text = if (weeklyPerf.totalQuizzes > 0) {
            getString(R.string.profile_performance_weekly_insight_positive, weeklyPerf.totalQuizzes)
        } else {
            getString(R.string.profile_performance_weekly_insight_zero)
        }
    }

    private fun bindRecentActivity() {
        val activities = ContinueLearningPrefs.readRecentActivities(this, limit = 3)
        llProfileRecentActivity.removeAllViews()

        if (activities.isEmpty()) {
            tvProfileRecentActivityEmpty.visibility = View.VISIBLE
            llProfileRecentActivity.visibility = View.GONE
            return
        }

        tvProfileRecentActivityEmpty.visibility = View.GONE
        llProfileRecentActivity.visibility = View.VISIBLE

        activities.forEachIndexed { index, item ->
            llProfileRecentActivity.addView(createRecentActivityRow(item))
            if (index < activities.lastIndex) {
                llProfileRecentActivity.addView(createRecentActivityDivider())
            }
        }
    }

    private fun createRecentActivityRow(item: RecentActivityItem): View {
        val topic = resolveRecentTopic(item)
        val title = TextView(this).apply {
            text = buildRecentTitle(item, topic)
            setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.text_main))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val subtitle = TextView(this).apply {
            text = buildRecentSubtitle(item)
            setTextColor(ContextCompat.getColor(this@ProfileActivity, R.color.text_secondary))
            textSize = 12f
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.space_4), 0, resources.getDimensionPixelSize(R.dimen.space_4))
            addView(title)
            addView(subtitle)
            setOnClickListener {
                when (item.type) {
                    RecentActivityType.QUIZ -> {
                        startActivity(Intent(this@ProfileActivity, QuizSetupActivity::class.java).apply {
                            putExtra(QuizSetupActivity.EXTRA_PREFILL_TOPIC, topic)
                        })
                    }
                    RecentActivityType.FLASHCARD -> {
                        startActivity(Intent(this@ProfileActivity, FlashcardSetupActivity::class.java).apply {
                            putExtra(FlashcardSetupActivity.EXTRA_TOPIC_TEXT, topic)
                        })
                    }
                    RecentActivityType.CHAT -> {
                        startActivity(Intent(this@ProfileActivity, ChatActivity::class.java).apply {
                            if (item.sessionId.isNotBlank()) {
                                putExtra(ChatActivity.EXTRA_SESSION_ID, item.sessionId)
                            }
                        })
                    }
                    RecentActivityType.UPLOAD -> {
                        startActivity(Intent(this@ProfileActivity, UploadActivity::class.java))
                    }
                }
            }
        }
    }

    private fun createRecentActivityDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            }
            setBackgroundColor(ContextCompat.getColor(this@ProfileActivity, R.color.divider))
        }
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

    private fun observeMaterialsCount() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId.isNullOrBlank()) {
            tvMaterialsCount.text = getString(R.string.profile_materials_unavailable)
            return
        }
        val firestore = FirebaseFirestore.getInstance()
        materialsListener?.remove()
        
        materialsListener = firestore.collection("Notes").document(userId).collection("UserNotes")
            .addSnapshotListener { value, error ->
                if (error != null) {
                    tvMaterialsCount.text = getString(R.string.profile_materials_unavailable)
                    return@addSnapshotListener
                }
                val count = value?.size() ?: 0
                tvMaterialsCount.text = resources.getQuantityString(
                    R.plurals.profile_materials_count,
                    count,
                    count
                )
            }
    }

    private fun bindConsistencyHeatmap() {
        val streakDetails = ContinueLearningPrefs.readStudyStreakDetails(this)
        llConsistencyDots.removeAllViews()
        streakDetails.recentSevenDaysActive.forEach { isActive ->
            llConsistencyDots.addView(createStreakDot(isActive))
        }
    }

    private fun createStreakDot(isActive: Boolean): View {
        val size = resources.getDimensionPixelSize(R.dimen.space_16)
        val margin = resources.getDimensionPixelSize(R.dimen.space_8)

        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = margin
            }
            background = ContextCompat.getDrawable(
                this@ProfileActivity,
                if (isActive) R.drawable.bg_streak_dot_active else R.drawable.bg_streak_dot_inactive
            )
        }
    }

    private fun formatStudyTime(totalStudyTimeMs: Long): String {
        val totalMinutes = (totalStudyTimeMs / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L

        return if (hours > 0L) {
            getString(R.string.profile_learning_time_hours_minutes, hours, minutes)
        } else {
            getString(R.string.profile_learning_time_minutes, minutes)
        }
    }

    private fun resolveLastActiveDate(lastActiveTimestamp: Long?, lastActiveDaysAgo: Int?): String {
        val now = System.currentTimeMillis()
        val resolvedTimestamp = if (lastActiveTimestamp != null && lastActiveTimestamp > 0L) {
            lastActiveTimestamp
        } else {
            lastActiveDaysAgo?.let { daysAgo ->
                val safeDaysAgo = max(daysAgo, 0)
                now - (safeDaysAgo * 24L * 60L * 60L * 1000L)
            }
        }

        if (resolvedTimestamp == null || resolvedTimestamp <= 0L) {
            return getString(R.string.profile_learning_last_active_never)
        }

        val daysAgo = lastActiveDaysAgo?.coerceAtLeast(0) ?: ((now - resolvedTimestamp) / (24L * 60L * 60L * 1000L))
            .coerceAtLeast(0L)
            .toInt()

        return when (daysAgo) {
            0 -> getString(R.string.profile_learning_last_active_today)
            1 -> getString(R.string.profile_learning_last_active_yesterday)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(resolvedTimestamp))
        }
    }

    private fun updateAvatarView(displayName: String) {
        tvAvatarInitial.text = displayName.firstOrNull()?.uppercase() ?: getString(R.string.profile_default_initial)

        if (currentPhotoUri.isBlank()) {
            ivAvatarPhoto.visibility = View.GONE
            tvAvatarInitial.visibility = View.VISIBLE
            return
        }

        ivAvatarPhoto.visibility = View.VISIBLE
        tvAvatarInitial.visibility = View.GONE
        ivAvatarPhoto.load(currentPhotoUri) {
            crossfade(true)
            error(R.drawable.graduationcap201)
        }
    }

    private fun persistCachedProfile() {
        val notAvailable = getString(R.string.profile_not_available)
        UserProfilePrefs.save(
            context = this,
            name = currentDisplayName,
            email = if (currentEmail == notAvailable) "" else currentEmail,
            phone = if (currentPhone == notAvailable) "" else currentPhone,
            photoUri = currentPhotoUri
        )
    }

    private fun showPhotoActionsDialog() {
        val dialog = BottomSheetDialog(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_profile_photo, root, false)
        dialog.setContentView(content)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)

        val btnUploadChange = content.findViewById<MaterialButton>(R.id.btnUploadChange)
        val btnRemovePhoto = content.findViewById<MaterialButton>(R.id.btnRemovePhoto)
        val tvCancel = content.findViewById<TextView>(R.id.tvCancel)

        btnUploadChange.text = if (currentPhotoUri.isBlank()) {
            getString(R.string.profile_photo_upload)
        } else {
            getString(R.string.profile_photo_change)
        }

        btnRemovePhoto.visibility = if (currentPhotoUri.isBlank()) View.GONE else View.VISIBLE

        btnUploadChange.setOnClickListener {
            dialog.dismiss()
            photoPicker.launch(arrayOf("image/*"))
        }

        btnRemovePhoto.setOnClickListener {
            revokePersistedPhotoPermission(currentPhotoUri)
            currentPhotoUri = ""
            persistCachedProfile()
            updateAvatarView(currentDisplayName)
            dialog.dismiss()
        }

        tvCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showEditNameDialog() {
        val dialog = BottomSheetDialog(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_edit_name, root, false)
        dialog.setContentView(content)
        (application as? AIStudyAssistanceApp)?.bindSensorUiToBottomSheet(this, dialog)

        val etProfileName = content.findViewById<TextInputEditText>(R.id.etProfileName)
        val btnSaveName = content.findViewById<MaterialButton>(R.id.btnSaveName)
        val tvCancel = content.findViewById<TextView>(R.id.tvCancel)

        etProfileName.setText(currentDisplayName)
        etProfileName.setSelection(etProfileName.text?.length ?: 0)

        btnSaveName.setOnClickListener {
            val updatedName = etProfileName.text?.toString().orEmpty().trim()
            saveUpdatedName(updatedName)
            if (updatedName.isNotBlank()) {
                dialog.dismiss()
            }
        }

        tvCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun saveUpdatedName(updatedName: String) {
        if (updatedName.isBlank()) {
            showToast(getString(R.string.profile_edit_name_empty_error))
            return
        }

        authManager.updateUserDisplayName(updatedName) { success, message ->
            runOnUiThread {
                if (!success) {
                    showToast(message ?: getString(R.string.profile_edit_name_failed))
                    return@runOnUiThread
                }

                currentDisplayName = updatedName
                tvUserName.text = updatedName
                persistCachedProfile()
                updateAvatarView(updatedName)
                showToast(getString(R.string.profile_edit_name_success))
            }
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun logoutUser() {
        val cachedPhotoUri = UserProfilePrefs.read(this).photoUri
        revokePersistedPhotoPermission(currentPhotoUri)
        if (cachedPhotoUri != currentPhotoUri) {
            revokePersistedPhotoPermission(cachedPhotoUri)
        }

        authManager.signOut()
        authManager.clearCache()
        ContinueLearningPrefs.clearFlashcardProgress(this)
        ContinueLearningPrefs.clearQuizProgress(this)
        UserProfilePrefs.clear(this)

        startActivity(
            Intent(this, SignInActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_logout_confirm_title)
            .setMessage(R.string.profile_logout_confirm_message)
            .setPositiveButton(R.string.profile_logout_confirm_action) { _, _ ->
                logoutUser()
            }
            .setNegativeButton(R.string.profile_logout_cancel_action, null)
            .show()
    }

    private fun revokePersistedPhotoPermission(uriString: String) {
        if (uriString.isBlank()) return
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }
}
