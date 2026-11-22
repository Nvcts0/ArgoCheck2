package com.driver.argocheck

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("argo_prefs", Context.MODE_PRIVATE)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
        }
        scroll.addView(root)

        fun addTitle(text: String) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 18f
                setPadding(0, 24, 0, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }

        fun addInput(hint: String, defaultVal: String, isDecimal: Boolean = false): EditText {
            val tv = TextView(this).apply { text = hint; textSize = 13f }
            val et = EditText(this).apply {
                setText(defaultVal)
                inputType = if (isDecimal) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
            }
            root.addView(tv)
            root.addView(et)
            return et
        }

        addTitle("1. Profil Energi Kendaraan")
        val etHargaEnergi = addInput("Harga BBM (Rp/L) atau Listrik (Rp/kWh)", prefs.getFloat("hargaEnergi", 10000f).toInt().toString())
        val etEfisiensi = addInput("Konsumsi (km/L atau km/kWh)", prefs.getFloat("efisiensiEnergi", 14.0f).toString(), true)

        addTitle("2. Target Keuangan & Pengeluaran")
        val etTargetBulanan = addInput("Target Bersih Bulanan (Rp)", prefs.getFloat("targetBulanan", 7500000f).toLong().toString())
        val etPengeluaranHarian = addInput("Pengeluaran Harian: Makan/Kopi/Kuota (Rp)", prefs.getFloat("pengeluaranHarian", 50000f).toLong().toString())
        val etHariKerja = addInput("Hari Kerja per Bulan (Hari)", prefs.getInt("hariKerjaBulan", 25).toString())
        val etJamKerja = addInput("Jam Kerja per Hari (Jam)", prefs.getFloat("jamKerjaHari", 8.0f).toString(), true)
        val etMinMarginKm = addInput("Batas Minimal Margin per Km (Rp)", prefs.getFloat("minMarginPerKm", 2000f).toInt().toString())

        val btnSimpan = Button(this).apply {
            text = "Simpan Pengaturan"
            setOnClickListener {
                prefs.edit().apply {
                    putFloat("hargaEnergi", etHargaEnergi.text.toString().toFloatOrNull() ?: 10000f)
                    putFloat("efisiensiEnergi", etEfisiensi.text.toString().toFloatOrNull() ?: 14f)
                    putFloat("targetBulanan", etTargetBulanan.text.toString().toFloatOrNull() ?: 7500000f)
                    putFloat("pengeluaranHarian", etPengeluaranHarian.text.toString().toFloatOrNull() ?: 50000f)
                    putInt("hariKerjaBulan", etHariKerja.text.toString().toIntOrNull() ?: 25)
                    putFloat("jamKerjaHari", etJamKerja.text.toString().toFloatOrNull() ?: 8f)
                    putFloat("minMarginPerKm", etMinMarginKm.text.toString().toFloatOrNull() ?: 2000f)
                    apply()
                }
                Toast.makeText(this@MainActivity, "Pengaturan disimpan!", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnSimpan)

        addTitle("3. Aktivasi Izin & Layanan")
        val btnOverlay = Button(this).apply {
            text = "1. Izinkan Tampil di Atas Aplikasi Lain"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Izin Overlay sudah aktif", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(btnOverlay)

        val btnAcc = Button(this).apply {
            text = "2. Aktifkan Pembaca Layar (Accessibility)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(btnAcc)

        val btnStart = Button(this).apply {
            text = "3. Mulai Siaga Floating Widget"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Aktifkan izin Overlay dulu!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = Intent(this@MainActivity, FloatingOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this@MainActivity, "ArgoCheck Aktif & Siaga!", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnStart)

        setContentView(scroll)
    }
}
