package com.m0xtail.boluswidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.max

const val PROXY_URL = "https://dexcom-proxy-py.onrender.com"
const val ACTION_LOG_DOSE = "com.m0xtail.boluswidget.LOG_DOSE"
const val ACTION_REFRESH = "com.m0xtail.boluswidget.REFRESH"
const val EXTRA_DOSE = "dose"

// Novolog IOB model: full dose until peak (75min), linear decay to 0 at 195min
const val PEAK_MIN = 75.0
const val DURATION_MIN = 195.0

fun calcIOB(entries: List<Pair<Double, Long>>): Double {
    val now = System.currentTimeMillis()
    return entries.sumOf { (dose, ts) ->
        val ageMin = (now - ts) / 60000.0
        when {
            ageMin < 0 || ageMin >= DURATION_MIN -> 0.0
            ageMin < PEAK_MIN -> dose
            else -> dose * (1.0 - (ageMin - PEAK_MIN) / (DURATION_MIN - PEAK_MIN))
        }
    }
}

class BolusWidgetReceiver : AppWidgetProvider() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, mgr, it) }
        refreshData(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LOG_DOSE -> {
                val dose = intent.getDoubleExtra(EXTRA_DOSE, -1.0)
                if (dose > 0) logDose(context, dose)
            }
            ACTION_REFRESH -> refreshData(context)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, BolusWidgetReceiver::class.java))
                ids.forEach { updateWidget(context, mgr, it) }
            }
        }
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val prefs = context.getSharedPreferences("bolus_widget", Context.MODE_PRIVATE)
        val bg = prefs.getString("bg", "--") ?: "--"
        val trend = prefs.getString("trend", "") ?: ""
        val iob = prefs.getFloat("iob", 0f)

        val views = RemoteViews(context.packageName, R.layout.bolus_widget)
        views.setTextViewText(R.id.tv_bg, bg)
        views.setTextViewText(R.id.tv_trend, trend)
        views.setTextViewText(R.id.tv_iob, "iob %.1fu".format(iob))

        // Tap widget → open dose dialog
        val dialogIntent = Intent(context, CustomDoseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("bg", bg)
            putExtra("trend", trend)
        }
        val pi = PendingIntent.getActivity(context, 0, dialogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.tv_tap, pi)
        views.setOnClickPendingIntent(R.id.tv_bg, pi)
        views.setOnClickPendingIntent(R.id.tv_iob, pi)

        mgr.updateAppWidget(id, views)
    }

    private fun refreshData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch BG
                val bgReq = Request.Builder().url("$PROXY_URL/bg").build()
                val bgResp = client.newCall(bgReq).execute()
                if (bgResp.isSuccessful) {
                    val json = JSONObject(bgResp.body!!.string())
                    val bg = json.getInt("value").toString()
                    val trend = json.getString("trend")
                    context.getSharedPreferences("bolus_widget", Context.MODE_PRIVATE)
                        .edit().putString("bg", bg).putString("trend", trend).apply()
                }

                // Fetch entries for IOB
                val entriesReq = Request.Builder().url("$PROXY_URL/entries").build()
                val entriesResp = client.newCall(entriesReq).execute()
                if (entriesResp.isSuccessful) {
                    val arr = JSONArray(entriesResp.body!!.string())
                    val entries = mutableListOf<Pair<Double, Long>>()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val dose = e.getDouble("dose")
                        val ts = parseISO(e.getString("ts"))
                        if (ts > 0) entries.add(Pair(dose, ts))
                    }
                    val iob = calcIOB(entries).toFloat()
                    context.getSharedPreferences("bolus_widget", Context.MODE_PRIVATE)
                        .edit().putFloat("iob", iob).apply()
                }

                withContext(Dispatchers.Main) {
                    val mgr = AppWidgetManager.getInstance(context)
                    val ids = mgr.getAppWidgetIds(ComponentName(context, BolusWidgetReceiver::class.java))
                    ids.forEach { updateWidget(context, mgr, it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun logDose(context: Context, dose: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bg = context.getSharedPreferences("bolus_widget", Context.MODE_PRIVATE)
                    .getString("bg", null)?.toDoubleOrNull()
                val body = JSONObject().apply {
                    put("id", System.currentTimeMillis())
                    put("dose", dose)
                    if (bg != null) put("bg", bg)
                    put("note", "")
                    put("ts", java.time.Instant.now().toString())
                }.toString()
                val req = Request.Builder()
                    .url("$PROXY_URL/entries")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
                refreshData(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseISO(ts: String): Long {
        return try {
            java.time.Instant.parse(if (ts.endsWith("Z") || ts.contains("+")) ts else "${ts}Z").toEpochMilli()
        } catch (e: Exception) { 0L }
    }
}
