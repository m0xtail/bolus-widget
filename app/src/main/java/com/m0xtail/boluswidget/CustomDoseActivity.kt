package com.m0xtail.boluswidget

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CustomDoseActivity : AppCompatActivity() {

    private val receiver = BolusWidgetReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_dose)

        val bg = intent.getStringExtra("bg") ?: "--"
        val trend = intent.getStringExtra("trend") ?: ""
        val tvBg = findViewById<TextView>(R.id.tv_bg_dialog)
        tvBg.text = if (bg != "--") "$bg mg/dL $trend" else ""

        val etCustom = findViewById<EditText>(R.id.et_custom_dose)
        val btnLogCustom = findViewById<Button>(R.id.btn_log_custom)

        fun logAndFinish(dose: Double) {
            receiver.logDose(this, dose)
            Toast.makeText(this, "%.1fu logged".format(dose), Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btn_1).setOnClickListener { logAndFinish(1.0) }
        findViewById<Button>(R.id.btn_2).setOnClickListener { logAndFinish(2.0) }
        findViewById<Button>(R.id.btn_3).setOnClickListener { logAndFinish(3.0) }
        findViewById<Button>(R.id.btn_4).setOnClickListener { logAndFinish(4.0) }
        findViewById<Button>(R.id.btn_5).setOnClickListener { logAndFinish(5.0) }

        findViewById<Button>(R.id.btn_custom).setOnClickListener {
            etCustom.visibility = View.VISIBLE
            btnLogCustom.visibility = View.VISIBLE
            etCustom.requestFocus()
        }

        btnLogCustom.setOnClickListener {
            val dose = etCustom.text.toString().toDoubleOrNull()
            if (dose != null && dose > 0) {
                logAndFinish(dose)
            } else {
                etCustom.error = "Enter a valid dose"
            }
        }

        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }
}
