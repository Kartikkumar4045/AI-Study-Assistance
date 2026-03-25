package com.kartik.aistudyassistant.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.kartik.aistudyassistant.data.model.AuthResult
import com.kartik.aistudyassistant.ui.home.MainActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SignInActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager
    private lateinit var btnGoogle: MaterialButton
    private lateinit var btnGithub: MaterialButton
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignIn: MaterialButton
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvSignUp: TextView
    private var isCheckingExistingSession: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_in)

        auth = FirebaseAuth.getInstance()
        authManager = AuthManager(this)

        initViews()
        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        checkExistingSession()
    }

    private fun checkExistingSession() {
        if (auth.currentUser == null || isCheckingExistingSession) {
            return
        }

        isCheckingExistingSession = true
        authManager.checkCurrentUserVerification { result ->
            isCheckingExistingSession = false
            when (result) {
                is AuthResult.Success -> navigateToMain()
                is AuthResult.VerificationRequired -> {
                    showVerificationRequiredDialog(result)
                }
                is AuthResult.Error -> {
                    authManager.signOut()
                    Toast.makeText(this, result.message ?: "Please sign in", Toast.LENGTH_SHORT).show()
                }
                AuthResult.Cancelled -> Unit
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun initViews() {
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnSignIn = findViewById(R.id.btnSignIn)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvSignUp = findViewById(R.id.tvSignUp)
        btnGoogle = findViewById(R.id.btnGoogle)
        btnGithub = findViewById(R.id.btnGithub)
    }

    private fun setupClickListeners() {
        btnSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim().lowercase()
            val password = etPassword.text.toString().trim()

            if (!validateInputs(email, password)) return@setOnClickListener

            btnSignIn.isEnabled = false
            btnSignIn.text = getString(R.string.signin_signing_in)

            authManager.signInWithEmail(email, password) { result ->
                handleSignInResult(result)
            }
        }

        btnGoogle.setOnClickListener { handleGoogleLogin() }
        btnGithub.setOnClickListener { handleGithubLogin() }

        tvForgotPassword.setOnClickListener {
            showForgotPasswordBottomSheet()
        }

        tvSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
            finish()
        }
    }

    private fun handleSignInResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                UserProfilePrefs.cacheFromUser(
                    context = this,
                    user = auth.currentUser,
                    fallbackPhone = ""
                )
                Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT).show()
                navigateToMain()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, result.message ?: "Sign in failed", Toast.LENGTH_LONG).show()
                resetButton()
            }
            is AuthResult.VerificationRequired -> {
                showVerificationRequiredDialog(result)
                resetButton()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                resetButton()
            }
        }
    }

    private fun validateInputs(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            etEmail.error = "Email is required"
            etEmail.requestFocus()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Enter valid email"
            etEmail.requestFocus()
            return false
        }

        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            etPassword.requestFocus()
            return false
        }

        return true
    }

    private fun showForgotPasswordBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_forgot_password, root, false)
        bottomSheetDialog.setContentView(view)

        val etForgotEmail = view.findViewById<EditText>(R.id.etForgotEmail)
        val btnSendReset = view.findViewById<MaterialButton>(R.id.btnSendReset)
        val tvCancel = view.findViewById<TextView>(R.id.tvCancel)

        val currentEmail = etEmail.text.toString().trim()
        if (currentEmail.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(currentEmail).matches()) {
            etForgotEmail.setText(currentEmail)
            etForgotEmail.setSelection(currentEmail.length)
        }

        tvCancel.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        btnSendReset.setOnClickListener {
            val email = etForgotEmail.text.toString().trim().lowercase()

            if (email.isEmpty()) {
                etForgotEmail.error = "Email is required"
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etForgotEmail.error = "Enter valid email"
                return@setOnClickListener
            }

            btnSendReset.isEnabled = false
            btnSendReset.text = getString(R.string.common_sending)

            // Problem #4 Fix: Directly send reset email without deprecated check
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            "If an account exists with this email, a reset link has been sent.",
                            Toast.LENGTH_LONG
                        ).show()
                        bottomSheetDialog.dismiss()
                    } else {
                        val error = task.exception?.message ?: "Failed to send reset email"
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        btnSendReset.isEnabled = true
                        btnSendReset.text = getString(R.string.signin_send_reset_link)
                    }
                }
        }

        bottomSheetDialog.show()
    }

    private fun handleGoogleLogin() {
        disableButtons()
        authManager.signInWithGoogle { result ->
            handleSignInResult(result)
        }
    }

    private fun handleGithubLogin() {
        disableButtons()
        authManager.signInWithGithub(this) { result ->
            handleSignInResult(result)
        }
    }

    private fun disableButtons() {
        btnSignIn.isEnabled = false
        btnGoogle.isEnabled = false
        btnGithub.isEnabled = false
        btnSignIn.text = getString(R.string.signin_please_wait)
    }

    private fun resetButton() {
        btnSignIn.isEnabled = true
        btnSignIn.text = getString(R.string.signin_sign_in)
        btnGoogle.isEnabled = true
        btnGithub.isEnabled = true
    }

    private fun showVerificationRequiredDialog(result: AuthResult.VerificationRequired) {
        val details = buildString {
            append(result.message)
            append("\n\n")
            append("Email: ")
            append(if (result.emailVerified) "Verified" else "Not verified")
            append("\n")
            append("Phone: ")
            append(if (result.phoneVerified) "Verified" else "Not verified")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Verification required")
            .setMessage(details)
            .setPositiveButton(
                if (!result.phoneVerified) "Verify phone" else "OK"
            ) { _, _ ->
                if (!result.phoneVerified) {
                    val phone = result.phone
                    if (phone.isNullOrBlank()) {
                        Toast.makeText(this, "Phone number not found. Update it in sign up.", Toast.LENGTH_LONG).show()
                    } else {
                        openPhoneVerification(phone)
                    }
                }
            }
            .setNegativeButton("Sign out") { _, _ ->
                authManager.signOut()
            }

        if (!result.emailVerified && auth.currentUser != null) {
            dialog.setNeutralButton("Resend email") { _, _ ->
                authManager.sendEmailVerificationForCurrentUser { resendResult ->
                    when (resendResult) {
                        is AuthResult.Success -> Toast.makeText(
                            this,
                            "Verification email sent",
                            Toast.LENGTH_SHORT
                        ).show()
                        is AuthResult.Error -> Toast.makeText(
                            this,
                            resendResult.message ?: "Failed to send verification email",
                            Toast.LENGTH_LONG
                        ).show()
                        else -> Unit
                    }
                }
            }
        }

        dialog.show()
    }

    private fun openPhoneVerification(phone: String) {
        val intent = Intent(this, PhoneVerificationActivity::class.java)
            .putExtra(PhoneVerificationActivity.EXTRA_PHONE, phone)
            .putExtra(PhoneVerificationActivity.EXTRA_MODE, PhoneVerificationActivity.MODE_LINK)
        startActivity(intent)
    }
}

