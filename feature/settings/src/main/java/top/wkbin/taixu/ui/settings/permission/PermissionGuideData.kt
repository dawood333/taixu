package top.wkbin.taixu.ui.settings.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import top.wkbin.taixu.ui.components.RuntimeIconName

/**
 * 厂商保活引导步骤模型
 */
data class PermissionStep(
    val title: String,
    val description: String,
    val tip: String? = null,
)

/**
 * 品牌定义与识别模型
 */
enum class OemBrand(val id: String, val label: String, val osName: String) {
    XIAOMI("xiaomi", "Xiaomi / Redmi", "HyperOS / MIUI"),
    HUAWEI("huawei", "Huawei / Honor", "HarmonyOS / MagicOS"),
    VIVO("vivo", "vivo / iQOO", "OriginOS / Funtouch"),
    OPPO("oppo", "OPPO / OnePlus / realme", "ColorOS / RealmeUI"),
    MEIZU("meizu", "Meizu", "Flyme"),
    SAMSUNG("samsung", "Samsung", "One UI"),
    GENERIC("generic", "Stock Android / Other brands", "AOSP / Android");

    companion object {
        fun detect(): OemBrand {
            val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
            val brand = Build.BRAND?.lowercase().orEmpty()
            val fingerprint = Build.FINGERPRINT?.lowercase().orEmpty()
            val hardware = Build.HARDWARE?.lowercase().orEmpty()
            val combined = "$manufacturer $brand $fingerprint $hardware"

            return when {
                combined.contains("xiaomi") || combined.contains("redmi") ||
                    combined.contains("poco") || combined.contains("miui") -> XIAOMI
                combined.contains("huawei") || combined.contains("honor") ||
                    combined.contains("emui") || combined.contains("magic") -> HUAWEI
                combined.contains("vivo") || combined.contains("iqoo") ||
                    combined.contains("funtouch") || combined.contains("origin") -> VIVO
                combined.contains("oppo") || combined.contains("realme") ||
                    combined.contains("oneplus") || combined.contains("coloros") -> OPPO
                combined.contains("meizu") || combined.contains("flyme") -> MEIZU
                combined.contains("samsung") -> SAMSUNG
                else -> GENERIC
            }
        }
    }
}

/**
 * 保活与权限专题枚举
 */
enum class KeepaliveTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: RuntimeIconName,
) {
    AUTOSTART(
        id = "autostart",
        title = "Autostart and background wake-up",
        subtitle = "Allow the system to start the sandbox and Agent services automatically at boot or when needed in the background",
        icon = RuntimeIconName.Cpu,
    ),
    BATTERY_UNRESTRICTED(
        id = "battery_unrestricted",
        title = "Unrestricted battery optimization",
        subtitle = "Exempt TaiXu from system power-saving policies and Doze so PRoot processes are not frozen when the screen turns off",
        icon = RuntimeIconName.Battery,
    ),
    LOCK_RECENTS(
        id = "lock_recents",
        title = "Lock the app in Recents",
        subtitle = "Lock TaiXu in the Recents list to prevent one-tap cleanup from killing foreground services",
        icon = RuntimeIconName.Shield,
    ),
    FLOATING_OVERLAY(
        id = "floating_overlay",
        title = "Overlay and background pop-ups",
        subtitle = "Allow build-completion and Agent interaction notifications/windows to appear while the app is in the background",
        icon = RuntimeIconName.Terminal,
    ),
    NOTIFICATION(
        id = "notification",
        title = "Notifications and foreground service",
        subtitle = "Keep the foreground-service notification visible so the Linux runtime can remain at the highest available process priority",
        icon = RuntimeIconName.Chat,
    ),
}

/**
 * 厂商保活与权限引导知识库仓储
 */
object PermissionGuideRepository {

