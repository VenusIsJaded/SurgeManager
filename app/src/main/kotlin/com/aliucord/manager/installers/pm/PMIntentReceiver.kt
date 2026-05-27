package com.aliucord.manager.installers.pm

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import com.aliucord.manager.installers.InstallerResult
import com.aliucord.manager.installers.UnknownInstallerError
import dev.surgecord.manager.BuildConfig

/**
 * This class is used as a callback receiver for [PackageInstaller] events,
 * registered as a [PendingIntent]. If [PMIntentReceiver.EXTRA_RELAY_ENABLED] is set to true on
 * incoming intents, then the incoming intent will be parsed and relayed as an intent intended for [PMResultReceiver]
 * that was registered dynamically with the correct session id, which will handle the relayed result and return
 * it as a callback back to the application.
 */
class PMIntentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val realSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val expectedSessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        if (realSessionId != expectedSessionId) return

        try {
            handleSessionIntent(context, intent, expectedSessionId)
        } catch (error: Throwable) {
            if (intent.getBooleanExtra(EXTRA_RELAY_ENABLED, false)) {
                val relayIntent = Intent(PMResultReceiver.ACTION_RECEIVE_RESULT)
                    .setPackage(BuildConfig.APPLICATION_ID)
                    .putExtra(PMResultReceiver.EXTRA_SESSION_ID, expectedSessionId)
                    .putExtra(PMResultReceiver.EXTRA_RESULT, UnknownInstallerError(error))

                context.sendBroadcast(relayIntent)
            } else {
                Log.e(BuildConfig.TAG, "[PMIntentReceiver] Failed to handle intent", error)
            }
        }
    }

    private fun handleSessionIntent(context: Context, intent: Intent, sessionId: Int) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        // Log the full status message from the PackageManager — this is critical for diagnosing
        // STATUS_FAILURE_INCOMPATIBLE (code 6), which covers many sub-reasons only visible here.
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.w(BuildConfig.TAG, "PM session status=$status message=$statusMessage")

        // Launch the user action intent
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmationIntent = intent
                .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: throw IllegalStateException("Package installer requested user action without a confirmation intent")

            context.startActivity(confirmationIntent)

            return
        }

        // Handle install result
        val installerResult = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallerResult.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallerResult.Cancelled(systemTriggered = true)

            else -> {
                Log.w(BuildConfig.TAG, "PM failed with error code $status, message: $statusMessage")

                if (status <= PackageInstaller.STATUS_SUCCESS) {
                    // Unknown status code (not an error)
                    return
                } else {
                    PMInstallerError(status, statusMessage).also {
                        val toastText = if (statusMessage.isNullOrBlank()) {
                            it.getLocalizedReason(context)
                        } else {
                            "${it.getLocalizedReason(context)}\n($statusMessage)"
                        }

                        Toast.makeText(
                            /* context = */ context,
                            /* text = */ toastText,
                            /* duration = */ Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // Forward result to PMResultReceiver if relaying is enabled and have a real result
        if (intent.getBooleanExtra(EXTRA_RELAY_ENABLED, false)) {
            val relayIntent = Intent(PMResultReceiver.ACTION_RECEIVE_RESULT)
                .setPackage(BuildConfig.APPLICATION_ID)
                .putExtra(PMResultReceiver.EXTRA_SESSION_ID, sessionId)
                .putExtra(PMResultReceiver.EXTRA_RESULT, installerResult)

            context.sendBroadcast(relayIntent)
        }
    }

    companion object {
        const val EXTRA_RELAY_ENABLED = "relayEnabled"
        const val EXTRA_SESSION_ID = "expectedSessionId"
    }
}
