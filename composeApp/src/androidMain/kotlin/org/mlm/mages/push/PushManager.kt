package org.mlm.mages.push

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.ui.SelectDistributorDialogsBuilder
import org.unifiedpush.android.connector.ui.UnifiedPushFunctions
import androidx.core.content.edit

object PushManager {
    private const val PREFS_NAME = "unifiedpush_prefs"
    const val DEFAULT_INSTANCE = "default"

    /**
     * Call this from your UI (e.g., a settings button) to start the registration process.
     * It will automatically handle showing a dialog to the user if needed.
     */
    fun registerWithDialog(context: Context, instance: String = DEFAULT_INSTANCE) {
        SelectDistributorDialogsBuilder(context, getUnifiedPushFunctions(context))
            .apply {
                this.instances = listOf(instance)
            }.run()
    }

    fun registerSilently(context: Context, instance: String = DEFAULT_INSTANCE) {
        if (UnifiedPush.getSavedDistributor(context).isNullOrBlank()) {
            // No distributor is configured, do nothing.
            // The user will be prompted next time they open the app.
            return
        }
        UnifiedPush.register(context, instance)
    }

    fun unregister(context: Context, instance: String = DEFAULT_INSTANCE) {
        UnifiedPush.unregister(context, instance)
    }

    private fun getUnifiedPushFunctions(context: Context) = object : UnifiedPushFunctions {
        override fun getAckDistributor(): String? {
            return UnifiedPush.getAckDistributor(context)
        }

        override fun getDistributors(): List<String> =
            UnifiedPush.getDistributors(context)

        override fun register(instance: String) =
            UnifiedPush.register(context, instance)

        override fun saveDistributor(distributor: String) =
            UnifiedPush.saveDistributor(context, distributor)

        override fun tryUseDefaultDistributor(callback: (Boolean) -> Unit) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context, callback)
        }
    }

    // Methods for storing/retrieving endpoint info remain useful for debugging or re-registration.
    fun saveEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString("endpoint_$instance", endpoint.url) }
    }

    fun getEndpoint(context: Context, instance: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("endpoint_$instance", null)
    }

    fun removeEndpoint(context: Context, instance: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove("endpoint_$instance") }
    }
}