    fun getSteps(brand: OemBrand, topic: KeepaliveTopic): List<PermissionStep> {
        return when (topic) {
            KeepaliveTopic.AUTOSTART -> when (brand) {
                OemBrand.XIAOMI -> listOf(
                    PermissionStep("Open App settings", "Open system Settings ➔ App settings ➔ Permission management ➔ Autostart management."),
                    PermissionStep("Enable TaiXu autostart", "Find TaiXu in the app list and turn Autostart on."),
                    PermissionStep("Allow apps to wake each other", "Open TaiXu's app details and enable “Allow associated launch / Allow other apps to wake this app” (recommended on HyperOS)."),
                )
                OemBrand.HUAWEI -> listOf(
                    PermissionStep("Open App Launch Management", "Open Phone Manager ➔ App Launch Management."),
                    PermissionStep("Switch to manual management", "Find TaiXu, turn off Auto-manage, then enable all three options in the dialog: Allow autostart, Allow associated launch, and Allow background activity."),
                )
                OemBrand.VIVO -> listOf(
                    PermissionStep("Open Autostart permissions", "Open system Settings ➔ Apps & permissions ➔ Permission management ➔ Permissions tab ➔ Autostart."),
                    PermissionStep("Enable TaiXu autostart", "Find TaiXu and allow autostart."),
                    PermissionStep("Allow high-power background activity", "In Settings ➔ Battery ➔ High-power background activity, allow TaiXu to keep running in the background."),
                )
                OemBrand.OPPO -> listOf(
                    PermissionStep("Open Autostart management", "Open system Settings ➔ Apps ➔ Autostart management."),
                    PermissionStep("Allow autostart and associated launch", "Find TaiXu and enable Allow autostart and Allow associated launch."),
                )
                OemBrand.MEIZU -> listOf(
                    PermissionStep("Open Background management", "Open Phone Manager ➔ Permission management ➔ Background management."),
                    PermissionStep("Allow background execution", "Set TaiXu to Allow background execution or Keep awake in background."),
                )
                OemBrand.SAMSUNG -> listOf(
                    PermissionStep("Check automatic app launch", "Open Settings ➔ Battery ➔ Background usage restrictions."),
                    PermissionStep("Remove from the sleeping-app list", "Make sure TaiXu is not listed under Deep sleeping apps and add it to Never sleeping apps."),
                )
                OemBrand.GENERIC -> listOf(
                    PermissionStep("Check system startup permissions", "In the app details in system Settings, look for Autostart or Launch at boot permissions and allow them."),
                )
            }
            KeepaliveTopic.BATTERY_UNRESTRICTED -> when (brand) {
                OemBrand.XIAOMI -> listOf(
                    PermissionStep("App battery policy", "Long-press the TaiXu icon ➔ App info ➔ scroll down to Battery policy."),
                    PermissionStep("Set to Unrestricted", "Choose Unrestricted (does not limit background activity and is best for PRoot builds and long Agent tasks)."),
                )
                OemBrand.HUAWEI -> listOf(
                    PermissionStep("Ignore battery optimization", "Open Settings ➔ Apps ➔ Permission management ➔ top-right menu Special access ➔ Battery optimization."),
                    PermissionStep("Set to Not optimized", "Select All apps, find TaiXu, and set it to Not optimized."),
                )
                OemBrand.VIVO -> listOf(
                    PermissionStep("Allow high-power background activity", "Open Settings ➔ Battery ➔ Background power management."),
                    PermissionStep("Allow high-power background execution", "Find TaiXu and select Allow high-power background execution."),
                )
                OemBrand.OPPO -> listOf(
                    PermissionStep("Battery usage management", "Long-press the TaiXu icon ➔ App details ➔ Battery usage management."),
                    PermissionStep("Enable all background options", "Enable Allow full background activity and Allow foreground wake-up, and disable Deep power-saving optimization."),
                )
                OemBrand.SAMSUNG -> listOf(
                    PermissionStep("Battery usage mode", "Open system Settings ➔ Apps ➔ TaiXu ➔ Battery."),
                    PermissionStep("Set to Unrestricted", "Change the setting from Optimized to Unrestricted."),
                )
                else -> listOf(
                    PermissionStep("Ignore battery optimization", "Open App info ➔ Battery ➔ choose Unrestricted or request exemption from system battery optimization."),
                )
            }
            KeepaliveTopic.LOCK_RECENTS -> listOf(
                PermissionStep("Open the Recents screen", "Swipe up from the bottom and hold (or tap the Recents navigation button) to open the task-card view."),
                PermissionStep("Lock the TaiXu app card", "Long-press the TaiXu task card (or swipe the card down) and tap the 🔒 lock icon to prevent the system from killing it during one-tap cleanup."),
            )
            KeepaliveTopic.FLOATING_OVERLAY -> listOf(
                PermissionStep("Enable overlay / display over other apps", "Open App info ➔ Advanced ➔ Display over other apps / Overlay, then set it to Allow."),
                PermissionStep("Background pop-up permission", "On Xiaomi, vivo, and similar systems, enable Background pop-up windows or Home-screen shortcuts under Permission management."),
            )
            KeepaliveTopic.NOTIFICATION -> listOf(
                PermissionStep("Allow notifications and persistent foreground service", "Ensure TaiXu has notification permission. It starts a persistent foreground service while launching the PRoot sandbox and Agent loop to help preserve process priority."),
            )
        }
    }

