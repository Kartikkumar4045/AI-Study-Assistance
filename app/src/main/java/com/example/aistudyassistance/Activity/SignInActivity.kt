package com.example.aistudyassistance.Activity

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class SignInActivity : AppCompatActivity() {

    // Firebase Auth instance
    private lateinit var auth: FirebaseAuth

    // UI elements
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignIn: MaterialButton
    private lateinit var ivTogglePassword: ImageView
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvSignUp: TextView

    // Password visibility flag
    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_in)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize views
        initViews()

        // Set up click listeners
        setupClickListeners()
    }

    private fun initViews() {
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnSignIn = findViewById(R.id.btnSignIn)
        ivTogglePassword = findViewById(R.id.ivTogglePassword)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvSignUp = findViewById(R.id.tvSignUp)
    }

    private fun setupClickListeners() {

        // ──────────────────────────────────────────────
        // Sign In Button Click
        // ──────────────────────────────────────────────
        btnSignIn.setOnClickListener {
            val emailOrPhone = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (!validateInputs(emailOrPhone, password)) return@setOnClickListener

            btnSignIn.isEnabled = false
            btnSignIn.text = "Signing In..."

            if (Patterns.EMAIL_ADDRESS.matcher(emailOrPhone).matches()) {
                signInWithEmail(emailOrPhone, password)
            } else if (Patterns.PHONE.matcher(emailOrPhone).matches()) {
                signInWithEmail(emailOrPhone, password)
            } else {
                signInWithEmail(emailOrPhone, password)
            }
        }

        // ──────────────────────────────────────────────
        // Toggle Password Visibility  ✅ UPDATED
        // ──────────────────────────────────────────────
        ivTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible

            if (isPasswordVisible) {
                // Password is NOW VISIBLE → show "eye open" icon
                etPassword.transformationMethod =
                    HideReturnsTransformationMethod.getInstance()
                ivTogglePassword.setImageResource(R.drawable.visibilityon45)

            } else {
                // Password is NOW HIDDEN → show "eye closed" icon
                etPassword.transformationMethod =
                    PasswordTransformationMethod.getInstance()
                ivTogglePassword.setImageResource(R.drawable.visisbilityoff45)
            }

            // Keep cursor at the end
            etPassword.setSelection(etPassword.text.length)
        }

        // ──────────────────────────────────────────────
        // Forgot Password Click
        // ──────────────────────────────────────────────
        tvForgotPassword.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                etEmail.error = "Enter your email first to reset password"
                etEmail.requestFocus()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etEmail.error = "Enter a valid email to reset password"
                etEmail.requestFocus()
                return@setOnClickListener
            }

            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            "Password reset email sent. Check your inbox.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Failed: ${task.exception?.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }

        // ──────────────────────────────────────────────
        // Sign Up Click
        // ──────────────────────────────────────────────
        tvSignUp.setOnClickListener {
            Toast.makeText(this, "Navigate to Sign Up", Toast.LENGTH_SHORT).show()
        }
    }

    private fun signInWithEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Toast.makeText(
                        this,
                        "Login Successfully! Welcome ${user?.email}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val errorMessage = task.exception?.localizedMessage
                        ?: "Authentication failed"
                    Toast.makeText(
                        this,
                        "Login Failed: $errorMessage",
                        Toast.LENGTH_LONG
                    ).show()
                }
                resetButton()
            }
    }

    private fun validateInputs(emailOrPhone: String, password: String): Boolean {
        if (emailOrPhone.isEmpty()) {
            etEmail.error = "Email or Phone is required"
            etEmail.requestFocus()
            return false
        }
        if (password.isEmpty()) {
            etPassword.error = "Password is required"
            etPassword.requestFocus()
            return false
        }
        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"
            etPassword.requestFocus()
            return false
        }
        return true
    }

    private fun isValidPhone(input: String): Boolean {
        return Patterns.PHONE.matcher(input).matches() && input.length >= 10
    }

    private fun resetButton() {
        btnSignIn.isEnabled = true
        btnSignIn.text = "Sign In"
    }
}