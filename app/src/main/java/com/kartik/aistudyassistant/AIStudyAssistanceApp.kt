package com.kartik.aistudyassistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kartik.aistudyassistant.core.utils.SmartSensorManager
import com.kartik.aistudyassistant.ui.auth.SignInActivity
import com.kartik.aistudyassistant.ui.auth.SignUpActivity
import com.kartik.aistudyassistant.ui.home.MainActivity
import com.kartik.aistudyassistant.ui.launcher.LaunchLoaderActivity
import java.lang.ref.WeakReference
import java.util.WeakHashMap

class AIStudyAssistanceApp : Application(), Application.ActivityLifecycleCallbacks {

    private data class ActivitySensorUi(
        val eyeCareOverlay: View,
        val focusBanner: TextView,
        var hideFocusRunnable: Runnable? = null
    )

    private data class BottomSheetSensorUi(
        val hostActivity: WeakReference<Activity>,
        val eyeCareOverlay: View,
        val focusBanner: TextView,
        var hideFocusRunnable: Runnable? = null
    )

    private lateinit var smartSensorManager: SmartSensorManager
    private var activeSensorActivity: WeakReference<Activity>? = null
    private val activitySensorUi = WeakHashMap<Activity, ActivitySensorUi>()
    private val bottomSheetSensorUi = WeakHashMap<BottomSheetDialog, BottomSheetSensorUi>()

    private var isFocusModeActive = false
    private var isEyeCareActive = false

    override fun onCreate() {
        super.onCreate()

        smartSensorManager = SmartSensorManager(this)
        smartSensorManager.onFocusModeChanged = focusChanged@{ isFocusActive ->
            isFocusModeActive = isFocusActive
            val activity = activeSensorActivity?.get() ?: return@focusChanged
            if (activity is MainActivity || isExcludedScreen(activity)) return@focusChanged

            applySensorUi(activity, fromSensorEvent = true, showEyeCareToast = false)
            applyBottomSheetSensorUi(activity, fromSensorEvent = true)
        }
        smartSensorManager.onEyeCareChanged = eyeCareChanged@{ isEyeCareActive ->
            this.isEyeCareActive = isEyeCareActive

            val activity = activeSensorActivity?.get() ?: return@eyeCareChanged
            if (activity is MainActivity || isExcludedScreen(activity)) return@eyeCareChanged

            applySensorUi(activity, fromSensorEvent = true, showEyeCareToast = isEyeCareActive)
            applyBottomSheetSensorUi(activity, fromSensorEvent = true)
        }

        registerActivityLifecycleCallbacks(this)
    }

