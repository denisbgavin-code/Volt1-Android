package com.volt1.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Finished takes in Music/Volt1 Recorder: open in the editor, rename,
 * share, delete. Files the app no longer owns (e.g. after reinstalling)
 * go through the system confirmation dialogs.
 */
class RecordingsActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var summaryView: TextView

    private var items: List<RecordingItem> = emptyList()
    private var loadGeneration = 0

    /** Action waiting for the system write/delete confirmation. */
    private var pendingAction: (() -> Unit)? = null

    private val deleted = ArrayList<String>()
    private val renamed = ArrayList<String>() // "uri\nnewName"

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_recording, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.name).text = item.displayName
            view.findViewById<TextView>(R.id.details).text = listOf(
                RecordingsCatalog.formatDuration(
                    RecordingsCatalog.effectiveDurationMs(item.durationMs, item.sizeBytes)
                ),
                RecordingsCatalog.formatSize(item.sizeBytes),
                RecordingsCatalog.formatDate(item.dateAddedSeconds)
            ).joinToString("  •  ")
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        listView = findViewById(R.id.list)
        summaryView = findViewById(R.id.summary)
        listView.adapter = adapter

        findViewById<Button>(R.id.back).setOnClickListener { finishWithChanges(null) }

        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { finishWithChanges(it) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { showActions(it) }
            true
        }

        // Back (key or gesture) just finishes; the result is kept current.
        publishResult(null)

        if (!hasReadPermission()) {
            requestPermissions(arrayOf(readPermission()), REQUEST_READ)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasReadPermission(): Boolean =
        checkSelfPermission(readPermission()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ) reload()
    }

    private fun reload() {
        val generation = ++loadGeneration
        Thread {
            val result = runCatching { query(contentResolver) }
            runOnUiThread {
                if (generation != loadGeneration || isDestroyed) return@runOnUiThread
                result
                    .onSuccess { list ->
                        items = list
                        adapter.notifyDataSetChanged()
                        val total = list.sumOf {
                            RecordingsCatalog.effectiveDurationMs(it.durationMs, it.sizeBytes)
                        }
                        summaryView.text = when {
                            list.isEmpty() && !hasReadPermission() ->
                                "Нет записей этого приложения. Разрешите доступ к аудио, " +
                                    "чтобы видеть записи прошлых установок."
                            list.isEmpty() -> "Записей пока нет"
                            else -> "${list.size} • ${RecordingsCatalog.formatDuration(total)} • " +
                                RecordingsCatalog.formatSize(list.sumOf { it.sizeBytes })
                        }
                    }
                    .onFailure { summaryView.text = it.message ?: "Не удалось прочитать список" }
            }
        }.apply { name = "Volt1-List-Recordings"; start() }
    }

    private fun showActions(item: RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle(item.displayName)
            .setItems(arrayOf("Открыть в редакторе", "Переименовать", "Поделиться", "Удалить")) { _, which ->
                when (which) {
                    0 -> finishWithChanges(item)
                    1 -> askRename(item)
                    2 -> share(item)
                    3 -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun askRename(item: RecordingItem) {
        val input = EditText(this).apply {
            setText(RecordingsCatalog.editableBase(item.displayName))
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("Новое имя")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = RecordingsCatalog.sanitizeRename(input.text.toString(), item.displayName)
                if (name == null) {
                    toast("Имя не изменено")
                } else {
                    runWithWriteAccess(item, isDelete = false) { rename(item, name) }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun rename(item: RecordingItem, name: String) {
        val updated = contentResolver.update(
            uriOf(item),
            ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) },
            null,
            null
        )
        if (updated <= 0) error("Не удалось переименовать")
        renamed += "${uriOf(item)}\n$name"
        publishResult(null)
        toast("Переименовано: $name")
        reload()
    }

    private fun confirmDelete(item: RecordingItem) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage(item.displayName)
            .setPositiveButton("Удалить") { _, _ ->
                runWithWriteAccess(item, isDelete = true) { delete(item) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun delete(item: RecordingItem) {
        // After a system delete request the row is already gone: 0 is fine then.
        contentResolver.delete(uriOf(item), null, null)
        deleted += uriOf(item).toString()
        publishResult(null)
        toast("Удалено: ${item.displayName}")
        reload()
    }

    /**
     * Runs [action]; if the file belongs to an earlier installation, asks
     * the system for the user's consent first and re-runs it afterwards.
     */
    private fun runWithWriteAccess(item: RecordingItem, isDelete: Boolean, action: () -> Unit) {
        try {
            action()
        } catch (e: SecurityException) {
            val sender = if (e is RecoverableSecurityException) {
                e.userAction.actionIntent.intentSender
            } else if (isDelete) {
                MediaStore.createDeleteRequest(contentResolver, listOf(uriOf(item))).intentSender
            } else {
                MediaStore.createWriteRequest(contentResolver, listOf(uriOf(item))).intentSender
            }
            // A system delete request deletes the file itself.
            pendingAction = if (isDelete && e !is RecoverableSecurityException) {
                {
                    deleted += uriOf(item).toString()
                    publishResult(null)
                    toast("Удалено: ${item.displayName}")
                    reload()
                }
            } else {
                action
            }
            startIntentSenderForResult(sender, REQUEST_WRITE, null, 0, 0, 0)
        } catch (t: Throwable) {
            toast(t.message ?: "Ошибка")
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WRITE) return
        val action = pendingAction
        pendingAction = null
        if (resultCode == RESULT_OK && action != null) {
            runCatching(action).onFailure { toast(it.message ?: "Ошибка") }
        }
    }

    private fun share(item: RecordingItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uriOf(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, item.displayName))
    }

    private fun finishWithChanges(open: RecordingItem?) {
        publishResult(open)
        finish()
    }

    private fun publishResult(open: RecordingItem?) {
        val data = Intent().apply {
            putStringArrayListExtra(EXTRA_DELETED, deleted)
            putStringArrayListExtra(EXTRA_RENAMED, renamed)
            if (open != null) {
                this.data = uriOf(open)
                putExtra(EXTRA_DISPLAY_NAME, open.displayName)
            }
        }
        setResult(RESULT_OK, data)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_DELETED = "deleted"
        const val EXTRA_RENAMED = "renamed"
        const val EXTRA_DISPLAY_NAME = "display_name"

        private const val REQUEST_READ = 7001
        private const val REQUEST_WRITE = 7002

        private val COLLECTION: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        fun uriOf(item: RecordingItem): Uri = ContentUris.withAppendedId(COLLECTION, item.id)

        fun query(resolver: ContentResolver): List<RecordingItem> {
            val list = ArrayList<RecordingItem>()
            resolver.query(
                COLLECTION,
                arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
                ),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("${RecordingsCatalog.RELATIVE_PATH_PREFIX}%"),
                null
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val date = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val displayName = c.getString(name)
                    if (!RecordingsCatalog.isListed(displayName)) continue
                    list += RecordingItem(
                        id = c.getLong(id),
                        displayName = displayName,
                        durationMs = if (c.isNull(duration)) 0L else c.getLong(duration),
                        sizeBytes = if (c.isNull(size)) 0L else c.getLong(size),
                        dateAddedSeconds = if (c.isNull(date)) 0L else c.getLong(date)
                    )
                }
            }
            return RecordingsCatalog.sorted(list)
        }
    }
}
