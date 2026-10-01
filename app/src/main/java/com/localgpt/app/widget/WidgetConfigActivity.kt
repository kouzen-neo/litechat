package com.localgpt.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import com.localgpt.app.R

/**
 * "Ask LiteChat" widget configuration screen.
 *
 * The user fills in prefill text that auto-fills the chat box
 * when the widget button is tapped. Left empty = opens a normal chat.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cancel by default: the widget is not added if the user backs out.
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_config)

        val prefs = getSharedPreferences(
            LiteChatWidgetProvider.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val input = findViewById<EditText>(R.id.config_prefill_input)
        input.setText(prefs.getString(LiteChatWidgetProvider.prefillKey(appWidgetId), ""))

        findViewById<Button>(R.id.config_save_button).setOnClickListener {
            prefs.edit()
                .putString(
                    LiteChatWidgetProvider.prefillKey(appWidgetId),
                    input.text.toString().trim(),
                )
                .apply()
            LiteChatWidgetProvider.updateWidget(
                this,
                AppWidgetManager.getInstance(this),
                appWidgetId,
            )
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}
