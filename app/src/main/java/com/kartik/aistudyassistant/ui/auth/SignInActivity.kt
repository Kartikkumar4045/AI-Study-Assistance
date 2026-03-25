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
import com.google.firebase.database.FirebaseDatabase

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
    private var lastLoginInput: String = ""
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
                    authManager.signOut()
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
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
            val emailOrPhone = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (!validateInputs(emailOrPhone, password)) return@setOnClickListener

            lastLoginInput = emailOrPhone

            btnSignIn.isEnabled = false
            btnSignIn.text = "Signing In..."

            if (isValidPhone(emailOrPhone)) {
                // If it's a phone number, find email first
                findEmailByPhone(emailOrPhone, password)
            } else {
                // Direct email sign in
                authManager.signInWithEmail(emailOrPhone, password) { result ->
                    handleSignInResult(result)
                }
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

    private fun findEmailByPhone(phone: String, password: String) {
        val usersRef = FirebaseDatabase.getInstance().reference.child("Users")
        usersRef.orderByChild("phone").equalTo(phone)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val userData = snapshot.children.first()
                    val email = userData.child("email").value.toString()
                    authManager.signInWithEmail(email, password) { result ->
                        handleSignInResult(result)
                    }
                } else {
                    Toast.makeText(this, "No account found with this phone number", Toast.LENGTH_LONG).show()
                    resetButton()
                }
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_LONG).show()
                resetButton()
            }
    }

    private fun handleSignInResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                UserProfilePrefs.cacheFromUser(
                    context = this,
                    user = auth.currentUser,
                    fallbackPhone = lastLoginInput.takeIf { isValidPhone(it) }.orEmpty()
                )
                Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT).show()
                navigateToMain()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, result.message ?: "Sign in failed", Toast.LENGTH_LONG).show()
                resetButton()
            }
            is AuthResult.VerificationRequired -> {
                val shouldResendEmail = !result.emailVerified
                if (shouldResendEmail) {
                    authManager.sendEmailVerificationForCurrentUser { resendResult ->
                        if (resendResult is AuthResult.Error) {
                            Toast.makeText(
                                this,
                                resendResult.message ?: "Failed to send verification email",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                authManager.signOut()
                resetButton()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                resetButton()
            }
        }
    }

    private fun validateInputs(emailOrPhone: String, password: String): Boolean {
        if (emailOrPhone.isEmpty()) {
            etEmail.error = "Email or Phone is required"
            etEmail.requestFocus()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(emailOrPhone).matches() && !isValidPhone(emailOrPhone)) {
            etEmail.error = "Enter valid email or 10-digit phone number"
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

    private fun isValidPhone(input: String): Boolean {
        // India phone number validation: exactly 10 digits
        return input.length == 10 && input.all { it.isDigit() }
    }

    private fun showForgotPasswordBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_forgot_password, null)
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
            btnSendReset.text = "Sending..."

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
                        btnSendReset.text = "Send Reset Link"
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
        btnSignIn.text = "Please wait..."
    }

    private fun resetButton() {
        btnSignIn.isEnabled = true
        btnSignIn.text = "Sign In"
        btnGoogle.isEnabled = true
        btnGithub.isEnabled = true
    }
}

