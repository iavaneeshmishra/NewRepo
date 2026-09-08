package app.ripple.mesh

import android.app.Application
import android.os.Process
import app.ripple.mesh.core.EventLog
import kotlin.system.exitProcess

class RippleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Replay the previous process's sanitized crash into the same ring that the
        // Diagnostics screen exports, then consume the on-disk handoff.
        val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
        prefs.getString(PENDING_CRASH, null)?.let { previous ->
            EventLog.global.e("crash", "previous process: $previous")
            prefs.edit().remove(PENDING_CRASH).apply()
        }

        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Exception messages can contain user input. Record only the exception
            // type and a bounded list of stack frames — never message plaintext,
            // keys, or node IDs — and synchronously hand it to the next process.
            val summary = sanitizedStack(error)
            EventLog.global.e("crash", summary)
            prefs.edit().putString(PENDING_CRASH, summary).commit()

            if (prior != null) {
                prior.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun sanitizedStack(error: Throwable): String = buildString {
        append(error.javaClass.name)
        error.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
            append("\n  at ")
            append(frame.toString())
        }
        if (error.stackTrace.size > MAX_STACK_FRAMES) append("\n  … truncated")
    }.take(MAX_CRASH_CHARS)

    private companion object {
        const val CRASH_PREFS = "ripple_crash_diagnostics"
        const val PENDING_CRASH = "pending_crash"
        const val MAX_STACK_FRAMES = 24
        const val MAX_CRASH_CHARS = 4_096
    }
}
