package com.szymo.mojachmura

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class ConnectionManager {

    private val TAG = "ConnectionManager"
    private val CONNECTION_TIMEOUT = 5000 // 5 sekund timeout na połączenie

    /**
     * Próbuje nawiązać połączenie z serwerem.
     * @return true, jeśli połączenie powiodło się, w przeciwnym razie false.
     */
    suspend fun testConnection(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), CONNECTION_TIMEOUT)
                Log.d(TAG, "Pomyślnie połączono z $ipAddress:$port")
                true
            } catch (e: IOException) {
                Log.e(TAG, "Błąd połączenia z $ipAddress:$port: ${e.message}")
                false
            } finally {
                try {
                    socket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zamykania socketu: ${e.message}")
                }
            }
        }
    }
}