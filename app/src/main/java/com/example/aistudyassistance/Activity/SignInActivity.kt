package com.example.aistudyassistance.Activity

import android.content.Intent
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
import com.example.aistudyassistance.Authentication.AuthManager
import com.example.aistudyassistance.R
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.example.aistudyassistance.Authentication.AuthResult
import com.example.aistudyassistance.MainActivity
import com.google.firebase.auth.OAuthProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.database.FirebaseDatabase

class SignInActivity : AppCompatActivity() {

    // Firebase Auth instance
    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager
    private lateinit var btnGoogle: MaterialButton
    private lateinit var btnGoogleCircle: MaterialButton
    private lateinit var btnGithub: MaterialButton
    private lateinit var btnGithubCircle: MaterialButton

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
        authManager = AuthManager(this)

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
        btnGoogle = findViewById(R.id.btnGoogle)
        btnGoogleCircle = findViewById(R.id.btnGoogleCircle)
        btnGithub = findViewById(R.id.btnGithub)
        btnGithubCircle = findViewById(R.id.btnGithubCircle)
    }

    private fun setupClickListeners() {


        // Sign In Button Click

        btnSignIn.setOnClickListener {
            val emailOrPhone = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (!validateInputs(emailOrPhone, password)) return@setOnClickListener

            btnSignIn.isEnabled = false
            btnSignIn.text = "Signing In..."

            signInWithEmail(emailOrPhone, password)
        }


        // Google Sign In (Modern Credential Manager)

        btnGoogle.setOnClickListener {
            handleGoogleLogin()
        }
        btnGoogleCircle.setOnClickListener {
            handleGoogleLogin()
        }

        //Github Sign In
        btnGithub.setOnClickListener { handleGithubLogin() }
        btnGithubCircle.setOnClickListener { handleGithubLogin() }


        // Toggle Password Visibility  ✅ UPDATED
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


        // Forgot Password Click

        tvForgotPassword.setOnClickListener {
            showForgotPasswordBottomSheet()
        }

        //Sign Up TEXT VIEW click
        tvSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
            finish()
        }
    }


    private fun signInWithEmail(emailOrPhone: String, password: String) {
        if (isValidPhone(emailOrPhone)) {
            btnSignIn.text = "Searching account..."
            val usersRef = FirebaseDatabase.getInstance().reference.child("Users")
            usersRef.orderByChild("phone").equalTo(emailOrPhone)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.exists()) {
                        btnSignIn.text = "Signing In..."
                        val userData = snapshot.children.first()
                        val email = userData.child("email").value.toString()
                        performEmailSignIn(email, password)
                    } else {
                        Toast.makeText(this, "No account found with this phone", Toast.LENGTH_LONG).show()
                        resetButton()
                    }
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Error: ${exception.message}", Toast.LENGTH_LONG).show()
                    resetButton()
                }
                .addOnCompleteListener {
                    // Ensure button is re-enabled even if something goes wrong
                    if (!btnSignIn.isEnabled) {
                        resetButton()
                    }
                }

        } else {
            performEmailSignIn(emailOrPhone, password)
        }
    }

    private fun performEmailSignIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    val errorMessage = task.exception?.localizedMessage ?: "Authentication failed"
                    Toast.makeText(this, "Login Failed: $errorMessage", Toast.LENGTH_LONG).show()
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

        // Add proper validation
        if (!Patterns.EMAIL_ADDRESS.matcher(emailOrPhone).matches() &&
            !isValidPhone(emailOrPhone)) {
            etEmail.error = "Enter valid email or 10-digit phone number"
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
        return Patterns.PHONE.matcher(input).matches() && input.length == 10
    }


    private fun showForgotPasswordBottomSheet() {

        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(
            R.layout.bottom_sheet_forgot_password,
            null
        )

        bottomSheetDialog.setContentView(view)

        val etForgotEmail = view.findViewById<EditText>(R.id.etForgotEmail)
        val btnSendReset = view.findViewById<MaterialButton>(R.id.btnSendReset)
        val tvCancel = view.findViewById<TextView>(R.id.tvCancel)

        // Prefill email if typed
        val currentEmail = etEmail.text.toString().trim()
        if (currentEmail.isNotEmpty()) {
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
            btnSendReset.text = "Checking..."

            val usersRef = FirebaseDatabase.getInstance()
                .reference
                .child("Users")

            // 🔥 Query directly by email (efficient way)
            usersRef
                .orderByChild("email")
                .equalTo(email)
                .get()
                .addOnSuccessListener { snapshot ->

                    if (!snapshot.exists()) {

                        Toast.makeText(
                            this,
                            "No account found with this email.",
                            Toast.LENGTH_LONG
                        ).show()

                        resetButtonState(btnSendReset)
                        return@addOnSuccessListener
                    }

                    val userSnapshot = snapshot.children.first()
                    val providerType =
                        userSnapshot.child("provider").value?.toString() ?: "password"

                    when (providerType) {

                        "password" -> {

                            FirebaseAuth.getInstance()
                                .sendPasswordResetEmail(email)
                                .addOnCompleteListener {

                                    Toast.makeText(
                                        this,
                                        "Password reset link sent to your email.",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    bottomSheetDialog.dismiss()
                                }
                        }

                        "google" -> {
                            Toast.makeText(
                                this,
                                "This account was created using Google Sign-In. Please login with Google.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        "github" -> {
                            Toast.makeText(
                                this,
                                "This account was created using GitHub Sign-In. Please login with GitHub.",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        else -> {
                            Toast.makeText(
                                this,
                                "Please login using your original sign-in method.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    resetButtonState(btnSendReset)
                }
                .addOnFailureListener {

                    Toast.makeText(
                        this,
                        "Something went wrong. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()

                    resetButtonState(btnSendReset)
                }
        }

        bottomSheetDialog.behavior.isDraggable = true
        bottomSheetDialog.setCancelable(true)
        bottomSheetDialog.show()
    }

    private fun encodeEmail(email: String): String {
        return email.lowercase()
            .replace(".", "_")
            .replace("@", "_")
    }

    private fun handleGoogleLogin() {
        disableSocialButtons()
        authManager.signInWithGoogle { result ->
            handleSocialLoginResult(result)
        }
    }

    private fun handleGithubLogin() {
        disableSocialButtons()
        authManager.signInWithGithub(this) { result ->
            handleSocialLoginResult(result)
        }
    }


    private fun handleSocialLoginResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                Toast.makeText(this, "Welcome!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, "Login Failed: ${result.message}", Toast.LENGTH_LONG).show()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Login Cancelled", Toast.LENGTH_SHORT).show()
            }
        }
        resetButton()
    }

    private fun disableSocialButtons() {
        btnGoogle.isEnabled = false
        btnGoogleCircle.isEnabled = false
        btnGithub.isEnabled = false
        btnGithubCircle.isEnabled = false
        btnSignIn.text = "Please wait..."
    }

    private fun resetButton() {
        btnSignIn.isEnabled = true
        btnSignIn.text = "Sign In"
        btnGoogle.isEnabled = true
        btnGoogleCircle.isEnabled = true
        btnGithub.isEnabled = true
        btnGithubCircle.isEnabled = true
    }

    private fun resetButtonState(button: MaterialButton) {
        button.isEnabled = true
        button.text = "Send Reset Link"
    }
}