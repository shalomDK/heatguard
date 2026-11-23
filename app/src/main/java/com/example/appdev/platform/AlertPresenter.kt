package com.example.appdev.platform

import com.example.appdev.domain.model.BatterySnapshot
import com.example.appdev.domain.model.UserSettings

/** How the app tells the user about overheating. Implemented by [AlertNotifier]; faked in tests. */
interface AlertPresenter {
    fun showOverheatAlert(snapshot: BatterySnapshot, settings: UserSettings)
    fun cancelOverheatAlert()
    fun vibrate()

    /** An early warning: the phone is projected to reach the threshold in about [minutes] minutes. */
    fun showPreAlert(snapshot: BatterySnapshot, settings: UserSettings, minutes: Int)

    /** Removes the early warning (the forecast eased, or the real alert has taken over). */
    fun cancelPreAlert()

    /** Refreshes the persistent monitoring notification. */
    fun updateMonitoring(snapshot: BatterySnapshot?, settings: UserSettings, overheating: Boolean)
}
