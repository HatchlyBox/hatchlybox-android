package skill.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object Notifications {
    const val CHANNEL = "reminders"

    // Once, when the app starts. Android 8+ shows nothing without a channel.
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Checked just above with allowed(); a tap opens the app.
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, text: String) {
        if (!allowed(context)) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}

// Runs in the background when its time comes, even if the app is closed.
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Notifications.show(applicationContext, inputData.getString("title") ?: "Reminder", inputData.getString("text") ?: "")
        return Result.success()
    }
}

object Reminders {
    fun remindIn(context: Context, minutes: Long, title: String, text: String) {
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf("title" to title, "text" to text))
            .addTag("reminder")
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    // One daily reminder; calling it again replaces it.
    fun daily(context: Context, title: String, text: String) {
        val work = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf("title" to title, "text" to text))
            .addTag("reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-reminder", ExistingPeriodicWorkPolicy.UPDATE, work)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag("reminder")
    }
}

// Asks for the notification permission when called (Android 13+); returns whether notifications may show.
@Composable
fun rememberNotificationPermission(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Notifications.allowed(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val ask: () -> Unit = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    return granted to ask
}

@Composable
fun RemindersScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (granted, ask) = rememberNotificationPermission()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!granted) {
            Text("Reminders are off: allow notifications to get them.")
            Button(onClick = ask) { Text("Allow notifications") }
        }
        Button(onClick = { Reminders.remindIn(context, 20, "Time for a break", "Stand up and stretch") }, enabled = granted) {
            Text("Remind me in 20 minutes")
        }
        Button(onClick = { Reminders.daily(context, "Daily check-in", "How did today go?") }, enabled = granted) {
            Text("Remind me every day")
        }
        Button(onClick = { Reminders.cancelAll(context) }) { Text("Cancel reminders") }
    }
}
