package com.beatbox.midirecorder.midi

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.Equalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Enregistre le son de la machine (via l'interface audio USB, qui devient l'entrée
 * par défaut du téléphone) en WAV, et le relit avec un égaliseur multibande.
 */
class AudioEngine(private val context: android.content.Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager =
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

    /** Entrées audio disponibles (micro interne, interface USB…). */
    val inputs = MutableStateFlow<List<android.media.AudioDeviceInfo>>(emptyList())
    /** Entrée choisie ; null = automatique (USB en priorité). */
    val selectedInputId = MutableStateFlow<Int?>(null)
    /** Nom de l'entrée réellement utilisée pour la dernière prise. */
    val activeInputName = MutableStateFlow<String?>(null)

    fun refreshInputs() {
        inputs.value = audioManager
            .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .toList()
    }

    fun inputLabel(d: android.media.AudioDeviceInfo): String {
        val type = when (d.type) {
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micro interne"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Casque filaire"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Entrée ${d.type}"
        }
        val name = d.productName?.toString()?.take(18) ?: ""
        return if (name.isNotBlank() && !name.contains("Built", true)) "$type · $name" else type
    }

    private fun isUsb(d: android.media.AudioDeviceInfo): Boolean = d.type in listOf(
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    /** L'entrée à utiliser : celle choisie, sinon l'USB si présente, sinon défaut système. */
    private fun pickInput(): android.media.AudioDeviceInfo? {
        refreshInputs()
        val list = inputs.value
        selectedInputId.value?.let { id -> list.find { it.id == id }?.let { return it } }
        return list.firstOrNull { isUsb(it) }
    }

    val isRecording = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    /** Niveau d'entrée 0..1 pour le vumètre. */
    val level = MutableStateFlow(0f)
    val lastFile = MutableStateFlow<File?>(null)
    val statusMessage = MutableStateFlow<String?>(null)

    /** Bandes de l'égaliseur : libellés (Hz) et niveaux courants (millibels). */
    val bandLabels = MutableStateFlow<List<String>>(emptyList())
    val bandLevels = MutableStateFlow<List<Int>>(emptyList())
    var bandRange: Pair<Int, Int> = -1500 to 1500
        private set

    private var recorder: AudioRecord? = null
    private var player: MediaPlayer? = null
    private var eq: Equalizer? = null
    private var bass: android.media.audiofx.BassBoost? = null
    private var loud: android.media.audiofx.LoudnessEnhancer? = null
    private var virt: android.media.audiofx.Virtualizer? = null

    /** Mastering à la lecture : basses (0-1000), loudness (0-1200 mB), largeur (0-1000). */
    val bassAmt = MutableStateFlow(0)
    val loudAmt = MutableStateFlow(0)
    val wideAmt = MutableStateFlow(0)
    /** true pendant le rendu d'un fichier masterisé. */
    val rendering = MutableStateFlow(false)

    /** Spectre en temps réel (24 bandes, 0..1) pendant la lecture. */
    val spectrum = MutableStateFlow(FloatArray(24))
    private var visualizer: android.media.audiofx.Visualizer? = null

    private val sampleRate = 44100

    // ----- Enregistrement -----

    @SuppressLint("MissingPermission") // la permission est demandée par l'UI avant l'appel
    fun startRecording(dir: File) {
        if (isRecording.value) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { statusMessage.value = "Entrée audio indisponible"; return }
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            statusMessage.value = "Micro/interface refusé (permission ?)"
            rec?.release(); return
        }
        // Router vers l'interface USB (ou l'entrée choisie) plutôt que le micro interne
        val chosen = pickInput()
        if (chosen != null) {
            rec.preferredDevice = chosen
            activeInputName.value = inputLabel(chosen)
        } else {
            activeInputName.value = "Micro interne (aucune interface USB détectée)"
        }
        recorder = rec
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = File(dir, "audio_$stamp.wav")
        isRecording.value = true
        statusMessage.value = "REC via : ${activeInputName.value}"
        scope.launch {
            val buf = ShortArray(minBuf)
            FileOutputStream(file).use { out ->
                out.write(wavHeader(0)) // tailles corrigées à la fin
                rec.startRecording()
                var total = 0L
                while (isRecording.value) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var peak = 0
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            if (abs(s) > peak) peak = abs(s)
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        out.write(bytes)
                        total += bytes.size
                        level.value = peak / 32768f
                    }
                }
                rec.stop(); rec.release()
                out.flush()
                // Corriger l'en-tête WAV avec les vraies tailles
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(0); raf.write(wavHeader(total))
                }
            }
            recorder = null
            level.value = 0f
            lastFile.value = file
            statusMessage.value = "Enregistré : ${file.name}"
        }
    }

    fun stopRecording() { isRecording.value = false }

    private fun wavHeader(dataLen: Long): ByteArray {
        val totalLen = dataLen + 36
        val byteRate = sampleRate * 2
        val h = ByteArray(44)
        fun put(i: Int, s: String) { s.toByteArray().copyInto(h, i) }
        fun put32(i: Int, v: Long) {
            h[i] = (v and 0xFF).toByte(); h[i + 1] = ((v shr 8) and 0xFF).toByte()
            h[i + 2] = ((v shr 16) and 0xFF).toByte(); h[i + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun put16(i: Int, v: Int) { h[i] = (v and 0xFF).toByte(); h[i + 1] = ((v shr 8) and 0xFF).toByte() }
        put(0, "RIFF"); put32(4, totalLen); put(8, "WAVE"); put(12, "fmt ")
        put32(16, 16); put16(20, 1); put16(22, 1); put32(24, sampleRate.toLong())
        put32(28, byteRate.toLong()); put16(32, 2); put16(34, 16)
        put(36, "data"); put32(40, dataLen)
        return h
    }

    // ----- Lecture avec égaliseur -----

    fun play(file: File) {
        stopPlayback()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(file.absolutePath)
            p.prepare()
            // Égaliseur attaché à la session audio du lecteur
            val e = Equalizer(0, p.audioSessionId)
            e.enabled = true
            bandRange = e.bandLevelRange[0].toInt() to e.bandLevelRange[1].toInt()
            val labels = ArrayList<String>()
            val levels = ArrayList<Int>()
            for (b in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000
                labels.add(if (hz >= 1000) "${hz / 1000}k" else "$hz")
                levels.add(e.getBandLevel(b.toShort()).toInt())
            }
            bandLabels.value = labels
            bandLevels.value = levels
            eq = e
            // Chaîne de mastering à la lecture
            runCatching {
                bass = android.media.audiofx.BassBoost(0, p.audioSessionId).apply {
                    enabled = true; setStrength(bassAmt.value.toShort())
                }
            }
            runCatching {
                loud = android.media.audiofx.LoudnessEnhancer(p.audioSessionId).apply {
                    enabled = true; setTargetGain(loudAmt.value)
                }
            }
            runCatching {
                virt = android.media.audiofx.Virtualizer(0, p.audioSessionId).apply {
                    enabled = true; setStrength(wideAmt.value.toShort())
                }
            }
            // Analyseur de spectre (FFT temps réel)
            runCatching {
                val vis = android.media.audiofx.Visualizer(p.audioSessionId)
                vis.captureSize = 1024
                vis.setDataCaptureListener(object :
                    android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: android.media.audiofx.Visualizer?, w: ByteArray?, r: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        v: android.media.audiofx.Visualizer?, fft: ByteArray?, r: Int
                    ) {
                        if (fft == null) return
                        val bands = 24
                        val nBins = fft.size / 2
                        val fresh = FloatArray(bands)
                        for (b in 0 until bands) {
                            // Répartition logarithmique : graves détaillés, aigus regroupés
                            val start = Math.pow(nBins.toDouble(), b / bands.toDouble())
                                .toInt().coerceAtLeast(1)
                            val end = Math.pow(nBins.toDouble(), (b + 1) / bands.toDouble())
                                .toInt().coerceIn(start + 1, nBins)
                            var m = 0f
                            for (k in start until end) {
                                val re = fft[2 * k].toInt()
                                val im = fft[2 * k + 1].toInt()
                                val mag = Math.sqrt((re * re + im * im).toDouble()).toFloat()
                                if (mag > m) m = mag
                            }
                            fresh[b] = (m / 128f).coerceIn(0f, 1f)
                        }
                        // Lissage : montée instantanée, descente douce
                        val old2 = spectrum.value
                        spectrum.value = FloatArray(bands) { i ->
                            maxOf(fresh[i], old2[i] * 0.78f)
                        }
                    }
                }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true)
                vis.enabled = true
                visualizer = vis
            }
            p.setOnCompletionListener { isPlaying.value = false }
            p.start()
            player = p
            isPlaying.value = true
            statusMessage.value = "Lecture : ${file.name}"
        }.onFailure {
            statusMessage.value = "Lecture impossible : ${it.message}"
            p.release()
        }
    }

    fun setBand(index: Int, millibels: Int) {
        val e = eq ?: return
        runCatching {
            e.setBandLevel(index.toShort(), millibels.toShort())
            bandLevels.value = bandLevels.value.toMutableList().also { it[index] = millibels }
        }
    }

    fun setBass(v: Int) {
        bassAmt.value = v.coerceIn(0, 1000)
        runCatching { bass?.setStrength(bassAmt.value.toShort()) }
    }

    fun setLoud(v: Int) {
        loudAmt.value = v.coerceIn(0, 1200)
        runCatching { loud?.setTargetGain(loudAmt.value) }
    }

    fun setWide(v: Int) {
        wideAmt.value = v.coerceIn(0, 1000)
        runCatching { virt?.setStrength(wideAmt.value.toShort()) }
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { loud?.release() }
        runCatching { virt?.release() }
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        player = null; eq = null; bass = null; loud = null; virt = null; visualizer = null
        spectrum.value = FloatArray(24)
        isPlaying.value = false
    }

    /** Forme d'onde d'un WAV : pics par tranche, pour l'affichage (échantillonnage rapide). */
    fun waveform(file: File, bins: Int = 96): FloatArray {
        val out = FloatArray(bins)
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val total = (raf.length() - 44) / 2
                if (total <= 0) return out
                val samples = bins * 24L
                val step = (total / samples).coerceAtLeast(1)
                val b2 = ByteArray(2)
                var sIdx = 0L
                while (sIdx < total) {
                    raf.seek(44 + sIdx * 2)
                    if (raf.read(b2) < 2) break
                    val v = Math.abs(
                        (((b2[1].toInt() shl 8) or (b2[0].toInt() and 0xFF)).toShort()).toInt()
                    ) / 32768f
                    val bin = ((sIdx * bins) / total).toInt().coerceAtMost(bins - 1)
                    if (v > out[bin]) out[bin] = v
                    sIdx += step
                }
            }
        }
        return out
    }

    // ----- Rendu masterisé hors ligne -----

    /**
     * Fabrique un nouveau WAV masterisé : basses renforcées (filtre en plateau à 100 Hz),
     * gain, saturation douce (limiteur) puis normalisation à -0,3 dBFS.
     */
    fun masterize(file: File, bassDb: Double, driveDb: Double) {
        if (rendering.value) return
        rendering.value = true
        statusMessage.value = "Mastering en cours…"
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                require(bytes.size > 44) { "Fichier trop court" }
                val n = (bytes.size - 44) / 2
                val x = DoubleArray(n)
                for (i in 0 until n) {
                    val lo = bytes[44 + i * 2].toInt() and 0xFF
                    val hi = bytes[44 + i * 2 + 1].toInt()
                    x[i] = ((hi shl 8) or lo) / 32768.0
                }
                // Filtre en plateau basse fréquence (RBJ low-shelf, 100 Hz)
                if (bassDb != 0.0) {
                    val a = Math.pow(10.0, bassDb / 40.0)
                    val w0 = 2.0 * Math.PI * 100.0 / sampleRate
                    val cosw = Math.cos(w0); val sinw = Math.sin(w0)
                    val alpha = sinw / 2.0 * Math.sqrt((a + 1 / a) * (1 / 0.9 - 1) + 2)
                    val b0 = a * ((a + 1) - (a - 1) * cosw + 2 * Math.sqrt(a) * alpha)
                    val b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
                    val b2 = a * ((a + 1) - (a - 1) * cosw - 2 * Math.sqrt(a) * alpha)
                    val a0 = (a + 1) + (a - 1) * cosw + 2 * Math.sqrt(a) * alpha
                    val a1 = -2 * ((a - 1) + (a + 1) * cosw)
                    val a2 = (a + 1) + (a - 1) * cosw - 2 * Math.sqrt(a) * alpha
                    var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                    for (i in 0 until n) {
                        val xi = x[i]
                        val yi = (b0 / a0) * xi + (b1 / a0) * x1 + (b2 / a0) * x2 -
                                (a1 / a0) * y1 - (a2 / a0) * y2
                        x2 = x1; x1 = xi; y2 = y1; y1 = yi
                        x[i] = yi
                    }
                }
                // Gain + saturation douce (limiteur musical)
                val drive = Math.pow(10.0, driveDb / 20.0)
                for (i in 0 until n) x[i] = Math.tanh(x[i] * drive)
                // Normalisation à -0,3 dBFS
                var peak = 1e-9
                for (v in x) { val a2 = Math.abs(v); if (a2 > peak) peak = a2 }
                val norm = 0.966 / peak
                val out = ByteArray(44 + n * 2)
                wavHeader((n * 2).toLong()).copyInto(out, 0)
                for (i in 0 until n) {
                    val v = (x[i] * norm * 32767.0).toInt().coerceIn(-32768, 32767)
                    out[44 + i * 2] = (v and 0xFF).toByte()
                    out[44 + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val dst = File(file.parentFile, file.nameWithoutExtension + "_master.wav")
                dst.writeBytes(out)
                lastFile.value = dst
                statusMessage.value = "Master prêt : ${dst.name}"
            }.onFailure {
                statusMessage.value = "Mastering impossible : ${it.message}"
            }
            rendering.value = false
        }
    }

    /** Coupe un WAV entre deux positions (0..1), avec normalisation optionnelle. */
    fun trim(file: File, startFrac: Float, endFrac: Float, normalize: Boolean) {
        if (rendering.value) return
        rendering.value = true
        statusMessage.value = "Découpe en cours…"
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                val n = (bytes.size - 44) / 2
                require(n > 100) { "Fichier trop court" }
                val s0 = (n * startFrac).toInt().coerceIn(0, n - 51)
                val s1 = (n * endFrac).toInt().coerceIn(s0 + 50, n)
                val len = s1 - s0
                val x = DoubleArray(len)
                for (i in 0 until len) {
                    val j = 44 + (s0 + i) * 2
                    val lo = bytes[j].toInt() and 0xFF
                    val hi = bytes[j + 1].toInt()
                    x[i] = ((hi shl 8) or lo) / 32768.0
                }
                var gain = 1.0
                if (normalize) {
                    var peak = 1e-9
                    for (v in x) { val a = Math.abs(v); if (a > peak) peak = a }
                    gain = 0.97 / peak
                }
                val out = ByteArray(44 + len * 2)
                wavHeader((len * 2).toLong()).copyInto(out, 0)
                for (i in 0 until len) {
                    val v = (x[i] * gain * 32767.0).toInt().coerceIn(-32768, 32767)
                    out[44 + i * 2] = (v and 0xFF).toByte()
                    out[44 + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val dst = File(file.parentFile, file.nameWithoutExtension + "_cut.wav")
                dst.writeBytes(out)
                statusMessage.value = "Sample coupé : ${dst.name}"
            }.onFailure { statusMessage.value = "Découpe impossible : ${it.message}" }
            rendering.value = false
        }
    }

    /**
     * Effets créatifs : vitesse (change aussi la hauteur, comme un vinyle), inversion,
     * filtre passe-bas et écho. Crée un nouveau fichier « _fx ».
     */
    fun applyFx(
        file: File, speed: Double, reverse: Boolean,
        lowpassHz: Double, delayMs: Int, feedback: Double
    ) {
        if (rendering.value) return
        rendering.value = true
        statusMessage.value = "Effets en cours…"
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                val n = (bytes.size - 44) / 2
                require(n > 100) { "Fichier trop court" }
                var x = DoubleArray(n) { i ->
                    val j = 44 + i * 2
                    val lo = bytes[j].toInt() and 0xFF
                    val hi = bytes[j + 1].toInt()
                    ((hi shl 8) or lo) / 32768.0
                }
                // Vitesse : rééchantillonnage linéaire
                if (speed != 1.0 && speed > 0.05) {
                    val m = (n / speed).toInt().coerceIn(16, sampleRate * 120)
                    val y = DoubleArray(m)
                    for (i in 0 until m) {
                        val pos = i * speed
                        val i0 = pos.toInt().coerceIn(0, n - 1)
                        val i1 = (i0 + 1).coerceAtMost(n - 1)
                        val fr = pos - i0
                        y[i] = x[i0] * (1.0 - fr) + x[i1] * fr
                    }
                    x = y
                }
                if (reverse) x.reverse()
                // Filtre passe-bas (1 pôle)
                if (lowpassHz in 100.0..18000.0) {
                    val dt = 1.0 / sampleRate
                    val rc = 1.0 / (2.0 * Math.PI * lowpassHz)
                    val a = dt / (rc + dt)
                    var prev = 0.0
                    for (i in x.indices) { prev += a * (x[i] - prev); x[i] = prev }
                }
                // Écho
                if (delayMs > 0 && feedback > 0.01) {
                    val d = (sampleRate.toLong() * delayMs / 1000L).toInt().coerceAtLeast(1)
                    if (d < x.size) {
                        for (i in d until x.size) x[i] += x[i - d] * feedback
                    }
                }
                // Normalisation de sécurité
                var peak = 1e-9
                for (v in x) { val a2 = Math.abs(v); if (a2 > peak) peak = a2 }
                val g = if (peak > 0.97) 0.97 / peak else 1.0
                val out = ByteArray(44 + x.size * 2)
                wavHeader((x.size * 2).toLong()).copyInto(out, 0)
                for (i in x.indices) {
                    val v = (x[i] * g * 32767.0).toInt().coerceIn(-32768, 32767)
                    out[44 + i * 2] = (v and 0xFF).toByte()
                    out[44 + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                val dst = File(file.parentFile, file.nameWithoutExtension + "_fx.wav")
                dst.writeBytes(out)
                statusMessage.value = "Effets appliqués : ${dst.name}"
            }.onFailure { statusMessage.value = "Effets impossibles : ${it.message}" }
            rendering.value = false
        }
    }

    /** Slicer : détecte les coups (transitoires) et crée un WAV par tranche. */
    fun slice(file: File) {
        if (rendering.value) return
        rendering.value = true
        statusMessage.value = "Détection des coups…"
        scope.launch {
            runCatching {
                val bytes = file.readBytes()
                val n = (bytes.size - 44) / 2
                require(n > 4410) { "Fichier trop court" }
                fun sample(i: Int): Double {
                    val j = 44 + i * 2
                    return (((bytes[j + 1].toInt() shl 8) or
                            (bytes[j].toInt() and 0xFF)).toShort()).toInt() / 32768.0
                }
                // Enveloppe par fenêtres de ~11 ms
                val win = 512
                val nw = n / win
                val env = DoubleArray(nw)
                for (w in 0 until nw) {
                    var m = 0.0
                    for (k in 0 until win) {
                        val a = Math.abs(sample(w * win + k)); if (a > m) m = a
                    }
                    env[w] = m
                }
                val peak = env.maxOrNull() ?: 0.0
                require(peak > 0.02) { "Signal trop faible" }
                val thr = peak * 0.25
                val minGapW = (0.12 * sampleRate / win).toInt() // 120 ms mini entre coups
                val onsets = ArrayList<Int>()
                var last = -minGapW
                for (w in 1 until nw) {
                    if (env[w] > thr && env[w - 1] <= thr && w - last >= minGapW) {
                        onsets.add(w * win); last = w
                    }
                }
                require(onsets.isNotEmpty()) { "Aucun coup détecté" }
                val limited = onsets.take(32)
                var made = 0
                for ((idx, s0) in limited.withIndex()) {
                    val s1 = if (idx + 1 < limited.size) limited[idx + 1] else n
                    val len = (s1 - s0).coerceAtMost(sampleRate * 2) // 2 s max par tranche
                    if (len < sampleRate / 16) continue // < 60 ms : bruit, ignoré
                    val out = ByteArray(44 + len * 2)
                    wavHeader((len * 2).toLong()).copyInto(out, 0)
                    System.arraycopy(bytes, 44 + s0 * 2, out, 44, len * 2)
                    val dst = File(
                        file.parentFile,
                        file.nameWithoutExtension + "_slice" +
                                String.format(java.util.Locale.US, "%02d", idx + 1) + ".wav"
                    )
                    dst.writeBytes(out)
                    made++
                }
                statusMessage.value = "$made tranche(s) créée(s) — prêtes pour la carte SD"
            }.onFailure { statusMessage.value = "Slicer impossible : ${it.message}" }
            rendering.value = false
        }
    }

    fun listRecordings(dir: File): List<File> =
        dir.listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun release() {
        stopRecording()
        stopPlayback()
    }
}
