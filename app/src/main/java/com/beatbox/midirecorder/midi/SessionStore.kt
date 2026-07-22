package com.beatbox.midirecorder.midi

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Sauvegarde et chargement des prises (sessions) en JSON, dans le stockage privé de l'app. */
object SessionStore {

    fun dir(base: File): File = File(base, "sessions").apply { mkdirs() }

    fun save(base: File, name: String, events: List<RecordedEvent>, length: Long, bpm: Int): File {
        val o = JSONObject()
        o.put("bpm", bpm)
        o.put("length", length)
        val arr = JSONArray()
        for (e in events) {
            val a = JSONArray()
            a.put(e.timeNanos); a.put(e.status); a.put(e.data1); a.put(e.data2)
            arr.put(a)
        }
        o.put("events", arr)
        val safe = name.trim().ifEmpty { "session" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val f = File(dir(base), "$safe.json")
        f.writeText(o.toString())
        return f
    }

    fun list(base: File): List<File> =
        dir(base).listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    data class Loaded(val events: List<RecordedEvent>, val length: Long, val bpm: Int)

    fun load(f: File): Loaded {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("events")
        val evs = ArrayList<RecordedEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            evs.add(RecordedEvent(a.getLong(0), a.getInt(1), a.getInt(2), a.getInt(3)))
        }
        return Loaded(evs, o.getLong("length"), o.getInt("bpm"))
    }

    // ----- Setlist (mode concert) -----

    private fun setlistFile(base: File): File = File(base, "setlist.json")

    /** Liste ordonnée des noms de sessions de la setlist. */
    fun loadSetlist(base: File): List<String> = runCatching {
        val arr = JSONArray(setlistFile(base).readText())
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun saveSetlist(base: File, names: List<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        setlistFile(base).writeText(arr.toString())
    }

    fun addToSetlist(base: File, name: String) {
        val cur = loadSetlist(base)
        if (name !in cur) saveSetlist(base, cur + name)
    }

    fun removeFromSetlist(base: File, name: String) {
        saveSetlist(base, loadSetlist(base).filter { it != name })
    }

    // ----- Song mode (structure d'un morceau : parties + répétitions) -----

    data class SongPart(val name: String, val repeats: Int)

    private fun songFile(base: File): File = File(base, "song.json")

    fun loadSong(base: File): List<SongPart> = runCatching {
        val arr = JSONArray(songFile(base).readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SongPart(o.getString("n"), o.optInt("r", 1))
        }
    }.getOrDefault(emptyList())

    fun saveSong(base: File, parts: List<SongPart>) {
        val arr = JSONArray()
        parts.forEach { p ->
            arr.put(JSONObject().put("n", p.name).put("r", p.repeats))
        }
        songFile(base).writeText(arr.toString())
    }

    // ----- Dumps SysEx (sons/patterns de la machine) -----

    fun syxDir(base: File): File = File(base, "sysex").apply { mkdirs() }

    fun saveSyx(base: File, name: String, bytes: ByteArray): File {
        val safe = name.trim().ifEmpty { "dump" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val f = File(syxDir(base), "$safe.syx")
        f.writeBytes(bytes)
        return f
    }

    fun listSyx(base: File): List<File> =
        syxDir(base).listFiles { f -> f.extension == "syx" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ----- Sauvegarde totale (sessions + audio + sysex + setlist) -----

    fun backupAll(filesDir: File, audioDir: File, out: File) {
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            fun put(f: File, path: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            dir(filesDir).listFiles()?.forEach { put(it, "sessions/${it.name}") }
            syxDir(filesDir).listFiles()?.forEach { put(it, "sysex/${it.name}") }
            File(filesDir, "setlist.json").takeIf { it.exists() }?.let { put(it, "setlist.json") }
            File(filesDir, "song.json").takeIf { it.exists() }?.let { put(it, "song.json") }
            audioDir.listFiles { f -> f.extension == "wav" }?.forEach { put(it, "audio/${it.name}") }
        }
    }

    /** Restaure une sauvegarde totale ; renvoie le nombre de fichiers restaurés. */
    fun restoreAll(filesDir: File, audioDir: File, input: java.io.InputStream): Int {
        var count = 0
        java.util.zip.ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    val fileName = name.substringAfterLast('/')
                    val dst = when {
                        name.startsWith("sessions/") -> File(dir(filesDir), fileName)
                        name.startsWith("sysex/") -> File(syxDir(filesDir), fileName)
                        name == "setlist.json" -> File(filesDir, "setlist.json")
                        name == "song.json" -> File(filesDir, "song.json")
                        name.startsWith("audio/") -> File(audioDir, fileName)
                        else -> null
                    }
                    // Garde-fou : pas de noms dangereux
                    if (dst != null && !fileName.contains("..") && fileName.isNotBlank()) {
                        dst.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return count
    }
}
