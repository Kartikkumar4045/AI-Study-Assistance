package com.example.aistudyassistance.Activity

import android.content.Intent
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

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private var isPasswordVisible1 = false
    private var isPasswordVisible2 = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPhone = findViewById<EditText>(R.id.etPhone)
        val etPassword = findViewById<EditText>(R.id.etCreatePassword)
        val etConfirmPassword = findViewById<EditText>(R.id.etConfirmPassword)
        val btnSignUp = findViewById<MaterialButton>(R.id.btnSignUp)

        val toggle1 = findViewById<ImageView>(R.id.ivTogglePassword1)
        val toggle2 = findViewById<ImageView>(R.id.ivTogglePassword2)

        // 👁 Toggle Password 1 - FIXED (using TransformationMethod instead of inputType)
        toggle1.setOnClickListener {
            isPasswordVisible1 = !isPasswordVisible1

            if (isPasswordVisible1) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggle1.setImageResource(R.drawable.visibilityon45)
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle1.setImageResource(R.drawable.visisbilityoff45)
            }

            // Keep cursor at end
            etPassword.setSelection(etPassword.text.length)
        }

        // 👁 Toggle Password 2 - FIXED (using TransformationMethod instead of inputType)
        toggle2.setOnClickListener {
            isPasswordVisible2 = !isPasswordVisible2

            if (isPasswordVisible2) {
                etConfirmPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggle2.setImageResource(R.drawable.visibilityon45)
            } else {
                etConfirmPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle2.setImageResource(R.drawable.visisbilityoff45)
            }

            // Keep cursor at end
            etConfirmPassword.setSelection(etConfirmPassword.text.length)
        }

        // 🔥 Sign Up Logic - ENHANCED
        btnSignUp.setOnClickListener {

            val email = etEmail.text.toString().trim()
            val phone = etPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            // Validation
            if (email.isEmpty() || phone.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etEmail.error = "Enter valid email"
                return@setOnClickListener
            }

            if (phone.length != 10 || !phone.all { it.isDigit() }) {
                etPhone.error = "Enter valid 10 digit phone number"
                return@setOnClickListener
            }

            if (password.length < 6) {
                etPassword.error = "Password must be at least 6 characters"
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                etConfirmPassword.error = "Passwords do not match"
                return@setOnClickListener
            }

            // Disable button to prevent multiple clicks
            btnSignUp.isEnabled = false
            btnSignUp.alpha = 0.5f

            // Firebase Sign Up
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->

                    if (task.isSuccessful) {

                        val userId = auth.currentUser?.uid

                        if (userId != null) {
                            val userMap = HashMap<String, Any>()
                            userMap["email"] = email
                            userMap["phone"] = phone

                            database.reference
                                .child("Users")
                                .child(userId)
                                .setValue(userMap)
                                .addOnSuccessListener {
                                    Toast.makeText(
                                        this,
                                        "Account Created Successfully",
                                        Toast.LENGTH_LONG
                                    ).show()

                                    // Navigate to next screen (e.g., Login or Home)
                                    // startActivity(Intent(this, LoginActivity::class.java))
                                    // finish()
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(
                                        this,
                                        "Database Error: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    btnSignUp.isEnabled = true
                                    btnSignUp.alpha = 1f
                                }
                        } else {
                            Toast.makeText(
                                this,
                                "User ID is null",
                                Toast.LENGTH_LONG
                            ).show()
                            btnSignUp.isEnabled = true
                            btnSignUp.alpha = 1f
                        }

                    } else {
                        Toast.makeText(
                            this,
                            "Sign Up Failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        btnSignUp.isEnabled = true
                        btnSignUp.alpha = 1f
                    }
                }
        }
    }
}