    private fun applySensorUi(activity: Activity, fromSensorEvent: Boolean, showEyeCareToast: Boolean) {
        val ui = ensureSensorUi(activity) ?: return
        activity.runOnUiThread {
            ui.eyeCareOverlay.visibility = if (isEyeCareActive) View.VISIBLE else View.GONE
            if (showEyeCareToast && isEyeCareActive) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.sensor_low_light_detected),
                    Toast.LENGTH_SHORT
                ).show()
            }

            applyFocusModeBanner(activity, ui, fromSensorEvent)
        }
    }

    fun bindSensorUiToBottomSheet(activity: Activity, dialog: BottomSheetDialog) {
        if (isExcludedScreen(activity) || isManagedByMainScreen(activity)) return

        dialog.setOnShowListener {
            val ui = ensureBottomSheetSensorUi(activity, dialog) ?: return@setOnShowListener
            bottomSheetSensorUi[dialog] = ui
            applyBottomSheetSensorUi(dialog, ui, fromSensorEvent = false)
        }

        dialog.setOnDismissListener {
            removeBottomSheetSensorUi(dialog)
        }
    }

    private fun ensureBottomSheetSensorUi(activity: Activity, dialog: BottomSheetDialog): BottomSheetSensorUi? {
        bottomSheetSensorUi[dialog]?.let { return it }

        val decorView = dialog.window?.decorView as? FrameLayout ?: return null
        val eyeCareOverlay = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(activity, R.color.sensor_eye_care_overlay))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = activity.dpToPx(80).toFloat()
        }

        val focusBanner = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = activity.dpToPx(24)
            }
            setPadding(activity.dpToPx(16), activity.dpToPx(10), activity.dpToPx(16), activity.dpToPx(10))
            setTextColor(ContextCompat.getColor(activity, R.color.white))
            elevation = activity.dpToPx(82).toFloat()
            visibility = View.GONE
        }

        decorView.addView(eyeCareOverlay)
        decorView.addView(focusBanner)

        return BottomSheetSensorUi(
            hostActivity = WeakReference(activity),
            eyeCareOverlay = eyeCareOverlay,
            focusBanner = focusBanner
        )
    }

    private fun applyBottomSheetSensorUi(activity: Activity, fromSensorEvent: Boolean) {
        val entries = bottomSheetSensorUi.entries.toList()
        entries.forEach { (dialog, ui) ->
            if (!dialog.isShowing) return@forEach
            if (ui.hostActivity.get() != activity) return@forEach
            applyBottomSheetSensorUi(dialog, ui, fromSensorEvent)
        }
    }

    private fun applyBottomSheetSensorUi(
        dialog: BottomSheetDialog,
        ui: BottomSheetSensorUi,
        fromSensorEvent: Boolean
    ) {
        val activity = ui.hostActivity.get() ?: return
        activity.runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread

            ui.eyeCareOverlay.visibility = if (isEyeCareActive) View.VISIBLE else View.GONE

            ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }
            ui.hideFocusRunnable = null

            if (isFocusModeActive) {
                ui.focusBanner.visibility = View.VISIBLE
                ui.focusBanner.text = activity.getString(R.string.sensor_focus_mode_on)
                ui.focusBanner.setBackgroundColor(ContextCompat.getColor(activity, R.color.accent_purple))
                return@runOnUiThread
            }

            if (!fromSensorEvent) {
                ui.focusBanner.visibility = View.GONE
                return@runOnUiThread
            }

            ui.focusBanner.visibility = View.VISIBLE
            ui.focusBanner.text = activity.getString(R.string.sensor_focus_mode_off)
            ui.focusBanner.setBackgroundColor(ContextCompat.getColor(activity, R.color.accent_green))
            val hideRunnable = Runnable {
                ui.focusBanner.visibility = View.GONE
            }
            ui.hideFocusRunnable = hideRunnable
            ui.focusBanner.postDelayed(hideRunnable, 3000)
        }
    }

    private fun removeBottomSheetSensorUi(dialog: BottomSheetDialog) {
        val ui = bottomSheetSensorUi.remove(dialog) ?: return
        ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }
        (ui.eyeCareOverlay.parent as? ViewGroup)?.removeView(ui.eyeCareOverlay)
        (ui.focusBanner.parent as? ViewGroup)?.removeView(ui.focusBanner)
    }

    private fun applyFocusModeBanner(activity: Activity, ui: ActivitySensorUi, fromSensorEvent: Boolean) {
        ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }
        ui.hideFocusRunnable = null

        if (isFocusModeActive) {
            ui.focusBanner.visibility = View.VISIBLE
            ui.focusBanner.text = activity.getString(R.string.sensor_focus_mode_on)
            ui.focusBanner.setBackgroundColor(ContextCompat.getColor(activity, R.color.accent_purple))
            return
        }

        if (!fromSensorEvent) {
            ui.focusBanner.visibility = View.GONE
            return
        }

        ui.focusBanner.visibility = View.VISIBLE
        ui.focusBanner.text = activity.getString(R.string.sensor_focus_mode_off)
        ui.focusBanner.setBackgroundColor(ContextCompat.getColor(activity, R.color.accent_green))
        val hideRunnable = Runnable {
            ui.focusBanner.visibility = View.GONE
        }
        ui.hideFocusRunnable = hideRunnable
        ui.focusBanner.postDelayed(hideRunnable, 3000)
    }

    private fun ensureSensorUi(activity: Activity): ActivitySensorUi? {
        activitySensorUi[activity]?.let { return it }

        val decorView = activity.window?.decorView as? FrameLayout ?: return null
        val eyeCareOverlay = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(activity, R.color.sensor_eye_care_overlay))
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

        val focusBanner = TextView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = activity.dpToPx(24)
            }
            setPadding(activity.dpToPx(16), activity.dpToPx(10), activity.dpToPx(16), activity.dpToPx(10))
            setTextColor(ContextCompat.getColor(activity, R.color.white))
            elevation = activity.dpToPx(6).toFloat()
            visibility = View.GONE
        }

        decorView.addView(eyeCareOverlay)
        decorView.addView(focusBanner)

        return ActivitySensorUi(eyeCareOverlay = eyeCareOverlay, focusBanner = focusBanner).also {
            activitySensorUi[activity] = it
        }
    }

    private fun removeSensorUi(activity: Activity) {
        val ui = activitySensorUi.remove(activity) ?: return
        ui.hideFocusRunnable?.let { ui.focusBanner.removeCallbacks(it) }

        (ui.eyeCareOverlay.parent as? ViewGroup)?.removeView(ui.eyeCareOverlay)
        (ui.focusBanner.parent as? ViewGroup)?.removeView(ui.focusBanner)
    }

    private fun Activity.dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun isExcludedScreen(activity: Activity): Boolean {
        return activity is SignInActivity ||
            activity is SignUpActivity ||
            activity is LaunchLoaderActivity
    }

    private fun isManagedByMainScreen(activity: Activity): Boolean {
        return activity is MainActivity
    }

    override fun onActivityResumed(activity: Activity) {
        if (isExcludedScreen(activity) || isManagedByMainScreen(activity)) {
            smartSensorManager.stop()
            activeSensorActivity = null
            return
        }

        activeSensorActivity = WeakReference(activity)
        applySensorUi(activity, fromSensorEvent = false, showEyeCareToast = false)
        smartSensorManager.start()
    }

    override fun onActivityPaused(activity: Activity) {
        if (activeSensorActivity?.get() == activity) {
            smartSensorManager.stop()
            activeSensorActivity = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        val activeDialogs = bottomSheetSensorUi.entries
            .filter { (_, ui) -> ui.hostActivity.get() == activity }
            .map { it.key }
        activeDialogs.forEach { removeBottomSheetSensorUi(it) }
        removeSensorUi(activity)
    }
}




