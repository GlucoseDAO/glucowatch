package io.github.antonkulaga.glucowatch.phone

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import glucowatch.core.*
import java.io.File

/** Explicit local archive smoke test, no network and no added test framework dependency.
 * adb shell am instrument -w -e archive <private-file> <test-package>/.ExerciseSmokeTest
 */
class ExerciseSmokeTest : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); options = arguments ?: Bundle(); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            if (options.getString("mode") == "alerts") {
                verifyAlerts()
                result.putString("result", "Android background network job scheduling, notification posting, duplicate suppression, urgent escalation, demo suppression and historical staleness passed. Test preferences isolated; notifications cleared.")
                finish(Activity.RESULT_OK, result)
                return
            }
            val file = File(requireNotNull(options.getString("archive")) { "Supply an explicit archive path." })
            val store = WorkoutStore(targetContext)
            val ids = mutableSetOf<String>(); var fresh = 0; var detailed = 0
            val parsed = StravaArchive.read(file) { recording ->
                val (entry, added) = store.import(recording)
                check(entry.id == recording.workout.id)
                val saved = store.load(entry.id)
                check(saved.samples == recording.workout.samples && saved.lengths == recording.workout.lengths)
                check(saved.startMillis == recording.workout.startMillis && saved.endMillis == recording.workout.endMillis)
                if (added) fresh++
                if (saved.samples.isNotEmpty()) detailed++
                ids += entry.id
            }
            check(ids.size == parsed.imported) { "Recording IDs collided." }
            // Verify archive-level duplicate reuse, rather than checking only a digest function.
            StravaArchive.read(file) { check(!store.import(it).second) }
            val chosen = store.entries().firstOrNull { it.sport == "Run" && it.end > it.start } ?: store.entries().first()
            startActivitySync(Intent(targetContext, ExerciseActivity::class.java).putExtra("workoutId", chosen.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            result.putString("result", "Imported ${parsed.imported} activities ($fresh new, $detailed detailed); persisted observations round-trip; repeated ZIP reuses every recording; activity page opened.")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("error", "${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun verifyAlerts() {
        // The app's actual warning settings and warning history remain untouched.
        val isolated = object : android.content.ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("smoke-test-$name", mode)
        }
        val preferences = glucowatch.alerts.AlertPreferences(isolated)
        preferences.prefs.edit().clear().commit()
        preferences.save(GlucoseAlertSettings(enabled = true))
        val manager = glucowatch.alerts.AlertNotifications.channel(isolated)
        fun active() = manager.activeNotifications.any { it.id == 31 }
        fun evaluate(value: Int, demo: Boolean = false, age: Long = 0) {
            glucowatch.alerts.AlertNotifications.evaluate(isolated, "synthetic-test-account", demo,
                listOf(GlucoseReading(System.currentTimeMillis() - age, value, Trend.None)), null,
                R.drawable.ic_launcher, GlucoseAlertsActivity::class.java)
        }
        fun expect(condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50)
            check(condition()) { "Android notification state did not match the expected warning." }
        }
        try {
            // Android 14+ requires ACCESS_NETWORK_STATE even to schedule a network job.
            // Keep this job separate from real warning jobs and cancel it before it can run.
            val scheduler = targetContext.getSystemService(android.app.job.JobScheduler::class.java)
            val testJob = 31017
            try {
                val job = android.app.job.JobInfo.Builder(testJob, android.content.ComponentName(targetContext, PhoneAlertJob::class.java))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(60_000).build()
                check(scheduler.schedule(job) == android.app.job.JobScheduler.RESULT_SUCCESS)
                check(scheduler.getPendingJob(testJob) != null)
            } finally { scheduler.cancel(testJob) }
            check(glucowatch.alerts.AlertNotifications.available(isolated)) { "Grant POST_NOTIFICATIONS for this explicit emulator test." }
            evaluate(250); expect { active() }
            val first = preferences.prefs.getString("history", "[]")
            android.os.SystemClock.sleep(5); evaluate(251)
            check(preferences.prefs.getString("history", "[]") == first) { "Repeat interval was ignored." }
            android.os.SystemClock.sleep(5); evaluate(50)
            expect { manager.activeNotifications.any { it.id == 31 && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "Urgent low glucose" } }
            check(org.json.JSONArray(preferences.prefs.getString("history", "[]")).length() == 2)
            evaluate(50, demo = true); expect { !active() }
            evaluate(50, age = 11 * 60_000L); expect { !active() }
        } finally { manager.cancel(31); preferences.prefs.edit().clear().commit() }
    }
}
