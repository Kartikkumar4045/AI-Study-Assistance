package com.kartik.aistudyassistant.data.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import com.kartik.aistudyassistant.data.model.AuthResult as AppAuthResult
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.UUID

class AuthManager(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)
    private val database = FirebaseDatabase.getInstance()
    private val tag = "AuthManager"

    companion object {
        // Cache for user existence checks
        private val emailUserCache = mutableMapOf<String, CachedUserData>()
        private const val CACHE_DURATION = 5 * 60 * 1000 // 5 minutes
        private const val PHONE_LENGTH = 10
        private const val OTP_TIMEOUT_SECONDS = 60L
        private const val TEMP_PASSWORD_PREFIX = "Tmp#"

        data class CachedUserData(
            val uid: String,
            val provider: String,
            val timestamp: Long
        ) {
            fun isValid(): Boolean = System.currentTimeMillis() - timestamp < CACHE_DURATION
        }

    }

    fun checkCurrentUserVerification(callback: (AppAuthResult) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(AppAuthResult.Error("User not logged in"))
            return
        }

        evaluateVerificationState(currentUser, false, callback)
    }

    fun sendEmailVerificationForCurrentUser(callback: (AppAuthResult) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(AppAuthResult.Error("User not logged in"))
            return
        }

        currentUser.sendEmailVerification()
            .addOnSuccessListener {
                callback(AppAuthResult.Success(false))
            }
            .addOnFailureListener { e ->
                callback(AppAuthResult.Error(e.message ?: "Failed to send verification email"))
            }
    }

    fun startPendingEmailVerification(email: String, callback: (AppAuthResult) -> Unit) {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            callback(AppAuthResult.Error("Email is required"))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingUserData = checkEmailExists(normalizedEmail)
                if (existingUserData != null) {
                    withContext(Dispatchers.Main) {
                        callback(
                            AppAuthResult.Error(
                                "An account already exists with this email using ${existingUserData.provider}."
                            )
                        )
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val currentUser = auth.currentUser
                    if (currentUser != null && currentUser.email.equals(normalizedEmail, ignoreCase = true)) {
                        sendEmailVerificationForCurrentUser(callback)
                    } else {
                        auth.signOut()
                        val tempPassword = buildTempPassword()
                        auth.createUserWithEmailAndPassword(normalizedEmail, tempPassword)
                            .addOnSuccessListener {
                                auth.currentUser?.sendEmailVerification()
                                    ?.addOnSuccessListener { callback(AppAuthResult.Success(true)) }
                                    ?.addOnFailureListener { e ->
                                        callback(
                                            AppAuthResult.Error(
                                                e.message ?: "Failed to send verification email"
                                            )
                                        )
                                    }
                            }
                            .addOnFailureListener { e ->
                                callback(AppAuthResult.Error(e.message ?: "Unable to start email verification"))
                            }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(AppAuthResult.Error(e.message ?: "Unable to start email verification"))
                }
            }
        }
    }

    fun refreshEmailVerificationStatus(callback: (Boolean, String?) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(false, "Please verify email first")
            return
        }

        currentUser.reload()
            .addOnSuccessListener {
                callback(currentUser.isEmailVerified, null)
            }
            .addOnFailureListener { e ->
                callback(false, e.message ?: "Failed to refresh email verification")
            }
    }

    fun completeVerifiedEmailSignUp(
        phone: String,
        password: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(AppAuthResult.Error("Please verify email first"))
            return
        }

        if (!currentUser.isEmailVerified) {
            callback(AppAuthResult.Error("Email is not verified yet"))
            return
        }

        val verifiedPhone = currentUser.phoneNumber
        val resolvedPhone = if (!verifiedPhone.isNullOrBlank()) {
            toLocalPhone(verifiedPhone)
        } else {
            phone.trim()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val phoneExists = checkPhoneExists(resolvedPhone, currentUser.uid)
            if (phoneExists) {
                withContext(Dispatchers.Main) {
                    callback(AppAuthResult.Error("This phone number is already registered with another account."))
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                currentUser.updatePassword(password)
                    .addOnSuccessListener {
                        val email = currentUser.email.orEmpty()
                        saveUserToDatabase(
                            uid = currentUser.uid,
                            email = email,
                            phone = resolvedPhone,
                            provider = "password",
                            isNewUser = true,
                            callback = { dbResult ->
                                when (dbResult) {
                                    is AppAuthResult.Success -> evaluateVerificationState(currentUser, true, callback)
                                    else -> callback(dbResult)
                                }
                            }
                        )
                    }
                    .addOnFailureListener { e ->
                        callback(AppAuthResult.Error(e.message ?: "Failed to finalize account setup"))
                    }
            }
        }
    }

    fun startPhoneVerification(
        activity: Activity,
        rawPhone: String,
        onCodeSent: (verificationId: String, token: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onVerified: (AppAuthResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val phoneE164 = normalizePhoneToE164(rawPhone)
        if (phoneE164 == null) {
            onError("Enter a valid phone number")
            return
        }

        val callbacks = buildPhoneCallbacks(phoneE164, onCodeSent, onVerified, onError)
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun resendPhoneVerification(
        activity: Activity,
        rawPhone: String,
        token: PhoneAuthProvider.ForceResendingToken,
        onCodeSent: (verificationId: String, resendToken: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onVerified: (AppAuthResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val phoneE164 = normalizePhoneToE164(rawPhone)
        if (phoneE164 == null) {
            onError("Enter a valid phone number")
            return
        }

        val callbacks = buildPhoneCallbacks(phoneE164, onCodeSent, onVerified, onError)
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneE164)
            .setTimeout(OTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .setForceResendingToken(token)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    fun verifyPhoneCode(
        verificationId: String,
        code: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        linkPhoneCredential(credential, callback)
    }

    fun getCurrentUserStoredPhone(callback: (String?) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(null)
            return
        }

        database.reference.child("Users").child(currentUser.uid).child("phone")
            .get()
            .addOnSuccessListener { snapshot ->
                callback(snapshot.getValue(String::class.java))
            }
            .addOnFailureListener {
                callback(null)
            }
    }

    // Google Sign-In
    fun signInWithGoogle(callback: (AppAuthResult) -> Unit) {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(
                context.getString(com.kartik.aistudyassistant.R.string.default_web_client_id)
            )
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)

                    handleProviderSignIn(
                        idToken = googleIdTokenCredential.idToken,
                        email = googleIdTokenCredential.id,
                        callback = callback
                    )
                } else {
                    callback(AppAuthResult.Error("Invalid credential type"))
                }
            } catch (e: GetCredentialException) {
                if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                    callback(AppAuthResult.Cancelled)
                } else {
                    callback(AppAuthResult.Error(e.message))
                }
            }
        }
    }

    // GitHub Sign-In
    fun signInWithGithub(activity: Activity, callback: (AppAuthResult) -> Unit) {
        val provider = OAuthProvider.newBuilder("github.com")
            .addCustomParameter("allow_signup", "true")
            .build()

        auth.startActivityForSignInWithProvider(activity, provider)
            .addOnSuccessListener { authResult ->
                val user = authResult.user
                user?.let {
                    val email = it.email?.trim()?.lowercase().orEmpty()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val existingUserData = if (email.isBlank()) null else checkEmailExists(email)

                            withContext(Dispatchers.Main) {
                                if (existingUserData != null && existingUserData.uid != it.uid) {
                                    callback(AppAuthResult.Error(
                                        "This email is already registered with ${existingUserData.provider}. " +
                                                "Please sign in with ${existingUserData.provider} first."
                                    ))
                                } else {
                                    saveUserToDatabase(
                                        uid = it.uid,
                                        email = email,
                                        phone = "",
                                        provider = "github.com",
                                        isNewUser = authResult.additionalUserInfo?.isNewUser ?: false,
                                        callback = { dbResult ->
                                            when (dbResult) {
                                                is AppAuthResult.Success -> {
                                                    evaluateVerificationState(
                                                        it,
                                                        authResult.additionalUserInfo?.isNewUser ?: false,
                                                        callback
                                                    )
                                                }
                                                else -> callback(dbResult)
                                            }
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                callback(AppAuthResult.Error(e.message))
                            }
                        }
                    }
                } ?: callback(AppAuthResult.Error("Failed to get user info"))
            }
            .addOnFailureListener { e ->
                if (e.message?.contains("cancelled", ignoreCase = true) == true) {
                    callback(AppAuthResult.Cancelled)
                } else {
                    callback(AppAuthResult.Error(e.message ?: "GitHub sign-in failed"))
                }
            }
    }

    // Email/Password Sign Up
    fun signUpWithEmail(email: String, password: String, phone: String, callback: (AppAuthResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if email exists in our database first
                val existingUserData = checkEmailExists(email)
                if (existingUserData != null) {
                    withContext(Dispatchers.Main) {
                        callback(AppAuthResult.Error(
                            "An account already exists with this email using ${existingUserData.provider}."
                        ))
                    }
                    return@launch
                }

                // Check if phone exists (Problem #5 fix)
                val phoneExists = checkPhoneExists(phone)
                if (phoneExists) {
                    withContext(Dispatchers.Main) {
                        callback(AppAuthResult.Error("This phone number is already registered with another account."))
                    }
                    return@launch
                }

                // Create new account with password
                withContext(Dispatchers.Main) {
                    createEmailAccount(email, password, phone, callback)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(AppAuthResult.Error(e.message))
                }
            }
        }
    }

    // Email/Password Sign In
    fun signInWithEmail(email: String, password: String, callback: (AppAuthResult) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let {
                        updateUserPhoneIfNeeded(it.uid, email) { updateResult ->
                            when (updateResult) {
                                is AppAuthResult.Error -> callback(updateResult)
                                else -> evaluateVerificationState(it, false, callback)
                            }
                        }
                    } ?: callback(AppAuthResult.Error("User not found"))
                } else {
                    val exception = task.exception
                    when {
                        exception is FirebaseAuthInvalidUserException -> {
                            callback(AppAuthResult.Error("No account found with this email"))
                        }
                        exception is FirebaseAuthInvalidCredentialsException -> {
                            callback(AppAuthResult.Error("Invalid password"))
                        }
                        else -> {
                            callback(AppAuthResult.Error(exception?.message ?: "Sign in failed"))
                        }
                    }
                }
            }
    }

    // Handle provider sign-in (Google)
    private fun handleProviderSignIn(
        idToken: String,
        email: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val provider = "google.com"
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            callback(AppAuthResult.Error("Unable to read Google account email"))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingUserData = checkEmailExists(normalizedEmail)

                withContext(Dispatchers.Main) {
                    if (existingUserData != null) {
                        linkProviderToExistingAccount(
                            idToken = idToken,
                            provider = provider,
                            existingUid = existingUserData.uid,
                            callback = callback
                        )
                    } else {
                        createProviderAccount(
                            idToken = idToken,
                            provider = provider,
                            email = normalizedEmail,
                            callback = callback
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(AppAuthResult.Error(e.message))
                }
            }
        }
    }

    private fun linkProviderToExistingAccount(
        idToken: String,
        provider: String,
        existingUid: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val credential = when (provider) {
            "google.com" -> GoogleAuthProvider.getCredential(idToken, null)
            else -> null
        }

        if (credential == null) {
            callback(AppAuthResult.Error("Invalid provider"))
            return
        }

        // Try to sign in with the new credential
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Successfully signed in with new provider
                    val user = auth.currentUser
                    if (user?.uid == existingUid) {
                        // Same account, already linked
                        updateUserData(user.uid, provider, callback)
                    } else {
                        // Different account, need to link
                        linkAccounts(user, callback)
                    }
                } else {
                    val exception = task.exception
                    when {
                        exception is FirebaseAuthUserCollisionException -> {
                            // Email already used with another provider
                            handleAccountCollision(exception, provider, callback)
                        }
                        else -> {
                            callback(AppAuthResult.Error(exception?.message ?: "Authentication failed"))
                        }
                    }
                }
            }
    }

    // Handle account collision (email exists with different provider)
    private fun handleAccountCollision(
        exception: FirebaseAuthUserCollisionException,
        newProvider: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val email = exception.email ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existingUserData = checkEmailExists(email)

                withContext(Dispatchers.Main) {
                    if (existingUserData != null) {
                        callback(AppAuthResult.Error(
                            "This email is already registered with ${existingUserData.provider}. " +
                                    "Please sign in with ${existingUserData.provider} first, then you can link your $newProvider account."
                        ))
                    } else {
                        callback(AppAuthResult.Error("Email already in use with another provider"))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(AppAuthResult.Error(e.message))
                }
            }
        }
    }

    private fun linkAccounts(
        newUser: FirebaseUser?,
        callback: (AppAuthResult) -> Unit
    ) {
        if (newUser == null) {
            callback(AppAuthResult.Error("Failed to link accounts"))
            return
        }

        // Sign in with existing account first
        // This is complex - in a real app, you'd prompt user to sign in with original provider
        // For now, we'll delete the new account and ask user to sign in with original provider
        newUser.delete()
            .addOnCompleteListener {
                callback(AppAuthResult.Error(
                    "Please sign in with your original provider first, then link this provider."
                ))
            }
    }

    // Create account with provider (Google)
    private fun createProviderAccount(
        idToken: String,
        provider: String,
        email: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val credential = when (provider) {
            "google.com" -> GoogleAuthProvider.getCredential(idToken, null)
            else -> null
        }

        credential?.let {
            auth.signInWithCredential(it)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        user?.let {
                            saveUserToDatabase(
                                uid = it.uid,
                                email = email,
                                phone = "",
                                provider = provider,
                                isNewUser = true,
                                callback = { dbResult ->
                                    when (dbResult) {
                                        is AppAuthResult.Success -> evaluateVerificationState(it, true, callback)
                                        else -> callback(dbResult)
                                    }
                                }
                            )
                        } ?: callback(AppAuthResult.Error("Failed to create account"))
                    } else {
                        callback(AppAuthResult.Error(task.exception?.message ?: "Authentication failed"))
                    }
                }
        }
    }

    // Create email/password account
    private fun createEmailAccount(
        email: String,
        password: String,
        phone: String,
        callback: (AppAuthResult) -> Unit
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let { firebaseUser ->
                        firebaseUser.sendEmailVerification()
                            .addOnSuccessListener {
                                saveUserToDatabase(
                                    uid = firebaseUser.uid,
                                    email = email,
                                    phone = phone,
                                    provider = "password",
                                    isNewUser = true,
                                    callback = { dbResult ->
                                        when (dbResult) {
                                            is AppAuthResult.Success -> {
                                                callback(
                                                    AppAuthResult.VerificationRequired(
                                                        emailVerified = false,
                                                        phoneVerified = false,
                                                        message = "Verify your email and phone to continue.",
                                                        phone = phone
                                                    )
                                                )
                                            }
                                            else -> callback(dbResult)
                                        }
                                    }
                                )
                            }
                            .addOnFailureListener { e ->
                                callback(AppAuthResult.Error(e.message ?: "Failed to send verification email"))
                            }
                    } ?: callback(AppAuthResult.Error("Failed to create account"))
                } else {
                    val exception = task.exception
                    when {
                        exception is FirebaseAuthUserCollisionException -> {
                            callback(AppAuthResult.Error("Email already registered. Please sign in instead."))
                        }
                        else -> {
                            callback(AppAuthResult.Error(exception?.message ?: "Sign up failed"))
                        }
                    }
                }
            }
    }

    // Save user to database with caching
    private fun saveUserToDatabase(
        uid: String,
        email: String,
        phone: String,
        provider: String,
        isNewUser: Boolean,
        callback: (AppAuthResult) -> Unit
    ) {
        val normalizedEmail = email.trim().lowercase()
        val emailVerified = auth.currentUser?.isEmailVerified == true
        val phoneE164 = normalizePhoneToE164(phone).orEmpty()
        val phoneVerified = !auth.currentUser?.phoneNumber.isNullOrBlank()
        val userMap = HashMap<String, Any>().apply {
            put("email", normalizedEmail)
            put("phone", phone)
            put("phoneE164", phoneE164)
            put("provider", provider)
            put("createdAt", System.currentTimeMillis())
            put("lastLogin", System.currentTimeMillis())
            put("emailVerified", emailVerified)
            put("phoneVerified", phoneVerified)
            put("verificationUpdatedAt", System.currentTimeMillis())
        }

        database.reference.child("Users").child(uid)
            .setValue(userMap)
            .addOnSuccessListener {
                if (normalizedEmail.isBlank()) {
                    callback(AppAuthResult.Success(isNewUser))
                    return@addOnSuccessListener
                }

                val encodedEmail = encodeEmail(normalizedEmail)
                database.reference.child("Emails").child(encodedEmail)
                    .setValue(uid)
                    .addOnSuccessListener {
                        emailUserCache[normalizedEmail] = CachedUserData(
                            uid = uid,
                            provider = provider,
                            timestamp = System.currentTimeMillis()
                        )

                        callback(AppAuthResult.Success(isNewUser))
                    }
                    .addOnFailureListener { e ->
                        Log.e(tag, "Failed to save email mapping", e)
                        callback(AppAuthResult.Success(isNewUser))
                    }
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Failed to save user", e)
                callback(AppAuthResult.Error("Failed to save user data: ${e.message}"))
            }
    }

    // Update existing user data
    private fun updateUserData(
        uid: String,
        provider: String,
        callback: (AppAuthResult) -> Unit
    ) {
        val updates = mapOf<String, Any>(
            "lastLogin" to System.currentTimeMillis(),
            "provider" to provider, // Update last used provider
            "emailVerified" to (auth.currentUser?.isEmailVerified == true),
            "verificationUpdatedAt" to System.currentTimeMillis()
        )

        database.reference.child("Users").child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                val currentUser = auth.currentUser
                if (currentUser != null) {
                    evaluateVerificationState(currentUser, false, callback)
                } else {
                    callback(AppAuthResult.Error("User not found"))
                }
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Failed to update user", e)
                callback(AppAuthResult.Error("Failed to update user"))
            }
    }

    // Update user phone if needed
    private fun updateUserPhoneIfNeeded(
        uid: String,
        email: String,
        callback: (AppAuthResult) -> Unit
    ) {
        database.reference.child("Users").child(uid).get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // Update last login
                    snapshot.ref.child("lastLogin").setValue(System.currentTimeMillis())

                    // Check if email mapping exists
                    val encodedEmail = encodeEmail(email)
                    database.reference.child("Emails").child(encodedEmail)
                        .get()
                        .addOnSuccessListener { emailSnapshot ->
                            if (!emailSnapshot.exists()) {
                                // Create email mapping if missing
                                database.reference.child("Emails").child(encodedEmail)
                                    .setValue(uid)
                            }
                        }

                    val phone = snapshot.child("phone").getValue(String::class.java).orEmpty()
                    val persistedPhoneVerified = snapshot.child("phoneVerified").getValue(Boolean::class.java)
                    val updates = hashMapOf<String, Any>(
                        "emailVerified" to (auth.currentUser?.isEmailVerified == true),
                        "phoneVerified" to isPhoneCredentialVerified(auth.currentUser, persistedPhoneVerified),
                        "phoneE164" to normalizePhoneToE164(phone).orEmpty(),
                        "verificationUpdatedAt" to System.currentTimeMillis()
                    )
                    snapshot.ref.updateChildren(updates)

                    callback(AppAuthResult.Success(false))
                } else {
                    // User exists in Auth but not in DB - fix inconsistency
                    val provider = auth.currentUser?.providerData?.firstOrNull()?.providerId ?: "unknown"
                    saveUserToDatabase(uid, email, "", provider, false, callback)
                }
            }
            .addOnFailureListener {
                callback(AppAuthResult.Success(false))
            }
    }

    private fun evaluateVerificationState(
        user: FirebaseUser,
        isNewUser: Boolean,
        callback: (AppAuthResult) -> Unit
    ) {
        user.reload()
            .addOnSuccessListener {
                val emailVerified = user.isEmailVerified
                database.reference.child("Users").child(user.uid).get()
                    .addOnSuccessListener { snapshot ->
                        val phone = snapshot.child("phone").getValue(String::class.java).orEmpty()
                        val persistedPhoneVerified = snapshot.child("phoneVerified").getValue(Boolean::class.java)
                        val phoneVerified = isPhoneCredentialVerified(user, persistedPhoneVerified)
                        val phoneE164 = normalizePhoneToE164(phone).orEmpty()
                        val snapshotProvider = snapshot.child("provider").getValue(String::class.java)
                        val provider = resolvePrimaryProvider(user, snapshotProvider)
                        val requiresStrictVerification = !isSocialProvider(provider)

                        val updates = hashMapOf<String, Any>(
                            "emailVerified" to emailVerified,
                            "phoneVerified" to phoneVerified,
                            "phoneE164" to phoneE164,
                            "verificationUpdatedAt" to System.currentTimeMillis(),
                            "lastLogin" to System.currentTimeMillis()
                        )
                        snapshot.ref.updateChildren(updates)

                        when {
                            requiresStrictVerification && (!emailVerified || !phoneVerified) -> {
                                callback(
                                    AppAuthResult.VerificationRequired(
                                        emailVerified = emailVerified,
                                        phoneVerified = phoneVerified,
                                        message = buildVerificationMessage(emailVerified, phoneVerified, true),
                                        phone = phone
                                    )
                                )
                            }
                            else -> callback(AppAuthResult.Success(isNewUser))
                        }
                    }
                    .addOnFailureListener { e ->
                        callback(AppAuthResult.Error(e.message ?: "Failed to validate account status"))
                    }
            }
            .addOnFailureListener { e ->
                callback(AppAuthResult.Error(e.message ?: "Failed to refresh account status"))
            }
    }

    private fun buildVerificationMessage(
        emailVerified: Boolean,
        phoneVerified: Boolean,
        requiresPhoneVerification: Boolean
    ): String {
        return when {
            !emailVerified && requiresPhoneVerification && !phoneVerified -> "Verify your email and phone to continue."
            !emailVerified -> "Please verify your email to continue."
            requiresPhoneVerification -> "Please verify your phone to continue."
            else -> "Verification required"
        }
    }

    private fun buildTempPassword(): String {
        return TEMP_PASSWORD_PREFIX + UUID.randomUUID().toString().replace("-", "").take(12)
    }

    private fun buildPhoneCallbacks(
        phoneE164: String,
        onCodeSent: (verificationId: String, token: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onVerified: (AppAuthResult) -> Unit,
        onError: (String) -> Unit
    ): PhoneAuthProvider.OnVerificationStateChangedCallbacks {
        return object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                linkPhoneCredential(credential, onVerified)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                onError(e.message ?: "Phone verification failed")
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                onCodeSent(verificationId, token)
            }
        }
    }

    private fun linkPhoneCredential(
        credential: PhoneAuthCredential,
        callback: (AppAuthResult) -> Unit
    ) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            callback(AppAuthResult.Error("User not logged in"))
            return
        }

        currentUser.linkWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val resolvedUser = authResult.user ?: currentUser
                val phoneE164 = resolvedUser.phoneNumber ?: credential.smsCode?.let { null }
                persistVerifiedPhone(resolvedUser.uid, phoneE164, callback)
            }
            .addOnFailureListener { exception ->
                when (exception) {
                    is FirebaseAuthUserCollisionException,
                    is FirebaseAuthInvalidCredentialsException -> {
                        callback(AppAuthResult.Error("Invalid OTP or phone number already linked to another account"))
                    }
                    else -> {
                        // If phone is already linked to this user, treat as success and sync DB.
                        val alreadyLinked = currentUser.providerData.any { it.providerId == PhoneAuthProvider.PROVIDER_ID }
                        if (alreadyLinked) {
                            persistVerifiedPhone(currentUser.uid, currentUser.phoneNumber, callback)
                        } else {
                            callback(AppAuthResult.Error(exception.message ?: "Failed to verify phone"))
                        }
                    }
                }
            }
    }

    private fun persistVerifiedPhone(
        uid: String,
        phoneE164Input: String?,
        callback: (AppAuthResult) -> Unit
    ) {
        database.reference.child("Users").child(uid).get()
            .addOnSuccessListener { snapshot ->
                val existingPhone = snapshot.child("phone").getValue(String::class.java).orEmpty()
                val phoneE164 = phoneE164Input ?: normalizePhoneToE164(existingPhone).orEmpty()
                val localPhone = toLocalPhone(phoneE164).ifBlank { existingPhone }
                val updates = hashMapOf<String, Any>(
                    "phone" to localPhone,
                    "phoneE164" to phoneE164,
                    "phoneVerified" to true,
                    "verificationUpdatedAt" to System.currentTimeMillis()
                )

                snapshot.ref.updateChildren(updates)
                    .addOnSuccessListener {
                        val currentUser = auth.currentUser
                        if (currentUser != null) {
                            evaluateVerificationState(currentUser, false, callback)
                        } else {
                            callback(AppAuthResult.Success(false))
                        }
                    }
                    .addOnFailureListener { e ->
                        callback(AppAuthResult.Error(e.message ?: "Failed to save verified phone"))
                    }
            }
            .addOnFailureListener { e ->
                callback(AppAuthResult.Error(e.message ?: "Failed to load user profile"))
            }
    }

    private fun isPhoneCredentialVerified(user: FirebaseUser?, persistedPhoneVerified: Boolean?): Boolean {
        return (persistedPhoneVerified == true) && !user?.phoneNumber.isNullOrBlank()
    }

    private fun normalizePhoneToE164(rawPhone: String): String? {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) return null

        return when {
            trimmed.startsWith("+") && trimmed.drop(1).all { it.isDigit() } -> trimmed
            trimmed.length == PHONE_LENGTH && trimmed.all { it.isDigit() } -> "+91$trimmed"
            else -> null
        }
    }

    private fun toLocalPhone(phoneE164: String): String {
        val digits = phoneE164.filter { it.isDigit() }
        return if (digits.length >= PHONE_LENGTH) digits.takeLast(PHONE_LENGTH) else digits
    }

    private fun isSocialProvider(provider: String): Boolean {
        return provider == "google.com" || provider == "github.com"
    }

    private fun resolvePrimaryProvider(user: FirebaseUser, snapshotProvider: String?): String {
        val providers = user.providerData
            .map { it.providerId }
            .filter { it != "firebase" }

        return when {
            providers.any { it == "google.com" } -> "google.com"
            providers.any { it == "github.com" } -> "github.com"
            providers.any { it == "password" } -> "password"
            !snapshotProvider.isNullOrBlank() && snapshotProvider != "unknown" -> snapshotProvider
            else -> "password"
        }
    }

    // Check if email exists in database with caching
    private suspend fun checkEmailExists(email: String): CachedUserData? {
        val lowerEmail = email.lowercase()
        if (lowerEmail.isBlank()) return null

        // Check cache first
        emailUserCache[lowerEmail]?.let { cachedData ->
            if (cachedData.isValid()) {
                return cachedData
            }
        }

        return try {
            val encodedEmail = encodeEmail(email)
            val snapshot = database.reference
                .child("Emails")
                .child(encodedEmail)
                .get()
                .await()

            if (snapshot.exists()) {
                val uid = snapshot.getValue(String::class.java) ?: return null

                // Get user data to know provider
                val userSnapshot = database.reference
                    .child("Users")
                    .child(uid)
                    .get()
                    .await()

                if (userSnapshot.exists()) {
                    val provider = userSnapshot.child("provider").value as? String ?: "unknown"
                    val cachedData = CachedUserData(
                        uid = uid,
                        provider = provider,
                        timestamp = System.currentTimeMillis()
                    )
                    emailUserCache[lowerEmail] = cachedData
                    cachedData
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking email", e)
            null
        }
    }

    // Check if phone number already exists in database
    private suspend fun checkPhoneExists(phone: String, currentUid: String? = null): Boolean {
        if (phone.isEmpty()) return false

        val normalizedPhone = normalizePhoneToE164(phone) ?: return false
        val localPhone = toLocalPhone(normalizedPhone)

        return try {
            val byE164Snapshot = database.reference.child("Users")
                .orderByChild("phoneE164")
                .equalTo(normalizedPhone)
                .get()
                .await()

            val hasOtherAccountByE164 = byE164Snapshot.children.any { snapshot ->
                val uid = snapshot.key
                uid != null && uid != currentUid
            }

            if (hasOtherAccountByE164) {
                return true
            }

            val byLocalPhoneSnapshot = database.reference.child("Users")
                .orderByChild("phone")
                .equalTo(localPhone)
                .get()
                .await()

            byLocalPhoneSnapshot.children.any { snapshot ->
                val uid = snapshot.key
                uid != null && uid != currentUid
            }
        } catch (e: Exception) {
            Log.e(tag, "Error checking phone", e)
            false
        }
    }


    private fun encodeEmail(email: String): String {
        return email.lowercase()
            .replace(".", "_")
            .replace("@", "_")
    }

    fun signOut() {
        auth.signOut()
    }

    fun getCurrentUser(): FirebaseUser? {
        return auth.currentUser
    }

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }

    // Clear cache (call on logout)
    fun clearCache() {
        emailUserCache.clear()
    }
}
