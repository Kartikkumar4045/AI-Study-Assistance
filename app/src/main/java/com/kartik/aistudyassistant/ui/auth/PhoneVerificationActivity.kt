package com.kartik.aistudyassistant.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.kartik.aistudyassistant.data.model.AuthResult
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.ui.home.MainActivity

class PhoneVerificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_MODE = "extra_mode"
        const val MODE_LINK = "mode_link"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager
    private lateinit var tvSubtitle: TextView
    private lateinit var etOtp: EditText
    private lateinit var btnVerify: MaterialButton
    private lateinit var btnResend: MaterialButton
    private lateinit var btnBackToSignIn: MaterialButton

    private var phone: String = ""
    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_phone_verification)

        auth = FirebaseAuth.getInstance()
        authManager = AuthManager(this)

        phone = intent.getStringExtra(EXTRA_PHONE)?.trim().orEmpty()
        if (phone.isEmpty()) {
            Toast.makeText(this, "Phone number missing", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        startPhoneOtpFlow()
    }

    private fun initViews() {
        tvSubtitle = findViewById(R.id.tvOtpSubtitle)
        etOtp = findViewById(R.id.etOtp)
        btnVerify = findViewById(R.id.btnVerifyOtp)
        btnResend = findViewById(R.id.btnResendOtp)
        btnBackToSignIn = findViewById(R.id.btnBackToSignIn)

        val displayPhone = if (phone.startsWith("+")) phone else "+91 $phone"
        tvSubtitle.text = "Enter the 6-digit code sent to $displayPhone"
        btnResend.isEnabled = false
    }

    private fun setupListeners() {
        btnVerify.setOnClickListener {
            val code = etOtp.text?.toString()?.trim().orEmpty()
            if (code.length != 6 || !code.all { it.isDigit() }) {
                etOtp.error = "Enter valid 6-digit OTP"
                return@setOnClickListener
            }

            val currentVerificationId = verificationId
            if (currentVerificationId.isNullOrEmpty()) {
                Toast.makeText(this, "Request OTP first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true, "Verifying...")
            val verifyCallback: (AuthResult) -> Unit = { result ->
                setLoading(false, "Verify OTP")
                handleVerificationResult(result)
            }

            authManager.verifyPhoneCode(currentVerificationId, code, verifyCallback)
        }

        btnResend.setOnClickListener {
            val token = resendToken
            if (token == null) {
                startPhoneOtpFlow()
                return@setOnClickListener
            }

            setLoading(true, "Resending...")
            authManager.resendPhoneVerification(
                activity = this,
                rawPhone = phone,
                token = token,
                onCodeSent = { newVerificationId, newToken ->
                    setLoading(false, "Verify OTP")
                    verificationId = newVerificationId
                    resendToken = newToken
                    Toast.makeText(this, "OTP resent", Toast.LENGTH_SHORT).show()
                },
                onVerified = { result ->
                    setLoading(false, "Verify OTP")
                    handleVerificationResult(result)
                },
                onError = { message ->
                    setLoading(false, "Verify OTP")
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            )
        }

        btnBackToSignIn.setOnClickListener {
            authManager.signOut()
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
        }
    }

    private fun startPhoneOtpFlow() {
        setLoading(true, "Sending OTP...")
        authManager.startPhoneVerification(
            activity = this,
            rawPhone = phone,
            onCodeSent = { sentVerificationId, token ->
                setLoading(false, "Verify OTP")
                verificationId = sentVerificationId
                resendToken = token
                btnResend.isEnabled = true
                Toast.makeText(this, "OTP sent", Toast.LENGTH_SHORT).show()
            },
            onVerified = { result ->
                setLoading(false, "Verify OTP")
                handleVerificationResult(result)
            },
            onError = { message ->
                setLoading(false, "Verify OTP")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun handleVerificationResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                UserProfilePrefs.cacheFromUser(this, auth.currentUser, fallbackPhone = phone)
                Toast.makeText(this, "Phone verified successfully", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                finish()
            }
            is AuthResult.VerificationRequired -> {
                if (result.phoneVerified && !result.emailVerified) {
                    Toast.makeText(this, "Phone verified. Verify email, then sign in again.", Toast.LENGTH_LONG).show()
                    authManager.signOut()
                    startActivity(Intent(this, SignInActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
            is AuthResult.Error -> {
                Toast.makeText(this, result.message ?: "Verification failed", Toast.LENGTH_LONG).show()
            }
            AuthResult.Cancelled -> {
                Toast.makeText(this, "Verification cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLoading(loading: Boolean, buttonText: String) {
        btnVerify.isEnabled = !loading
        btnResend.isEnabled = !loading && verificationId != null
        btnBackToSignIn.isEnabled = !loading
        btnVerify.text = buttonText
    }
}



