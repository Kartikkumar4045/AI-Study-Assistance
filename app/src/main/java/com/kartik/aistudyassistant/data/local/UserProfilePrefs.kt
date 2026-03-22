package com.kartik.aistudyassistant.data.local

import android.content.Context
import com.google.firebase.auth.FirebaseUser

data class CachedUserProfile(
    val name: String,
    val email: String,
    val phone: String
)

object UserProfilePrefs {
    private const val PREF_FILE = "user_profile_prefs"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"
    private const val KEY_PHONE = "phone"

    fun save(context: Context, name: String, email: String, phone: String) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, name)
            .putString(KEY_EMAIL, email)
            .putString(KEY_PHONE, phone)
            .apply()
    }

    fun read(context: Context): CachedUserProfile {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        return CachedUserProfile(
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty(),
            phone = prefs.getString(KEY_PHONE, "").orEmpty()
        )
    }

    fun cacheFromUser(context: Context, user: FirebaseUser?, fallbackPhone: String = "") {
        if (user == null) return

        val resolvedName = user.displayName.orEmpty().ifBlank {
            user.email.orEmpty().substringBefore("@").replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }

        save(
            context = context,
            name = resolvedName,
            email = user.email.orEmpty(),
            phone = user.phoneNumber.orEmpty().ifBlank { fallbackPhone }
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

