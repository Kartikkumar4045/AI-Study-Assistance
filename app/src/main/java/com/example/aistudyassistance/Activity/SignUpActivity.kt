package com.example.aistudyassistance.Activity

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Patterns
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.aistudyassistance.Authentication.AuthManager
import com.example.aistudyassistance.Authentication.AuthResult
import com.example.aistudyassistance.MainActivity
import com.example.aistudyassistance.utils.EmailUtils

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var authManager: AuthManager

    // Views
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var cvGoogle: CardView
    private lateinit var cvGithub: CardView

    private var isPasswordVisible1 = false
    private var isPasswordVisible2 = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
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
        cvGoogle = findViewById(R.id.cvGoogle)
        cvGithub = findViewById(R.id.cvGithub)

        val tvSignIn = findViewById<TextView>(R.id.tvSignIn)
        val toggle1 = findViewById<ImageView>(R.id.ivTogglePassword1)
        val toggle2 = findViewById<ImageView>(R.id.ivTogglePassword2)

        tvSignIn.setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
        }

        // Password toggles
        toggle1.setOnClickListener {
            isPasswordVisible1 = !isPasswordVisible1
            if (isPasswordVisible1) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggle1.setImageResource(R.drawable.visibilityon45)
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle1.setImageResource(R.drawable.visisbilityoff45)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        toggle2.setOnClickListener {
            isPasswordVisible2 = !isPasswordVisible2
            if (isPasswordVisible2) {
                etConfirmPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggle2.setImageResource(R.drawable.visibilityon45)
            } else {
                etConfirmPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle2.setImageResource(R.drawable.visisbilityoff45)
            }
            etConfirmPassword.setSelection(etConfirmPassword.text.length)
        }
    }

    private fun setupClickListeners() {
        btnSignUp.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performEmailSignUp()
        }

        cvGoogle.setOnClickListener {
            disableSocialButtons()
            authManager.signInWithGoogle { result ->
                handleSocialSignUpResult(result)
            }
        }

        cvGithub.setOnClickListener {
            disableSocialButtons()
            authManager.signInWithGithub(this) { result ->
                handleSocialSignUpResult(result)
            }
        }
    }

    private fun performEmailSignUp() {
        val email = etEmail.text.toString().trim().lowercase()
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString().trim()
        val confirmPassword = etConfirmPassword.text.toString().trim()

        // Validation
        if (!validateInputs(email, phone, password, confirmPassword)) {
            return
        }

        // Disable button
        btnSignUp.isEnabled = false
        btnSignUp.alpha = 0.5f

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val userId = auth.currentUser?.uid
                    if (userId != null) {
                        saveUserToDatabase(userId, email, phone)
                    } else {
                        Toast.makeText(this, "Error creating user", Toast.LENGTH_LONG).show()
                        enableSignUpButton()
                    }
                } else {
                    val error = task.exception?.message ?: "Sign up failed"
                    Toast.makeText(this, "Sign Up Failed: $error", Toast.LENGTH_LONG).show()
                    enableSignUpButton()
                }
            }
    }

    private fun saveUserToDatabase(userId: String, email: String, phone: String) {
        val userMap = HashMap<String, Any>().apply {
            put("email", email)
            put("phone", phone)
            put("provider", "password")
        }

        database.reference.child("Users").child(userId)
            .setValue(userMap)
            .addOnSuccessListener {
                val encodedEmail = EmailUtils.encodeEmail(email)
                database.reference.child("Emails").child(encodedEmail)
                    .setValue(userId)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Account Created Successfully", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SignInActivity::class.java))
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Database Error: ${e.message}", Toast.LENGTH_LONG).show()
                        enableSignUpButton()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Database Error: ${e.message}", Toast.LENGTH_LONG).show()
                enableSignUpButton()
            }
    }

    private fun validateInputs(email: String, phone: String, password: String, confirmPassword: String): Boolean {
        if (email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Enter valid email"
            return false
        }

        if (phone.length != 10 || !phone.all { it.isDigit() }) {
            etPhone.error = "Enter valid 10 digit phone number"
            return false
        }

        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"
            return false
        }

        if (password != confirmPassword) {
            etConfirmPassword.error = "Passwords do not match"
            return false
        }

        return true
    }

    private fun handleSocialSignUpResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                Toast.makeText(this, "Welcome!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, "Sign Up Failed: ${result.message}", Toast.LENGTH_LONG).show()
                enableSocialButtons()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Sign Up Cancelled", Toast.LENGTH_SHORT).show()
                enableSocialButtons()
            }
        }
    }

    private fun disableSocialButtons() {
        cvGoogle.isEnabled = false
        cvGithub.isEnabled = false
        cvGoogle.alpha = 0.5f
        cvGithub.alpha = 0.5f
    }

    private fun enableSocialButtons() {
        cvGoogle.isEnabled = true
        cvGithub.isEnabled = true
        cvGoogle.alpha = 1f
        cvGithub.alpha = 1f
    }

    private fun enableSignUpButton() {
        btnSignUp.isEnabled = true
        btnSignUp.alpha = 1f
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