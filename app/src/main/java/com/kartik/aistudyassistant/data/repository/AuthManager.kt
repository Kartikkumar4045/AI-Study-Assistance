package com.kartik.aistudyassistant.data.repository

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialException
import com.kartik.aistudyassistant.data.model.AuthResult as AppAuthResult
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.auth.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
                        provider = "google.com",
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
            .addCustomParameter("allow_signup", "true") // Fixed: Allow signups
            .build()

        auth.startActivityForSignInWithProvider(activity, provider)
            .addOnSuccessListener { authResult ->
                val user = authResult.user
                user?.let {
                    val email = it.email ?: ""
                    // Check if user exists in database and handle accordingly
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val existingUserData = checkEmailExists(email)

                            withContext(Dispatchers.Main) {
                                if (existingUserData != null && existingUserData.uid != it.uid) {
                                    // Email exists with different UID - this shouldn't happen with GitHub
                                    // Handle by linking or showing error
                                    callback(AppAuthResult.Error(
                                        "This email is already registered with ${existingUserData.provider}. " +
                                                "Please sign in with ${existingUserData.provider} first."
                                    ))
                                } else {
                                    // Either new user or same user, save/update in database
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
        provider: String,
        email: String,
        callback: (AppAuthResult) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if email exists in database
                val existingUserData = checkEmailExists(email)

                withContext(Dispatchers.Main) {
                    if (existingUserData != null) {
                        // Email exists, try to sign in with credential and link
                        linkProviderToExistingAccount(
                            idToken = idToken,
                            provider = provider,
                            existingUid = existingUserData.uid,
                            existingProvider = existingUserData.provider,
                            callback = callback
                        )
                    } else {
                        // New email, create account with this provider
                        createProviderAccount(
                            idToken = idToken,
                            provider = provider,
                            email = email,
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

    // Link provider to existing account
    private fun linkProviderToExistingAccount(
        idToken: String,
        provider: String,
        existingUid: String,
        existingProvider: String,
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
                        linkAccounts(user, existingUid, provider, callback)
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

    // Link two accounts
    private fun linkAccounts(
        newUser: FirebaseUser?,
        existingUid: String,
        provider: String,
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
                                                        phoneVerified = isPhoneLocallyVerified(phone),
                                                        message = "Verify your email to continue. We sent a verification link."
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
        val emailVerified = auth.currentUser?.isEmailVerified == true
        val phoneVerified = isPhoneLocallyVerified(phone)
        val userMap = HashMap<String, Any>().apply {
            put("email", email.lowercase())
            put("phone", phone)
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
                // Save email mapping
                val encodedEmail = encodeEmail(email)
                database.reference.child("Emails").child(encodedEmail)
                    .setValue(uid)
                    .addOnSuccessListener {
                        // Update cache
                        emailUserCache[email.lowercase()] = CachedUserData(
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
                    val updates = hashMapOf<String, Any>(
                        "emailVerified" to (auth.currentUser?.isEmailVerified == true),
                        "phoneVerified" to isPhoneLocallyVerified(phone),
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
                        val phoneVerified = persistedPhoneVerified ?: isPhoneLocallyVerified(phone)

                        val updates = hashMapOf<String, Any>(
                            "emailVerified" to emailVerified,
                            "phoneVerified" to phoneVerified,
                            "verificationUpdatedAt" to System.currentTimeMillis(),
                            "lastLogin" to System.currentTimeMillis()
                        )
                        snapshot.ref.updateChildren(updates)

                        when {
                            !emailVerified || !phoneVerified -> {
                                callback(
                                    AppAuthResult.VerificationRequired(
                                        emailVerified = emailVerified,
                                        phoneVerified = phoneVerified,
                                        message = buildVerificationMessage(emailVerified, phoneVerified)
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

    private fun buildVerificationMessage(emailVerified: Boolean, phoneVerified: Boolean): String {
        return when {
            !emailVerified && !phoneVerified -> "Verify your email and phone to continue."
            !emailVerified -> "Please verify your email to continue."
            else -> "Please verify your phone to continue."
        }
    }

    private fun isPhoneLocallyVerified(phone: String): Boolean {
        return phone.length == PHONE_LENGTH && phone.all { it.isDigit() }
    }

    // Check if email exists in database with caching
    private suspend fun checkEmailExists(email: String): CachedUserData? {
        val lowerEmail = email.lowercase()

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
    private suspend fun checkPhoneExists(phone: String): Boolean {
        if (phone.isEmpty()) return false
        return try {
            val snapshot = database.reference.child("Users")
                .orderByChild("phone")
                .equalTo(phone)
                .get()
                .await()
            snapshot.exists()
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

