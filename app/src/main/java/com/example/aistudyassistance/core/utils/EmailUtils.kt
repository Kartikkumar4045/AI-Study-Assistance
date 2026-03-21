package com.example.aistudyassistance.core.utils

object EmailUtils {
    fun encodeEmail(email: String): String {
        return email.lowercase()
            .replace(".", "_")
            .replace("@", "_")
    }
}
