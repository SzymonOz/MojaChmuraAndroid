package com.szymo.mojachmura

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.szymo.mojachmura.databinding.ActivityMainBinding // Generowany automatycznie, upewnij się, że masz w build.gradle: buildFeatures { viewBinding true }
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val connectionManager = ConnectionManager()
    private val PREFS_NAME = "ConnectionPrefs"
    private val KEY_IP_ADDRESS = "ip_address"
    private val KEY_PAIRING_CODE = "pairing_code"
    private val KEY_LAST_WIFI_SSID = "last_wifi_ssid"
    private val SERVER_PORT = 5000 // Port Twojego serwera Pythona

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedData()
        updateSavedInfoDisplay()

        binding.btnConnect.setOnClickListener {
            connectToServer()
        }

        // Automatyczne łączenie przy starcie, jeśli są zapisane dane i ta sama sieć
        checkAndAutoConnect()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset_data -> {
                showResetConfirmationDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.etIpAddress.setText(prefs.getString(KEY_IP_ADDRESS, ""))
        binding.etPairingCode.setText(prefs.getString(KEY_PAIRING_CODE, ""))
    }

    private fun saveConnectionData(ipAddress: String, pairingCode: String, wifiSsid: String?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            putString(KEY_IP_ADDRESS, ipAddress)
            putString(KEY_PAIRING_CODE, pairingCode)
            putString(KEY_LAST_WIFI_SSID, wifiSsid)
            apply()
        }
        updateSavedInfoDisplay()
    }

    private fun clearSavedData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            clear()
            apply()
        }
        binding.etIpAddress.setText("")
        binding.etPairingCode.setText("")
        updateSavedInfoDisplay()
        Toast.makeText(this, "Dane logowania zostały zresetowane.", Toast.LENGTH_SHORT).show()
    }

    private fun updateSavedInfoDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_IP_ADDRESS, "")
        val savedCode = prefs.getString(KEY_PAIRING_CODE, "")
        val savedSsid = prefs.getString(KEY_LAST_WIFI_SSID, "")

        if (savedIp.isNullOrEmpty() || savedCode.isNullOrEmpty()) {
            binding.tvSavedInfo.text = "Zapisane dane: Brak"
        } else {
            binding.tvSavedInfo.text = "Zapisane dane: IP: $savedIp, Kod: $savedCode, Sieć: ${savedSsid ?: "Nieznana"}"
        }
    }

    private fun getCurrentWifiSsid(): String? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // Dla Android 10+ wymagana jest lokalizacja do uzyskania SSID
                // Możesz użyć WifiInfo.getSSID() ale będzie ono zawarte w cudzysłowach ("SSID")
                // Lepszym rozwiązaniem jest użycie NetworkRequest i NetworkCallback
                // lub po prostu WifiManager.getConnectionInfo().ssid dla Android < 10
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                // Usunięcie cudzysłowów z SSID
                return wifiInfo.ssid?.replace("\"", "")
            }
        } else {
            // Starsze wersje Androida
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                return wifiInfo.ssid?.replace("\"", "")
            }
        }
        return null
    }

    private fun checkAndAutoConnect() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(KEY_IP_ADDRESS, null)
        val savedCode = prefs.getString(KEY_PAIRING_CODE, null)
        val savedSsid = prefs.getString(KEY_LAST_WIFI_SSID, null)

        if (savedIp != null && savedCode != null) {
            val currentSsid = getCurrentWifiSsid()
            if (currentSsid != null && currentSsid == savedSsid) {
                binding.tvConnectionStatus.text = "Status połączenia: Próba automatycznego połączenia..."
                lifecycleScope.launch {
                    val isConnected = connectionManager.testConnection(savedIp, SERVER_PORT)
                    runOnUiThread {
                        if (isConnected) {
                            binding.tvConnectionStatus.text = "Status połączenia: Połączono automatycznie!"
                            Toast.makeText(this@MainActivity, "Automatyczne połączenie udane!", Toast.LENGTH_SHORT).show()
                            // --- NOWY KOD TUTAJ (dla automatycznego połączenia) ---
                            val intent = Intent(this@MainActivity, DashboardActivity::class.java)
                            intent.putExtra("CONNECTED_IP", savedIp)
                            startActivity(intent)
                            finish() // Zakończenie MainActivity
                            // --- KONIEC NOWEGO KODU ---
                        } else {
                            binding.tvConnectionStatus.text = "Status połączenia: Brak połączenia (automatyczne)."
                            Toast.makeText(this@MainActivity, "Automatyczne połączenie nieudane.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                binding.tvConnectionStatus.text = "Status połączenia: Brak automatycznego połączenia (inna sieć)."
            }
        }
    }

    private fun connectToServer() {
        val ipAddress = binding.etIpAddress.text.toString().trim()
        val pairingCode = binding.etPairingCode.text.toString().trim()

        if (ipAddress.isEmpty()) {
            binding.tilIpAddress.error = "Adres IP nie może być pusty"
            return
        }
        if (pairingCode.isEmpty()) {
            binding.tilPairingCode.error = "Kod parowania nie może być pusty"
            return
        }

        binding.tilIpAddress.error = null
        binding.tilPairingCode.error = null

        val currentSsid = getCurrentWifiSsid()

        binding.tvConnectionStatus.text = "Status połączenia: Łączenie z $ipAddress..."
        lifecycleScope.launch {
            val isConnected = connectionManager.testConnection(ipAddress, SERVER_PORT)
            runOnUiThread {
                if (isConnected) {
                    binding.tvConnectionStatus.text = "Status połączenia: Połączono!"
                    Toast.makeText(this@MainActivity, "Połączono pomyślnie!", Toast.LENGTH_SHORT).show()
                    saveConnectionData(ipAddress, pairingCode, currentSsid)
                    // --- NOWY KOD TUTAJ ---
                    // Tworzenie intencji do uruchomienia DashboardActivity
                    val intent = Intent(this@MainActivity, DashboardActivity::class.java)
                    // Opcjonalnie: Przekazanie adresu IP do nowej aktywności
                    intent.putExtra("CONNECTED_IP", ipAddress)
                    startActivity(intent) // Uruchomienie nowej aktywności
                    finish() // Opcjonalnie: Zakończenie MainActivity, aby użytkownik nie mógł do niej wrócić przyciskiem wstecz
                    // --- KONIEC NOWEGO KODU ---
                } else {
                    binding.tvConnectionStatus.text = "Status połączenia: Brak połączenia."
                    Toast.makeText(this@MainActivity, "Błąd połączenia. Sprawdź IP i upewnij się, że serwer działa.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Resetuj dane")
            .setMessage("Czy na pewno chcesz zresetować wszystkie zapisane dane logowania?")
            .setPositiveButton("Tak") { dialog, _ ->
                clearSavedData()
                dialog.dismiss()
            }
            .setNegativeButton("Nie") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}