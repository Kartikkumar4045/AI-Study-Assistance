package com.kartik.aistudyassistant.ui.auth

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.kartik.aistudyassistant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignUpActivityVerificationFlowTest {

    @Before
    fun signOutBeforeEach() {
        FirebaseAuth.getInstance().signOut()
    }

    @Test
    fun staleAttemptIsRejectedAfterPhoneAttemptChanges() {
        ActivityScenario.launch(SignUpActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstAttempt = callIntMethod(activity, "beginPhoneAttempt", "9876543210")
                val firstIsCurrent = callBooleanMethod(
                    activity,
                    "isCurrentPhoneAttempt",
                    firstAttempt,
                    "9876543210"
                )

                callIntMethod(activity, "beginPhoneAttempt", "9123456780")

                val staleAttemptStillCurrent = callBooleanMethod(
                    activity,
                    "isCurrentPhoneAttempt",
                    firstAttempt,
                    "9876543210"
                )

                assertTrue(firstIsCurrent)
                assertFalse(staleAttemptStillCurrent)
            }
        }
    }

    @Test
    fun resendWithInvalidPhoneShowsInlineErrorAndKeepsStateSafe() {
        ActivityScenario.launch(SignUpActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val etPhone = activity.findViewById<EditText>(R.id.etPhone)
                val btnResend = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnResendPhoneOtp)

                etPhone.setText("123")
                btnResend.isEnabled = true
                btnResend.performClick()

                assertEquals("Enter valid 10-digit Indian phone number", etPhone.error?.toString())
                assertNull(readPrivateField(activity, "verificationId") as String?)
            }
        }
    }

    @Test
    fun editVerifiedContactsResetsVerificationStateWhenNoPendingUser() {
        ActivityScenario.launch(SignUpActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                writePrivateField(activity, "emailVerified", true)
                writePrivateField(activity, "phoneVerified", true)
                writePrivateField(activity, "lastVerifiedEmail", "verified@example.com")
                writePrivateField(activity, "lastOtpTargetPhone", "9876543210")
                callUnitMethod(activity, "updateVerificationUi")

                val btnEdit = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditVerifiedContacts)
                assertTrue(btnEdit.visibility == android.view.View.VISIBLE)

                btnEdit.performClick()

                assertFalse(readPrivateField(activity, "emailVerified") as Boolean)
                assertFalse(readPrivateField(activity, "phoneVerified") as Boolean)
                assertNull(readPrivateField(activity, "lastVerifiedEmail") as String?)
                assertNull(readPrivateField(activity, "lastOtpTargetPhone") as String?)
            }
        }
    }

    private fun writePrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun readPrivateField(target: Any, fieldName: String): Any? {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }

    private fun callIntMethod(target: Any, methodName: String, arg: String): Int {
        val method = target.javaClass.getDeclaredMethod(methodName, String::class.java)
        method.isAccessible = true
        return method.invoke(target, arg) as Int
    }

    private fun callBooleanMethod(target: Any, methodName: String, id: Int, phone: String): Boolean {
        val method = target.javaClass.getDeclaredMethod(methodName, Int::class.javaPrimitiveType, String::class.java)
        method.isAccessible = true
        return method.invoke(target, id, phone) as Boolean
    }

    private fun callUnitMethod(target: Any, methodName: String) {
        val method = target.javaClass.getDeclaredMethod(methodName)
        method.isAccessible = true
        method.invoke(target)
    }
}

