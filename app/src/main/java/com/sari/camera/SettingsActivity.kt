package com.sari.camera

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("settings", 0)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "SARI Camera"
            textSize = 28f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "AI, capture and performance"
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, 4, 0, 24)
        })

        fun sw(label: String, key: String, default: Boolean) {
            root.addView(Switch(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = p.getBoolean(key, default)
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, v -> p.edit().putBoolean(key, v).apply() }
            })
        }

        sw("AI enhancement", "ai", true)
        sw("Save RAW with RAW mode", "raw", true)
        sw("Video stabilization", "stabilization", true)
        sw("Thermal adaptation", "thermal", true)
        sw("Keep original JPEG", "saveOriginal", false)

        root.addView(TextView(this).apply {
            text = "Astro duration"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 4)
        })

        val astroLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.START
        }
        root.addView(astroLabel)

        val seek = SeekBar(this).apply {
            max = 7
            progress = (p.getInt("astroMinutes", 4) - 1).coerceIn(0, 7)
            setPadding(0, 0, 0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val minutes = value + 1
                    astroLabel.text = "$minutes minute${if (minutes == 1) "" else "s"}"
                    if (fromUser) p.edit().putInt("astroMinutes", minutes).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        root.addView(seek)
        astroLabel.text = "${p.getInt("astroMinutes", 4)} minutes"

        root.addView(TextView(this).apply {
            text = "6x–13x uses tiled multi-frame AI. 13x–20x uses stronger NCNN/Vulkan reconstruction when available. Faces remain blended with the measured image to preserve identity and real skin texture."
            textSize = 13f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, 20, 0, 0)
        })

        setContentView(root)
    }
}
