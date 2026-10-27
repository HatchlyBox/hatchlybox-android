package skill.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

// What the widget shows: read it here from the app's own data (Room or DataStore).
class HomeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val count = loadCount(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("$count", style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    Text("done today", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
                    if (open != null) {
                        Spacer(GlanceModifier.height(8.dp))
                        Button(text = "Open", onClick = actionStartActivity(open))
                    }
                }
            }
        }
    }

    // Replace with the app's data, e.g. a DataStore value or a Room count.
    private fun loadCount(context: Context): Int =
        context.getSharedPreferences("widget", Context.MODE_PRIVATE).getInt("count", 0)

    companion object {
        // Call after the app changes what the widget shows.
        suspend fun refresh(context: Context) = HomeWidget().updateAll(context)
    }
}

// Android finds the widget through this receiver (declared in the manifest).
class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeWidget()
}
