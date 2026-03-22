package com.kartik.aistudyassistant.ui.profile

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.ContinueLearningPrefs
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.ui.auth.SignInActivity
import com.google.android.material.button.MaterialButton

class ProfileActivity : AppCompatActivity() {

    private lateinit var tvAvatarInitial: TextView
    private lateinit var tvUserName: TextView
    private lateinit var tvUserEmail: TextView
    private lateinit var tvUserPhone: TextView

    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_profile)

        authManager = AuthManager(this)

        tvAvatarInitial = findViewById(R.id.tvAvatarInitial)
        tvUserName = findViewById(R.id.tvUserName)
        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvUserPhone = findViewById(R.id.tvUserPhone)

        findViewById<ImageView>(R.id.ivBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener { showLogoutConfirmationDialog() }

        if (!authManager.isUserLoggedIn()) {
            logoutUser()
            return
        }

        bindUserProfile()
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

        tvUserName.text = displayName
        tvUserEmail.text = email
        tvUserPhone.text = phone
        tvAvatarInitial.text = displayName.firstOrNull()?.uppercase() ?: getString(R.string.profile_default_initial)

        val notAvailable = getString(R.string.profile_not_available)
        UserProfilePrefs.save(
            context = this,
            name = displayName,
            email = if (email == notAvailable) "" else email,
            phone = if (phone == notAvailable) "" else phone
        )
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



