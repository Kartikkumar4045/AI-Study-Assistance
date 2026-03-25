package com.kartik.aistudyassistant.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.ui.auth.SignInActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
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

    private lateinit var authManager: AuthManager
    private var currentDisplayName: String = ""
    private var currentEmail: String = ""
    private var currentPhone: String = ""
    private var currentPhotoUri: String = ""

    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not grant persistable permission; use the URI while it remains valid.
        }

        currentPhotoUri = uri.toString()
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

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { finish() }
        findViewById<MaterialCardView>(R.id.cardAvatar).setOnClickListener { showPhotoActionsDialog() }
        findViewById<View>(R.id.layoutNameEdit).setOnClickListener { showEditNameDialog() }
        findViewById<ImageView>(R.id.ivEditName).setOnClickListener { showEditNameDialog() }
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener { showLogoutConfirmationDialog() }

        if (!authManager.isUserLoggedIn()) {
            logoutUser()
            return
        }

        bindUserProfile()
        bindLearningSnapshot()
    }

    override fun onResume() {
        super.onResume()
        bindLearningSnapshot()
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
}



