package dev.miniwatts

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.miniwatts.core.Privileged
import dev.miniwatts.core.PrivilegedParser
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Optional privileged channel.
 *
 * Shizuku is not root: this app binds a user service that the Shizuku server instantiates inside its
 * own process, which runs with whatever identity the user started Shizuku with (ADB shell or root).
 * The app itself never calls `su` and never reads a vendor node directly, and the service only ever
 * gets a fixed read-only whitelist.
 *
 * Everything here is best-effort. The channel is only bound while the user enabled it and sampling
 * is running, and every failure keeps the readings at "unavailable" instead of inventing a value.
 */
class PrivilegedReader(private val context: Context, private val versionCode: Int) {

    enum class Status {
        /** The user has not enabled the channel. */
        OFF,
        /** Shizuku is not installed. */
        NO_SHIZUKU,
        /** Shizuku is installed and running, but this app has no permission yet. */
        NO_PERMISSION,
        BINDING,
        /** Bound. The probe result says whether policy actually lets it read the nodes. */
        READY,
        FAILED,
    }

    @Volatile var status: Status = Status.OFF
        private set

    /** Stable key for the UI to translate; keeps wording out of this class. */
    val statusKey: String
        get() =
            when (status) {
                Status.OFF -> "off"
                Status.NO_SHIZUKU -> "no_shizuku"
                Status.NO_PERMISSION -> "no_permission"
                Status.BINDING -> "binding"
                Status.READY -> if (latest == null) "binding" else "ready"
                Status.FAILED -> "failed"
            }

    /** Most recent probe result, for the UI to show identity, refusal reasons and node values. */
    @Volatile var latest: Privileged? = null
        private set

    @Volatile private var service: IPrivileged? = null
    @Volatile private var bound = false

    private val args =
        Shizuku.UserServiceArgs(
                ComponentName(context.packageName, PrivilegedUserService::class.java.name)
            )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(false)
            .version(versionCode)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPrivileged.Stub.asInterface(binder)
                status = Status.READY
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                bound = false
                latest = null
                status = Status.FAILED
            }
        }

    /** True when the Shizuku app is installed at all. */
    fun installed(): Boolean =
        runCatching {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            }
            .getOrDefault(false)
    /** True when the Shizuku server is reachable, i.e. Shizuku is running. */
    fun reachable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permitted(): Boolean =
        runCatching {
                reachable() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
            .getOrDefault(false)

    /** Must be called from the main thread. */
    fun connect() {
        if (bound || service != null) return
        when {
            !installed() -> {
                status = Status.NO_SHIZUKU
                return
            }
            !reachable() -> {
                status = Status.NO_PERMISSION
                return
            }
            !permitted() -> {
                status = Status.NO_PERMISSION
                return
            }
        }
        status = Status.BINDING
        bound =
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure {
                    service = null
                    status = Status.FAILED
                }
                .isSuccess
    }

    /** Must be called from the main thread. */
    fun disconnect() {
        if (!bound) {
            service = null
            latest = null
            return
        }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        bound = false
        service = null
        latest = null
        if (status != Status.OFF) status = Status.OFF
    }

    /** Safe from any thread; returns null whenever the channel is not usable. */
    fun probe(): Privileged? {
        val current = service
        if (current == null) {
            if (status == Status.READY) status = Status.BINDING
            return null
        }
        val result =
            runCatching { PrivilegedParser.parse(current.probe()) }
                .getOrElse {
                    // The service died or the binder was revoked: drop the binding so a later
                    // connect() can start over instead of leaking a stale one.
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    service = null
                    bound = false
                    latest = null
                    status = Status.FAILED
                    return null
                }
        latest = result
        status = Status.READY
        return result
    }

    private companion object {
        /**
         * The Shizuku app's package. Its permission namespace is `moe.shizuku.manager.permission.*`,
         * which is what the provider artifact declares; the application id is different, so the two
         * must not be confused when checking whether Shizuku is installed.
         */
        val SHIZUKU_PACKAGE = ShizukuProvider.MANAGER_APPLICATION_ID
    }
}
