package com.moeez.cardash

import android.service.notification.NotificationListenerService

/**
 * We don't actually need to read notifications - we only need this service
 * to exist and be enabled in Settings > Apps > Special Access > Notification
 * access, because that's the permission Android requires before any app
 * (without root) is allowed to call MediaSessionManager.getActiveSessions().
 * That's how we read the Bluetooth AVRCP track info the head unit's own
 * Bluetooth stack exposes as a MediaSession.
 *
 * (Being a bound listener also means Android restarts CarDash's process after
 * the firmware force-stops it at power-off; CarDashApp then does the rest.)
 */
class NotificationListener : NotificationListenerService() // see CarDashApp for start-up work
