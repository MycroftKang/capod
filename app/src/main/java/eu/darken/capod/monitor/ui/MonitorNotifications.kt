package eu.darken.capod.monitor.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.R
import eu.darken.capod.common.BuildConfigWrap
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.hasApiLevel
import eu.darken.capod.common.notifications.PendingIntentCompat
import eu.darken.capod.main.ui.MainActivity
import eu.darken.capod.pods.core.DualPodDevice
import eu.darken.capod.pods.core.HasCase
import eu.darken.capod.pods.core.HasChargeDetection
import eu.darken.capod.pods.core.HasEarDetection
import eu.darken.capod.pods.core.PodDevice
import eu.darken.capod.pods.core.SinglePodDevice
import eu.darken.capod.pods.core.getBatteryLevelCase
import eu.darken.capod.pods.core.getBatteryLevelHeadset
import eu.darken.capod.pods.core.getBatteryLevelLeftPod
import eu.darken.capod.pods.core.getBatteryLevelRightPod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import kotlin.math.roundToInt


class MonitorNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    notificationManager: NotificationManager,
    private val notificationViewFactory: MonitorNotificationViewFactory
) {

    private val builderLock = Mutex()
    private val builder: NotificationCompat.Builder

    init {
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_device_status_label),
            NotificationManager.IMPORTANCE_LOW
        ).run { notificationManager.createNotificationChannel(this) }
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID_CONNECTED,
            context.getString(R.string.notification_channel_device_status_connected_label),
            NotificationManager.IMPORTANCE_LOW
        ).run { notificationManager.createNotificationChannel(this) }

        val openIntent = Intent(context, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntentCompat.FLAG_IMMUTABLE
        )

        builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID).apply {
            setContentIntent(openPi)
            priority = NotificationCompat.PRIORITY_LOW
            setSmallIcon(eu.darken.capod.common.R.drawable.devic_earbuds_generic_both)
            setOngoing(true)
            setContentTitle(context.getString(eu.darken.capod.common.R.string.app_name))
            setOnlyAlertOnce(true)
        }
    }

    private fun averagePercent(p1: String, p2: String): String {
        val n1 = p1.removeSuffix("%").toInt()
        val n2 = p2.removeSuffix("%").toInt()

        val avg = (n1 + n2) / 2
        return "$avg%"
    }

    private fun getLiveUpdateBuilder(device: PodDevice?): NotificationCompat.Builder {
        if (device == null) {
            return builder.apply {
                setCustomContentView(null)
                setStyle(NotificationCompat.BigTextStyle())
                setContentTitle(context.getString(eu.darken.capod.common.R.string.pods_none_label_short))
                setSubText(context.getString(eu.darken.capod.common.R.string.app_name))
                setSmallIcon(eu.darken.capod.common.R.drawable.devic_earbuds_generic_both)
            }
        }

        // Options here should be mutually exclusive, and are prioritized by their order of importance
        // Some options are omitted here, as they will conflict with other options
        // TODO: Implement a settings pane to allow user to customize this
        val stateText = when {
            // Pods charging state
            // This goes first as pods should not be worn if it is still charging
            device is HasChargeDetection && device.isHeadsetBeingCharged -> {
                context.getString(eu.darken.capod.common.R.string.pods_charging_label)
            }

            // Pods wear state
            device is HasEarDetection -> {
                if (device.isBeingWorn) context.getString(eu.darken.capod.common.R.string.headset_being_worn_label)
                else context.getString(eu.darken.capod.common.R.string.headset_not_being_worn_label)
            }

            // Case charge state
            // This is under pods wear state as we don't want it conflicting with it
            device is HasCase && device.isCaseCharging -> {
                context.getString(eu.darken.capod.common.R.string.pods_charging_label)
            }

            else -> context.getString(eu.darken.capod.common.R.string.pods_case_unknown_state)
        }

        val batteryText = when (device) {
            is DualPodDevice -> {
                val left = device.batteryLeftPodPercent
                val right = device.batteryRightPodPercent
                when {
                    device is HasCase -> {
                        val text = mutableListOf<String>()

                        val case = device.batteryCasePercent


                        if (left != null){
                            val value = left.let { "${(it * 100).roundToInt()}" }
                            text.add("L $value")
                        }
                        if (right != null){
                            val value = right.let { "${(it * 100).roundToInt()}" }
                            text.add("R $value")
                        }
                        if (case != null)
                        {
                            val value = case.let { "${(it * 100).roundToInt()}" }

                            if ((left != null) || (right != null))
                                text.add("|")
                            text.add("C $value")
                        }

                        text.joinToString(" ")
                    }

                    else -> "L $left R $right"
                }
            }

            is SinglePodDevice -> {
                val headset = device.getBatteryLevelHeadset(context)
                when {
                    device is HasCase -> {
                        val case = device.getBatteryLevelCase(context)
                        "$headset $case"
                    }

                    else -> headset
                }
            }

            else -> "?"
        }

        var smallIcon: Int = eu.darken.capod.common.R.drawable.devic_earbuds_generic_both

        val shortBatteryText = when (device) {
            is DualPodDevice -> {
            val left = device.batteryLeftPodPercent
            val right = device.batteryRightPodPercent
            when {
                device is HasCase -> {
                    val case = device.batteryCasePercent

                    if (case != null)
                    {
                        smallIcon = eu.darken.capod.common.R.drawable.devic_airpods_gen1_case
                        val value = case.let { "${(it * 100).roundToInt()}%" }
                        "C $value"
                    }
                    else if ((left != null) && (right != null)) {
                        smallIcon = eu.darken.capod.common.R.drawable.devic_airpods_gen1_both
                        val value = averagePercent(left.let { "${(it * 100).roundToInt()}%" }, right.let { "${(it * 100).roundToInt()}%" })
                        "L·R $value"
                    }
                    else if (left != null){
                        smallIcon = eu.darken.capod.common.R.drawable.devic_airpods_gen1_left
                        val value = left.let { "${(it * 100).roundToInt()}%" }
                        "L $value"
                    }
                    else if (right != null){
                        smallIcon = eu.darken.capod.common.R.drawable.devic_airpods_gen1_right
                        val value = right.let { "${(it * 100).roundToInt()}%" }
                        "R $value"
                    }
                    else {
                        smallIcon = eu.darken.capod.common.R.drawable.devic_earbuds_generic_both
                        "?"
                    }
                }

                else -> {
                    smallIcon = eu.darken.capod.common.R.drawable.devic_earbuds_generic_both
                    "?"
                }
            }
        }

            is SinglePodDevice -> {
            val headset = device.getBatteryLevelHeadset(context)
            when {
                device is HasCase -> {
                    val case = device.getBatteryLevelCase(context)
                    "$headset $case"
                }

                else -> headset
            }
        }

            else -> "?"
        }

        return builder.apply {
            setCustomContentView(null)
            setStyle(NotificationCompat.BigTextStyle())
            setSmallIcon(smallIcon)
            setLargeIcon(BitmapFactory.decodeResource(context.resources, smallIcon))
//            setColorized(true)
            setContentTitle(batteryText)
            setSubText(stateText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                setRequestPromotedOngoing(true)
            }
            setShortCriticalText(shortBatteryText)
        }
    }

    private fun getBuilder(device: PodDevice?): NotificationCompat.Builder {
        if (device == null) {
            return builder.apply {
                setCustomContentView(null)
                setStyle(NotificationCompat.BigTextStyle())
                setContentTitle(context.getString(eu.darken.capod.common.R.string.pods_none_label_short))
                setSubText(context.getString(eu.darken.capod.common.R.string.app_name))
                setSmallIcon(eu.darken.capod.common.R.drawable.devic_earbuds_generic_both)
            }
        }

        return builder.apply {

            // Options here should be mutually exclusive, and are prioritized by their order of importance
            // Some options are omitted here, as they will conflict with other options
            // TODO: Implement a settings pane to allow user to customize this
            val stateText = when {
                // Pods charging state
                // This goes first as pods should not be worn if it is still charging
                device is HasChargeDetection && device.isHeadsetBeingCharged -> {
                    context.getString(eu.darken.capod.common.R.string.pods_charging_label)
                }

                // Pods wear state
                device is HasEarDetection -> {
                    if (device.isBeingWorn) context.getString(eu.darken.capod.common.R.string.headset_being_worn_label)
                    else context.getString(eu.darken.capod.common.R.string.headset_not_being_worn_label)
                }

                // Case charge state
                // This is under pods wear state as we don't want it conflicting with it
                device is HasCase && device.isCaseCharging -> {
                    context.getString(eu.darken.capod.common.R.string.pods_charging_label)
                }

                else -> context.getString(eu.darken.capod.common.R.string.pods_case_unknown_state)
            }

            val batteryText = when (device) {
                is DualPodDevice -> {
                    val left = device.getBatteryLevelLeftPod(context)
                    val right = device.getBatteryLevelRightPod(context)
                    when {
                        device is HasCase -> {
                            val case = device.getBatteryLevelCase(context)
                            "$left $case $right"
                        }

                        else -> "$left $right"
                    }
                }

                is SinglePodDevice -> {
                    val headset = device.getBatteryLevelHeadset(context)
                    when {
                        device is HasCase -> {
                            val case = device.getBatteryLevelCase(context)
                            "$headset $case"
                        }

                        else -> headset
                    }
                }

                else -> "?"
            }

            setStyle(NotificationCompat.DecoratedCustomViewStyle())
            setCustomBigContentView(notificationViewFactory.createContentView(device))
            setSmallIcon(device.iconRes)
            setContentTitle("$batteryText ~ $stateText")
            setSubText(null)
            log(TAG, VERBOSE) { "updatingNotification(): $device" }
        }
    }

    suspend fun getNotification(podDevice: PodDevice?, liveUpdate: Boolean): Notification = builderLock.withLock {
        val notificationBuilder = if (liveUpdate) {
            getLiveUpdateBuilder(podDevice).apply {
                setChannelId(NOTIFICATION_CHANNEL_ID)
            }
        } else {
            getBuilder(podDevice).apply {
                setChannelId(NOTIFICATION_CHANNEL_ID)
            }
        }

        notificationBuilder.build()
    }

    suspend fun getNotificationConnected(podDevice: PodDevice?): Notification = builderLock.withLock {
        getBuilder(podDevice).apply {
            setChannelId(NOTIFICATION_CHANNEL_ID_CONNECTED)
        }.build()
    }

    suspend fun getForegroundInfo(podDevice: PodDevice?): ForegroundInfo = builderLock.withLock {
        getBuilder(podDevice).apply {
            setChannelId(NOTIFICATION_CHANNEL_ID)
        }.toForegroundInfo()
    }

    @SuppressLint("InlinedApi")
    private fun NotificationCompat.Builder.toForegroundInfo(): ForegroundInfo = if (hasApiLevel(29)) {
        ForegroundInfo(
            NOTIFICATION_ID,
            this.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    } else {
        ForegroundInfo(
            NOTIFICATION_ID,
            this.build()
        )
    }

    companion object {
        val TAG = logTag("Monitor", "Notifications")
        private val NOTIFICATION_CHANNEL_ID = "${BuildConfigWrap.APPLICATION_ID}.notification.channel.device.status"
        private val NOTIFICATION_CHANNEL_ID_CONNECTED =
            "${BuildConfigWrap.APPLICATION_ID}.notification.channel.device.status.connected"
        internal const val NOTIFICATION_ID = 1
        internal const val NOTIFICATION_ID_CONNECTED = 2
    }
}