    /**
     * 检查某项权限在当前系统中的状态
     */
    fun checkStatus(context: Context, topic: KeepaliveTopic): Boolean {
        return when (topic) {
            KeepaliveTopic.BATTERY_UNRESTRICTED -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            }
            KeepaliveTopic.NOTIFICATION -> {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            KeepaliveTopic.FLOATING_OVERLAY -> {
                Settings.canDrawOverlays(context)
            }
            KeepaliveTopic.AUTOSTART,
            KeepaliveTopic.LOCK_RECENTS -> {
                // Android 无公开 API 直接读取 OEM 私有自启动/最近任务加锁状态
                false
            }
        }
    }

    /**
     * 构建跳转至该专题设置页的 Intent 列表（按优先级降级尝试）
     */
    fun buildLaunchIntents(context: Context, brand: OemBrand, topic: KeepaliveTopic): List<Intent> {
        val pkg = context.packageName
        return when (topic) {
            KeepaliveTopic.AUTOSTART -> buildAutostartIntents(pkg, brand)
            KeepaliveTopic.BATTERY_UNRESTRICTED -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.FLOATING_OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")),
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.NOTIFICATION -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                },
                appDetailsIntent(pkg),
            )
            KeepaliveTopic.LOCK_RECENTS -> listOf(
                // 最近任务加锁不需要跳转，用户直接呼出多任务即可
                appDetailsIntent(pkg),
            )
        }
    }

    private fun buildAutostartIntents(pkg: String, brand: OemBrand): List<Intent> {
        return when (brand) {
            OemBrand.XIAOMI -> listOf(
                intentFor("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                intentFor("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
                intentFor("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                appDetailsIntent(pkg),
            )
            OemBrand.HUAWEI -> listOf(
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                intentFor("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.VIVO -> listOf(
                intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                intentFor("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                intentFor("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                appDetailsIntent(pkg),
            )
            OemBrand.OPPO -> listOf(
                intentFor("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intentFor("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                intentFor("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.MEIZU -> listOf(
                intentFor("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                intentFor("com.meizu.safe", "com.meizu.safe.security.AppSecActivity"),
                appDetailsIntent(pkg),
            )
            OemBrand.SAMSUNG -> listOf(
                appDetailsIntent(pkg),
            )
            OemBrand.GENERIC -> listOf(
                appDetailsIntent(pkg),
            )
        }
    }

    private fun intentFor(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun appDetailsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
