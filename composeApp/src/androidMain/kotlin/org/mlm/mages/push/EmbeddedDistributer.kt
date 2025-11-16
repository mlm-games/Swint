package org.mlm.mages.push

import org.unifiedpush.android.embedded_fcm_distributor.EmbeddedDistributorReceiver
import org.unifiedpush.android.embedded_fcm_distributor.Gateway

/**
 * This receiver configures the embedded FCM distributor to use a gateway,
 * which is required for apps like Matrix that don't support VAPID natively.
 */
class EmbeddedDistributor : EmbeddedDistributorReceiver() {
    override val gateway = object : Gateway {
        // Should probably host your own gateway (default key below)
        override val vapid = "BJVlg_p7GZr_ZluA2ace8aWj8dXVG6hB5L19VhMX3lbVd3c8IqrziiHVY3ERNVhB9Jje5HNZQI4nUOtF_XkUIyI"

        override fun getEndpoint(token: String): String {
            // It translates a standard webpush into a Matrix push.
            return "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify?token=$token"
        }
    }
}