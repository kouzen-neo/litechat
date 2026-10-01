package com.localgpt.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.localgpt.app.MainActivity
import com.localgpt.app.R

/**
 * Home screen widget "Tanya LiteChat".
 *
 * Satu tombol membuka [MainActivity] dengan extra [EXTRA_PREFILL_PROMPT]
 * berisi teks prefill (diatur lewat [WidgetConfigActivity]), sehingga pengguna
 * tinggal menekan kirim di layar chat.
 */
class LiteChatWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            for (widgetId in appWidgetIds) remove(prefillKey(widgetId))
            apply()
        }
    }

    companion object {
        /**
         * Nama extra Intent (String) yang dikirim ke MainActivity berisi teks
         * prefill untuk kolom chat. Nilai: "prefill_prompt".
         */
        const val EXTRA_PREFILL_PROMPT = "prefill_prompt"

        const val PREFS_NAME = "litechat_widget_prefs"

        fun prefillKey(appWidgetId: Int): String = "prefill_$appWidgetId"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefill = prefs.getString(prefillKey(appWidgetId), "").orEmpty()

            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PREFILL_PROMPT, prefill)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_litechat)
            views.setOnClickPendingIntent(R.id.widget_ask_button, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
