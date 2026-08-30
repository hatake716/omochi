package io.github.hatake716.omochi

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Launcher-only router. The setup dashboard and the workbench remain unexported,
 * while an app-icon launch can still select the correct first screen.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeFromAppIcon()
    }

    private fun routeFromAppIcon() {
        val destination = OmochiLaunchPolicy.destination(
            runtimeInstalled = OmochiRuntime.isInstalled(this),
            japaneseClaudeInstalled = OmochiRuntime.isJapaneseClaudeInstalled(this),
        )
        val target = when (destination) {
            OmochiLaunchDestination.HOME -> MainActivity::class.java
            OmochiLaunchDestination.WORKBENCH -> WorkbenchActivity::class.java
        }
        startActivity(
            Intent(this, target).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        )
        finishAndRemoveTask()
    }
}
