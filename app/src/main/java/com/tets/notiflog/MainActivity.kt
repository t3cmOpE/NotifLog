package com.tets.notiflog

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen() {
        val dao = remember { AppDb.get(this).dao() }
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf("") }
        var pkg by remember { mutableStateOf<String?>(null) }
        var confirmClear by remember { mutableStateOf(false) }
        val list by remember(q, pkg) { dao.observe(q, pkg) }.collectAsState(emptyList())
        val apps by remember { dao.apps() }.collectAsState(emptyList())

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("NotifLog", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { confirmClear = true }) { Text("Hapus semua") }
            }

            if (!enabled) {
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Akses notifikasi belum aktif", fontWeight = FontWeight.Bold)
                        Text(
                            "Aktifkan NotifLog di daftar akses notifikasi. Kalau APK ini di-install manual " +
                            "dan Android 13+ menolak, buka Info Aplikasi → ⋮ → Izinkan setelan terbatas."
                        )
                        Button(onClick = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Buka pengaturan") }
                    }
                }
            }

            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                label = { Text("Cari pesan / pengirim") }, modifier = Modifier.fillMaxWidth()
            )

            LazyRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = pkg == null, onClick = { pkg = null }, label = { Text("Semua") }) }
                items(apps) { a ->
                    FilterChip(
                        selected = pkg == a.pkg,
                        onClick = { pkg = if (pkg == a.pkg) null else a.pkg },
                        label = { Text("${a.appName} (${a.count})") }
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${n.appName} • ${fmt.format(Date(n.postTime))}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (!n.title.isNullOrBlank()) Text(n.title, fontWeight = FontWeight.Bold)
                            if (!n.sender.isNullOrBlank() && n.sender != n.title)
                                Text(n.sender, style = MaterialTheme.typography.labelMedium)
                            if (!n.text.isNullOrBlank()) Text(n.text)
                            n.mediaPath?.let { Thumb(it) }
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    dao.mediaOf(n.id)?.let { File(it).delete() }
                                    dao.delete(n.id)
                                }
                            }) { Text("Hapus") }
                        }
                    }
                }
            }
        }

        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Hapus semua notifikasi?") },
            text = { Text("Semua teks dan media yang tersimpan akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch(Dispatchers.IO) {
                        File(filesDir, "media").deleteRecursively()
                        dao.clear()
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } }
        )
    }

    @Composable
    fun Thumb(path: String) {
        val bmp by produceState<ImageBitmap?>(null, path) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(path, o)?.asImageBitmap()
                }.getOrNull()
            }
        }
        bmp?.let {
            Image(
                it, null,
                Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 6.dp),
                contentScale = ContentScale.Fit
            )
        }
    }

    private val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
}
