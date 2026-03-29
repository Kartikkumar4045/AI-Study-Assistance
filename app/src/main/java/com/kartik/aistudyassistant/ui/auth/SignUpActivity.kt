package com.kartik.aistudyassistant.ui.auth

import android.content.Intent
import android.os.CountDownTimer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kartik.aistudyassistant.R
import com.kartik.aistudyassistant.data.local.UserProfilePrefs
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import com.kartik.aistudyassistant.data.repository.AuthManager
import com.kartik.aistudyassistant.data.model.AuthResult
import com.kartik.aistudyassistant.ui.home.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SignUpActivity : AppCompatActivity() {

    companion object {
        private const val PHONE_OTP_BLOCK_COOLDOWN_MILLIS = 10 * 60 * 1000L
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var authManager: AuthManager

    // Views
    private lateinit var etEmail: EditText
    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var etPhoneOtp: EditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var btnGoogle: MaterialButton
    private lateinit var btnGithub: MaterialButton
    private lateinit var btnSendEmailOtp: MaterialButton
    private lateinit var btnCheckEmailVerified: MaterialButton
    private lateinit var btnSendPhoneOtp: MaterialButton
    private lateinit var btnResendPhoneOtp: MaterialButton
    private lateinit var btnVerifyPhoneOtp: MaterialButton
    private var btnEditVerifiedContacts: MaterialButton? = null
    private lateinit var tvEmailVerificationStatus: TextView
    private lateinit var tvPhoneVerificationStatus: TextView
    private var tvVerifiedContactsLockedHint: TextView? = null
    private var tvPhoneOtpBlockedCountdown: TextView? = null

    private var emailVerified = false
    private var phoneVerified = false
    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var lastVerifiedEmail: String? = null
    private var lastOtpTargetPhone: String? = null
    private var phoneOtpBlockTimer: CountDownTimer? = null

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
        etPhoneOtp = findViewById(R.id.etPhoneOtp)
        btnSignUp = findViewById(R.id.btnSignUp)
        btnGoogle = findViewById(R.id.btnGoogle)
        btnGithub = findViewById(R.id.btnGithub)
        btnSendEmailOtp = findViewById(R.id.btnSendEmailOtp)
        btnCheckEmailVerified = findViewById(R.id.btnCheckEmailVerified)
        btnSendPhoneOtp = findViewById(R.id.btnSendPhoneOtp)
        btnResendPhoneOtp = findViewById(R.id.btnResendPhoneOtp)
        btnVerifyPhoneOtp = findViewById(R.id.btnVerifyPhoneOtp)
        btnEditVerifiedContacts = findViewById(R.id.btnEditVerifiedContacts)
        tvEmailVerificationStatus = findViewById(R.id.tvEmailVerificationStatus)
        tvPhoneVerificationStatus = findViewById(R.id.tvPhoneVerificationStatus)
        tvVerifiedContactsLockedHint = findViewById(R.id.tvVerifiedContactsLockedHint)
        tvPhoneOtpBlockedCountdown = findViewById(R.id.tvPhoneOtpBlockedCountdown)

        updateVerificationUi()

        val tvSignIn = findViewById<TextView>(R.id.tvSignIn)

        tvSignIn.setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
            finish()
        }

        etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val normalizedCurrent = s?.toString()?.trim().orEmpty()
                val wasBoundToOtp = !lastOtpTargetPhone.isNullOrBlank()
                if (wasBoundToOtp && normalizedCurrent != lastOtpTargetPhone) {
                    resetPhoneVerificationState()
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        etEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val currentEmail = s?.toString()?.trim()?.lowercase().orEmpty()
                val verifiedEmail = lastVerifiedEmail
                if (!verifiedEmail.isNullOrBlank() && currentEmail != verifiedEmail) {
                    resetEmailVerificationState()
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun setupClickListeners() {
        btnSignUp.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val phone = etPhone.text.toString().trim()
            val email = etEmail.text.toString().trim().lowercase()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (emailVerified && lastVerifiedEmail != null && email != lastVerifiedEmail) {
                resetEmailVerificationState()
                Toast.makeText(this, "Email changed. Verify email again before creating account", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (phoneVerified && lastOtpTargetPhone != null && phone != lastOtpTargetPhone) {
                resetPhoneVerificationState()
                Toast.makeText(this, "Phone changed. Verify phone again before creating account", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!emailVerified || !phoneVerified) {
                Toast.makeText(this, "Verify email and phone before creating account", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!validatePasswordInputs(password, confirmPassword)) {
                return@setOnClickListener
            }

            btnSignUp.isEnabled = false
            btnSignUp.text = getString(R.string.signup_creating_account)

            authManager.completeVerifiedEmailSignUp(phone, password) { result ->
                handleSignUpResult(result)
            }
        }

        btnSendEmailOtp.setOnClickListener {
            val email = etEmail.text.toString().trim().lowercase()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                etEmail.error = "Enter valid email"
                etEmail.requestFocus()
                return@setOnClickListener
            }

            resetEmailVerificationState()

            btnSendEmailOtp.isEnabled = false
            btnSendEmailOtp.text = getString(R.string.common_sending)
            authManager.startPendingEmailVerification(email) { result ->
                btnSendEmailOtp.isEnabled = true
                btnSendEmailOtp.text = getString(R.string.signup_send_verification_email)
                when (result) {
                    is AuthResult.Success -> {
                        lastVerifiedEmail = email
                        setEmailStatus(false, "Verification email sent. Check inbox and tap Check Verified.", false)
                        Toast.makeText(this, "Verification email sent", Toast.LENGTH_SHORT).show()
                    }
                    is AuthResult.Error -> {
                        Toast.makeText(this, result.message ?: "Failed to send email verification", Toast.LENGTH_LONG).show()
                    }
                    else -> Unit
                }
            }
        }

        btnCheckEmailVerified.setOnClickListener {
            btnCheckEmailVerified.isEnabled = false
            btnCheckEmailVerified.text = getString(R.string.signup_checking)
            authManager.refreshEmailVerificationStatus { verified, error ->
                btnCheckEmailVerified.isEnabled = true
                btnCheckEmailVerified.text = getString(R.string.signup_check_verified)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@refreshEmailVerificationStatus
                }

                emailVerified = verified
                if (verified) {
                    lastVerifiedEmail = etEmail.text.toString().trim().lowercase()
                }
                setEmailStatus(
                    verified,
                    if (verified) "Email verified" else "Email not verified",
                    verified
                )
                updateVerificationUi()
            }
        }

        btnSendPhoneOtp.setOnClickListener {
            if (!emailVerified) {
                Toast.makeText(this, "Verify email first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val phone = etPhone.text.toString().trim()
            if (!isValidIndianPhone(phone)) {
                etPhone.error = "Enter valid 10-digit Indian phone number"
                etPhone.requestFocus()
                return@setOnClickListener
            }

            sendPhoneOtp(phone)
        }

        btnResendPhoneOtp.setOnClickListener {
            val token = resendToken
            val phone = etPhone.text.toString().trim()
            if (token == null) {
                sendPhoneOtp(phone)
                return@setOnClickListener
            }

            btnResendPhoneOtp.isEnabled = false
            btnResendPhoneOtp.text = getString(R.string.signup_resending)
            authManager.resendPhoneVerification(
                activity = this,
                rawPhone = phone,
                token = token,
                onCodeSent = { newVerificationId, newToken ->
                    verificationId = newVerificationId
                    resendToken = newToken
                    clearPhoneOtpBlockCounter()
                    btnResendPhoneOtp.isEnabled = true
                    btnResendPhoneOtp.text = getString(R.string.signup_resend_otp)
                    Toast.makeText(this, getString(R.string.signup_otp_resent), Toast.LENGTH_SHORT).show()
                },
                onVerified = { result ->
                    btnResendPhoneOtp.isEnabled = true
                    btnResendPhoneOtp.text = getString(R.string.signup_resend_otp)
                    handlePhoneVerificationResult(result)
                },
                onError = { message ->
                    handlePhoneOtpBlockedErrorIfNeeded(message)
                    btnResendPhoneOtp.isEnabled = true
                    btnResendPhoneOtp.text = getString(R.string.signup_resend_otp)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            )
        }

        btnVerifyPhoneOtp.setOnClickListener {
            val currentVerificationId = verificationId
            if (currentVerificationId.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.signup_send_otp_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val otp = etPhoneOtp.text.toString().trim()
            if (otp.length != 6 || !otp.all { it.isDigit() }) {
                etPhoneOtp.error = "Enter valid 6-digit OTP"
                etPhoneOtp.requestFocus()
                return@setOnClickListener
            }

            btnVerifyPhoneOtp.isEnabled = false
            btnVerifyPhoneOtp.text = getString(R.string.signup_verifying)
            authManager.verifyPhoneCode(currentVerificationId, otp) { result ->
                btnVerifyPhoneOtp.isEnabled = true
                btnVerifyPhoneOtp.text = getString(R.string.signup_verify_phone_otp)
                handlePhoneVerificationResult(result)
            }
        }

        btnGoogle.setOnClickListener {
            disableSocialButtons()
            authManager.signInWithGoogle { result ->
                handleSignUpResult(result)
            }
        }

        btnEditVerifiedContacts?.setOnClickListener {
            resetEmailVerificationState()
            Toast.makeText(this, "Email and phone unlocked. Verify again to continue", Toast.LENGTH_SHORT).show()
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
                UserProfilePrefs.cacheFromUser(
                    context = this,
                    user = auth.currentUser,
                    fallbackPhone = etPhone.text?.toString()?.trim().orEmpty()
                )
                val message = if (result.isNewUser) {
                    "Account created successfully!"
                } else {
                    "Welcome back!"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            is AuthResult.VerificationRequired -> {
                showVerificationRequiredDialog(result)
                enableAllButtons()
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

    private fun validatePasswordInputs(password: String, confirmPassword: String): Boolean {
        if (password.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Password fields are required", Toast.LENGTH_SHORT).show()
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
        btnSignUp.text = getString(R.string.signup_create_account)
        btnGoogle.isEnabled = true
        btnGithub.isEnabled = true
        btnGoogle.alpha = 1f
        btnGithub.alpha = 1f
        updateVerificationUi()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        return networkCapabilities != null &&
                (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun sendPhoneOtp(phone: String) {
        btnSendPhoneOtp.isEnabled = false
        btnSendPhoneOtp.text = getString(R.string.common_sending)
        authManager.startPhoneVerification(
            activity = this,
            rawPhone = phone,
            onCodeSent = { sentVerificationId, token ->
                verificationId = sentVerificationId
                resendToken = token
                lastOtpTargetPhone = phone.trim()
                clearPhoneOtpBlockCounter()
                btnSendPhoneOtp.isEnabled = true
                btnSendPhoneOtp.text = getString(R.string.signup_send_phone_otp)
                btnResendPhoneOtp.isEnabled = true
                setPhoneStatus(false, getString(R.string.signup_phone_otp_sent_status), false)
            },
            onVerified = { result ->
                btnSendPhoneOtp.isEnabled = true
                btnSendPhoneOtp.text = getString(R.string.signup_send_phone_otp)
                handlePhoneVerificationResult(result)
            },
            onError = { message ->
                handlePhoneOtpBlockedErrorIfNeeded(message)
                btnSendPhoneOtp.isEnabled = true
                btnSendPhoneOtp.text = getString(R.string.signup_send_phone_otp)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun handlePhoneVerificationResult(result: AuthResult) {
        when (result) {
            is AuthResult.Success -> {
                clearPhoneOtpBlockCounter()
                phoneVerified = true
                lastOtpTargetPhone = etPhone.text?.toString()?.trim().orEmpty()
                setPhoneStatus(true, getString(R.string.signup_phone_verified), true)
                updateVerificationUi()
            }
            is AuthResult.VerificationRequired -> {
                if (result.phoneVerified) {
                    clearPhoneOtpBlockCounter()
                }
                phoneVerified = result.phoneVerified
                if (result.phoneVerified) {
                    lastOtpTargetPhone = etPhone.text?.toString()?.trim().orEmpty()
                }
                setPhoneStatus(
                    result.phoneVerified,
                    if (result.phoneVerified) getString(R.string.signup_phone_verified) else getString(R.string.signup_phone_not_verified),
                    result.phoneVerified
                )
                updateVerificationUi()
            }
            is AuthResult.Error -> {
                Toast.makeText(this, result.message ?: getString(R.string.signup_phone_verification_failed), Toast.LENGTH_LONG).show()
                setPhoneStatus(false, getString(R.string.signup_phone_not_verified), false)
            }
            AuthResult.Cancelled -> Unit
        }
    }

    private fun updateVerificationUi() {
        val isFullyVerified = emailVerified && phoneVerified

        etEmail.isEnabled = !isFullyVerified
        etPhone.isEnabled = !isFullyVerified

        btnSendEmailOtp.isEnabled = !isFullyVerified
        btnCheckEmailVerified.isEnabled = !isFullyVerified
        btnSendPhoneOtp.isEnabled = emailVerified && !isFullyVerified
        btnResendPhoneOtp.isEnabled = resendToken != null && !phoneVerified && !isFullyVerified
        btnVerifyPhoneOtp.isEnabled = !phoneVerified && !isFullyVerified
        btnEditVerifiedContacts?.visibility = if (isFullyVerified) View.VISIBLE else View.GONE
        tvVerifiedContactsLockedHint?.visibility = if (isFullyVerified) View.VISIBLE else View.GONE
        btnSignUp.isEnabled = isFullyVerified
    }

    private fun resetEmailVerificationState() {
        emailVerified = false
        lastVerifiedEmail = null
        setEmailStatus(false, getString(R.string.signup_email_not_verified), false)
        resetPhoneVerificationState()
    }

    private fun resetPhoneVerificationState() {
        phoneVerified = false
        verificationId = null
        resendToken = null
        lastOtpTargetPhone = null
        etPhoneOtp.text?.clear()
        clearPhoneOtpBlockCounter()
        setPhoneStatus(false, getString(R.string.signup_phone_not_verified), false)
        updateVerificationUi()
    }

    private fun handlePhoneOtpBlockedErrorIfNeeded(message: String) {
        if (isOtpBlockedError(message)) {
            startPhoneOtpBlockCounter()
        }
    }

    private fun isOtpBlockedError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("blocked") && normalized.contains("unusual activity")
    }

    private fun startPhoneOtpBlockCounter() {
        phoneOtpBlockTimer?.cancel()
        tvPhoneOtpBlockedCountdown?.visibility = View.VISIBLE

        phoneOtpBlockTimer = object : CountDownTimer(PHONE_OTP_BLOCK_COOLDOWN_MILLIS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                tvPhoneOtpBlockedCountdown?.text =
                    getString(R.string.signup_phone_otp_blocked_countdown, minutes, seconds)
            }

            override fun onFinish() {
                tvPhoneOtpBlockedCountdown?.visibility = View.GONE
                tvPhoneOtpBlockedCountdown?.text = ""
                phoneOtpBlockTimer = null
            }
        }.start()
    }

    private fun clearPhoneOtpBlockCounter() {
        phoneOtpBlockTimer?.cancel()
        phoneOtpBlockTimer = null
        tvPhoneOtpBlockedCountdown?.visibility = View.GONE
        tvPhoneOtpBlockedCountdown?.text = ""
    }

    override fun onDestroy() {
        clearPhoneOtpBlockCounter()
        super.onDestroy()
    }

    private fun setEmailStatus(verified: Boolean, text: String, successTone: Boolean) {
        tvEmailVerificationStatus.text = text
        val color = if (verified || successTone) R.color.accent_green else R.color.red
        tvEmailVerificationStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun setPhoneStatus(verified: Boolean, text: String, successTone: Boolean) {
        tvPhoneVerificationStatus.text = text
        val color = if (verified || successTone) R.color.accent_green else R.color.red
        tvPhoneVerificationStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun isValidIndianPhone(phone: String): Boolean {
        return phone.length == 10 && phone.all { it.isDigit() }
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
            .setTitle("Verify your account")
            .setMessage(details)
            .setPositiveButton(
                if (!result.phoneVerified) "Verify phone" else "Go to Sign In"
            ) { _, _ ->
                if (!result.phoneVerified) {
                    val phone = result.phone ?: etPhone.text?.toString()?.trim().orEmpty()
                    if (phone.isBlank()) {
                        Toast.makeText(this, "Phone number is required for OTP verification", Toast.LENGTH_LONG).show()
                    } else {
                        openPhoneVerification(phone)
                    }
                } else {
                    startActivity(Intent(this, SignInActivity::class.java))
                    finish()
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

