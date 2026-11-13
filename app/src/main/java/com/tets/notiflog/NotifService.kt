package com.tets.notiflog

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class NotifService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDb.get(this).dao() }

    override fun onListenerDisconnected() {
        // minta sistem sambungkan lagi kalau terputus
        runCatching { requestRebind(android.content.ComponentName(this, NotifService::class.java)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val ex = n.extras
        val pkg = sbn.packageName
        val app = appLabel(pkg)

        // Ambil bitmap sekarang (sinkron) karena extras bisa berubah setelah callback
        @Suppress("DEPRECATION")
        val picture = ex.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)

        val chatTitle = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()

        val msgBundles = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val items = mutableListOf<Item>()

        if (msgBundles != null && msgBundles.isNotEmpty()) {
            val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(msgBundles)
            msgs.forEach { m ->
                items += Item(
                    sender = m.senderPerson?.name?.toString() ?: @Suppress("DEPRECATION") m.sender?.toString(),
                    text = m.text?.toString(),
                    time = if (m.timestamp > 0) m.timestamp else sbn.postTime,
                    dataUri = m.dataUri,
                )
            }
        } else {
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            items += Item(ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(), text, sbn.postTime, null)
        }
        if (items.isEmpty()) return

        scope.launch {
            val window = if (msgBundles != null) 1000L else 10_000L
            items.forEachIndexed { idx, it ->
                if (it.text.isNullOrBlank() && it.dataUri == null && picture == null) return@forEachIndexed
                if (dao.dupCount(pkg, chatTitle, it.text, it.time, window) > 0) return@forEachIndexed

                var media: String? = null
                // 1) media dari URI di pesan (kalau aplikasi mengizinkan)
                if (it.dataUri != null) media = copyUri(it.dataUri)
                // 2) gambar besar notifikasi -> dipasang ke pesan terbaru
                if (media == null && picture != null && idx == items.lastIndex) media = saveBitmap(picture)

                dao.insert(
                    NotifEntity(
                        pkg = pkg, appName = app, title = chatTitle, sender = it.sender,
                        text = it.text, postTime = it.time, mediaPath = media
                    )
                )
            }
        }
    }

    private class Item(val sender: String?, val text: String?, val time: Long, val dataUri: Uri?)

    private fun mediaDir() = File(filesDir, "media").also { it.mkdirs() }

    private fun saveBitmap(b: Bitmap): String? = runCatching {
        val f = File(mediaDir(), "${System.currentTimeMillis()}_${b.hashCode()}.jpg")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        f.absolutePath
    }.getOrNull()

    private fun copyUri(uri: Uri): String? = runCatching {
        val f = File(mediaDir(), "${System.currentTimeMillis()}_${uri.hashCode()}")
        contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        // pastikan bisa didecode sebagai gambar, kalau tidak tetap disimpan apa adanya
        f.absolutePath
    }.getOrNull()

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse {
        when (pkg) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            else -> pkg
        }
    }
}
