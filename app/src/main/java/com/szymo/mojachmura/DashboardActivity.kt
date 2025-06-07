package com.szymo.mojachmura

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.szymo.mojachmura.databinding.ActivityDashboardBinding
import com.szymo.mojachmura.databinding.ItemFolderBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val PREFS_NAME = "MyCloudPrefs"
    private val SELECTED_FOLDERS_KEY = "selectedFolderUris"

    private lateinit var folderAdapter: FolderAdapter
    private val selectedFolders: MutableList<Uri> = mutableListOf()

    private val STORAGE_PERMISSION_REQUEST_CODE = 100

    private val selectFolderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Zachowaj stałe uprawnienia do URI
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)

                addSelectedFolder(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val connectedIp = intent.getStringExtra("CONNECTED_IP")

        if (connectedIp != null) {
            binding.tvConnectedIpDashboard.text = "Połączono z: $connectedIp"
        } else {
            binding.tvConnectedIpDashboard.text = "Połączono (brak danych IP)."
        }

        setupRecyclerView()

        binding.btnSelectFolder.setOnClickListener {
            checkAndRequestStoragePermission()
        }

        loadSelectedFolders()
    }

    private fun setupRecyclerView() {
        folderAdapter = FolderAdapter(selectedFolders) { uri ->
            removeSelectedFolder(uri)
        }
        binding.rvSelectedFolders.apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            adapter = folderAdapter
        }
    }

    private fun addSelectedFolder(uri: Uri) {
        if (uri !in selectedFolders) {
            selectedFolders.add(uri)
            folderAdapter.notifyItemInserted(selectedFolders.size - 1)
            saveSelectedFolders()
        }
    }

    private fun removeSelectedFolder(uri: Uri) {
        val position = selectedFolders.indexOf(uri)
        if (position != -1) {
            selectedFolders.removeAt(position)
            folderAdapter.notifyItemRemoved(position)
            // Zwolnij stałe uprawnienia do URI, jeśli już nie jest potrzebne
            try {
                contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
            saveSelectedFolders()
        }
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                openFolderChooser()
            } else {
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Wymagane uprawnienia")
                builder.setMessage("Aplikacja potrzebuje dostępu do zarządzania plikami, aby móc wybrać folder. Przejdź do ustawień i włącz to uprawnienie.")
                builder.setPositiveButton("Przejdź do ustawień") { dialog, which ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                        startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE)
                    } catch (e: Exception) {
                        val intent = Intent()
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE)
                    }
                }
                builder.setNegativeButton("Anuluj") { dialog, which -> }
                builder.show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                openFolderChooser()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openFolderChooser()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Uprawnienia wymagane")
                    .setMessage("Aby wybrać folder, aplikacja potrzebuje uprawnień do pamięci.")
                    .setPositiveButton("OK") { dialog, which -> }
                    .show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    openFolderChooser()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Uprawnienia wymagane")
                        .setMessage("Aby wybrać folder, aplikacja potrzebuje uprawnień do zarządzania plikami.")
                        .setPositiveButton("OK") { dialog, which -> }
                        .show()
                }
            }
        }
    }

    private fun openFolderChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Brak obsługi wielokrotnego wyboru w ACTION_OPEN_DOCUMENT_TREE bezpośrednio.
            // Użytkownik będzie musiał wybierać foldery pojedynczo.
            // Jeśli potrzebujesz prawdziwej wielokrotnej selekcji plików/folderów,
            // będziesz musiał zaimplementować własny selektor plików lub użyć zewnętrznej biblioteki.
        }
        selectFolderLauncher.launch(intent)
    }

    private fun saveSelectedFolders() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(selectedFolders.map { it.toString() }) // Zapisz URI jako stringi
        with(sharedPrefs.edit()) {
            putString(SELECTED_FOLDERS_KEY, json)
            apply()
        }
    }

    private fun loadSelectedFolders() {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPrefs.getString(SELECTED_FOLDERS_KEY, null)
        if (json != null) {
            val type = object : TypeToken<List<String>>() {}.type
            val uriStrings: List<String> = gson.fromJson(json, type)
            selectedFolders.clear()
            uriStrings.forEach { uriString ->
                val uri = Uri.parse(uriString)
                // Sprawdź, czy uprawnienia do URI są nadal ważne
                try {
                    contentResolver.persistedUriPermissions.forEach { permission ->
                        if (permission.uri == uri && permission.isReadPermission && permission.isWritePermission) {
                            selectedFolders.add(uri)
                            return@forEach
                        }
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
            folderAdapter.notifyDataSetChanged()
        }
    }

    // Adapter do RecyclerView
    class FolderAdapter(private val folders: MutableList<Uri>, private val onRemoveClick: (Uri) -> Unit) :
        RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

        class FolderViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
            val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return FolderViewHolder(binding)
        }

        override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
            val uri = folders[position]
            // Spróbuj uzyskać nazwę wyświetlaną folderu
            var folderDisplayName: String? = null
            try {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                val split = documentId.split(":")
                if (split.size > 1) {
                    folderDisplayName = split[1]
                } else {
                    folderDisplayName = split[0]
                }
                if (folderDisplayName == "primary") {
                    folderDisplayName = "Pamięć wewnętrzna"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                folderDisplayName = uri.lastPathSegment ?: "Nieznany folder"
            }

            holder.binding.tvFolderPath.text = folderDisplayName

            holder.binding.btnRemoveFolder.setOnClickListener {
                onRemoveClick(uri)
            }
        }

        override fun getItemCount(): Int = folders.size
    }
}