package com.example.aistudyassistance.ui.auth

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Patterns
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import android.widget.TextView
import com.example.aistudyassistance.data.repository.AuthManager
import com.example.aistudyassistance.data.model.AuthResult
import com.example.aistudyassistance.ui.home.MainActivity

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager

    // Views
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var btnGoogle: MaterialButton
    private lateinit var btnGithub: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()
        authManager = AuthManager(this)

        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        etEmail = findViewById(R.id.etEmail)
        etPhone = findViewById(R.id.etPhone)
        etPassword = findViewById(R.id.etCreatePassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnSignUp = findViewById(R.id.btnSignUp)
        btnGoogle = findViewById(R.id.btnGoogle)
        btnGithub = findViewById(R.id.btnGithub)

        val tvSignIn = findViewById<TextView>(R.id.tvSignIn)

        tvSignIn.setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
        }
    }

    private fun setupClickListeners() {
        btnSignUp.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val email = etEmail.text.toString().trim().lowercase()
            val phone = etPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (!validateInputs(email, phone, password, confirmPassword)) {
                return@setOnClickListener
            }

            btnSignUp.isEnabled = false
            btnSignUp.text = "Creating Account..."

            authManager.signUpWithEmail(email, password, phone) { result ->
                handleSignUpResult(result)
            }
        }

        btnGoogle.setOnClickListener {
            disableSocialButtons()
            authManager.signInWithGoogle { result ->
                handleSignUpResult(result)
            }
        }

        btnGithub.setOnClickListener {
            disableSocialButtons()
            authManager.signInWithGithub(this) { result ->
                handleSignUpResult(result)
            }
        }
    }

    private fun handleSignUpResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                val message = if (result.isNewUser) {
                    "Account created successfully!"
                } else {
                    "Welcome back!"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, result.message ?: "Sign up failed", Toast.LENGTH_LONG).show()
                enableAllButtons()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Sign up cancelled", Toast.LENGTH_SHORT).show()
                enableAllButtons()
            }
        }
    }

    private fun validateInputs(email: String, phone: String, password: String, confirmPassword: String): Boolean {
        if (email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Enter valid email"
            etEmail.requestFocus()
            return false
        }

        // India phone number validation: exactly 10 digits
        if (phone.length != 10 || !phone.all { it.isDigit() }) {
            etPhone.error = "Enter valid 10-digit Indian phone number"
            etPhone.requestFocus()
            return false
        }

        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"
            etPassword.requestFocus()
            return false
        }

        if (password != confirmPassword) {
            etConfirmPassword.error = "Passwords do not match"
            etConfirmPassword.requestFocus()
            return false
        }

        return true
    }

    private fun disableSocialButtons() {
        btnSignUp.isEnabled = false
        btnGoogle.isEnabled = false
        btnGithub.isEnabled = false
        btnGoogle.alpha = 0.5f
        btnGithub.alpha = 0.5f
    }

    private fun enableAllButtons() {
        btnSignUp.isEnabled = true
        btnSignUp.text = "Sign Up"
        btnGoogle.isEnabled = true
        btnGithub.isEnabled = true
        btnGoogle.alpha = 1f
        btnGithub.alpha = 1f
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities != null &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}

