package com.beatbox.midirecorder.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Un événement MIDI horodaté (temps relatif au début de l'enregistrement, en nanosecondes). */
data class RecordedEvent(
    val timeNanos: Long,
    val status: Int,
    val data1: Int,
    val data2: Int
) {
    val channel: Int get() = status and 0x0F
    val command: Int get() = status and 0xF0
}

/** État visible d'une piste (un canal MIDI = une piste, comme sur une Electribe). */
data class TrackUiState(
    val channel: Int,
    val eventCount: Int = 0,
    val lastEventUptimeMs: Long = 0L,
    val lastNote: Int = -1,
    val muted: Boolean = false,
    val soloed: Boolean = false
)

/** État d'une piste "par note" : mode ER-1, où chaque son = un numéro de note. */
data class NoteTrackUiState(
    val note: Int,
    val eventCount: Int = 0,
    val lastEventUptimeMs: Long = 0L,
    val muted: Boolean = false,
    val soloed: Boolean = false
)

class MidiEngine(private val context: Context) {

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    /** Un appareil MIDI ouvert. On peut en avoir plusieurs via un hub USB. */
    private class OpenDev(
        val id: Int,
        val name: String,
        val device: MidiDevice
    ) {
        val outputs = mutableListOf<MidiOutputPort>()   // sorties appareil -> nous
        val receivers = mutableListOf<MidiReceiver>()
        var input: MidiInputPort? = null                // entrée appareil <- nous
    }

    private val openDevs = mutableListOf<OpenDev>()
    private var playJob: Job? = null
    private var metronomeJob: Job? = null

    /** Nom de la dernière machine connectée : sert à la reconnexion automatique. */
    private var lastDeviceName: String? = null

    /** Événements enregistrés, par canal (0..15). */
    val trackEvents: Array<MutableList<RecordedEvent>> = Array(16) { mutableListOf() }

    // ----- État observable par l'UI -----
    val devices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val connectedName = MutableStateFlow<String?>(null)

    /** Appareils actuellement connectés : (id, nom). */
    val connectedDevices = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    /** Appareil cible pour l'envoi (null = tous). */
    val targetDeviceId = MutableStateFlow<Int?>(null)
    /** Dernier signal reçu par appareil (uptime ms), pour les LED. */
    val deviceActivity = MutableStateFlow<Map<Int, Long>>(emptyMap())

    /** Routage THRU : ce qui arrive d'un appareil est renvoyé vers les autres. */
    val thruEnabled = MutableStateFlow(false)
    val thruTranspose = MutableStateFlow(0)
    /** Canal forcé en sortie du THRU (-1 = inchangé). */
    val thruChannel = MutableStateFlow(-1)

    /** Télécommande : des notes reçues déclenchent les fonctions de l'app. */
    val remoteEnabled = MutableStateFlow(false)
    /** action -> note MIDI assignée. */
    val remoteMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Action en cours d'apprentissage (appuie sur un pad pour l'assigner). */
    val remoteLearn = MutableStateFlow<String?>(null)
    val statusMessage = MutableStateFlow<String?>(null)
    val isRecording = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val elapsedNanos = MutableStateFlow(0L)
    val tracks = MutableStateFlow(List(16) { TrackUiState(channel = it) })
    val bpm = MutableStateFlow(120)
    val revision = MutableStateFlow(0)
    val overdub = MutableStateFlow(false)

    /** Mode ER-1 : true = une piste par NOTE (kick, snare, hi-hat…), false = une piste par canal. */
    val noteMode = MutableStateFlow(false)
    /** Pistes dynamiques du mode ER-1, une par numéro de note rencontré, triées. */
    val noteTracks = MutableStateFlow<List<NoteTrackUiState>>(emptyList())

    /** Sync MIDI Clock : suit l'horloge de la machine (BPM auto + REC calé sur Start/Stop). */
    val syncEnabled = MutableStateFlow(false)
    /** Dernier tick d'horloge reçu (uptime ms) : alimente la LED "clock". */
    val lastClockMs = MutableStateFlow(0L)
    /** Quantize : 0 = off, sinon division (8 = 1/8, 16 = 1/16, 32 = 1/32). */
    val quantize = MutableStateFlow(0)

    /** Boucle pattern : nombre de mesures (0 = enregistrement libre, sinon 2/4/8). */
    val loopBars = MutableStateFlow(0)
    /** Clic audible du métronome. */
    val clickEnabled = MutableStateFlow(true)

    /** Mixette : volume (CC7), pan (CC10) et transposition par canal. */
    val volumes = MutableStateFlow(List(16) { 100 })
    val pans = MutableStateFlow(List(16) { 64 })
    val transposes = MutableStateFlow(List(16) { 0 })
    /** Swing 50–75 % : décale les contre-temps de double-croche. */
    val swing = MutableStateFlow(50)

    /** Horloge maître : l'app envoie son horloge MIDI et pilote le tempo de la machine. */
    val masterClock = MutableStateFlow(false)
    private var clockJob: Job? = null

    /** Une capture SysEx reçue de la machine (dump de sons/patterns). */
    data class SysexCapture(val bytes: ByteArray, val uptimeMs: Long)

    /** MIDI Learn : quand armé, capture le prochain potard tourné sur la machine. */
    val learnArmed = MutableStateFlow(false)
    val learnedCC = MutableStateFlow<Int?>(null)
    val learnedNrpn = MutableStateFlow<Pair<Int, Int>?>(null)
    private var nrpnSeenMsb = -1
    private var nrpnSeenLsb = -1

    fun startLearn() {
        learnedCC.value = null
        learnedNrpn.value = null
        nrpnSeenMsb = -1
        nrpnSeenLsb = -1
        learnArmed.value = true
        statusMessage.value = "Tourne un potard sur la machine…"
    }

    fun stopLearn() {
        learnArmed.value = false
        learnedCC.value = null
        learnedNrpn.value = null
    }

    /** Dumps SysEx capturés depuis la machine (20 max). */
    val sysexDumps = MutableStateFlow<List<SysexCapture>>(emptyList())

    /** Pilotage de la machine : Start/Stop et changement de pattern. */
    fun sendStart() = sendRealtime(0xFA)
    fun sendStop() = sendRealtime(0xFC)

    fun sendProgramChange(ch: Int, program: Int) {
        sendBytes(byteArrayOf((0xC0 or ch).toByte(), program.coerceIn(0, 127).toByte()), 0, 2)
    }

    /** Potards façon ER-1 : envoi NRPN (CC99 = MSB, CC98 = LSB, CC6 = valeur). */
    fun sendNRPN(ch: Int, msb: Int, lsb: Int, value: Int) {
        sendCC(ch, 99, msb)
        sendCC(ch, 98, lsb)
        sendCC(ch, 6, value)
    }

    /** Renvoie un dump SysEx vers la machine, par petits paquets. */
    fun sendSysex(bytes: ByteArray) {
        if (!hasOutput()) { statusMessage.value = "Pas d'entrée MIDI"; return }
        scope.launch {
            statusMessage.value = "Envoi du dump (${bytes.size} octets)…"
            var i = 0
            while (i < bytes.size) {
                val n = minOf(256, bytes.size - i)
                sendBytes(bytes, i, n)
                i += n
                delay(12) // laisser respirer la machine
            }
            statusMessage.value = "Dump envoyé à la machine"
        }
    }

    private var activeLoopNanos = 0L
    private var toneGen: android.media.ToneGenerator? = null

    /** Envoie un octet temps réel (clock/start/stop) à la machine. */
    private fun sendRealtime(b: Int) {
        sendBytes(byteArrayOf(b.toByte()), 0, 1)
    }

    /** Envoie une note (pads LIVE). */
    fun sendNoteOn(ch: Int, note: Int, vel: Int) {
        sendBytes(
            byteArrayOf((0x90 or ch).toByte(), note.coerceIn(0, 127).toByte(),
                vel.coerceIn(1, 127).toByte()), 0, 3
        )
    }

    fun sendNoteOff(ch: Int, note: Int) {
        sendBytes(byteArrayOf((0x80 or ch).toByte(), note.coerceIn(0, 127).toByte(), 0), 0, 3)
    }

    /** Active/coupe l'horloge maître (exclusif avec le mode SYNC esclave). */
    fun toggleMasterClock() {
        if (masterClock.value) {
            masterClock.value = false
            clockJob?.cancel(); clockJob = null
            sendRealtime(0xFC) // Stop
        } else {
            syncEnabled.value = false
            masterClock.value = true
            sendRealtime(0xFA) // Start : la machine démarre calée sur nous
            clockJob = scope.launch {
                var next = System.nanoTime()
                while (masterClock.value) {
                    val interval = 60_000_000_000L / (bpm.value.coerceIn(20, 300) * 24L)
                    next += interval
                    val waitMs = (next - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) delay(waitMs)
                    sendRealtime(0xF8) // tick
                }
            }
        }
    }

    private var lastClockNanos = 0L
    private var clockDtSmooth = 0.0

    /** Petit clic audible ; fort (aigu double) pour le compte à rebours. */
    private fun click(strong: Boolean) {
        if (!clickEnabled.value) return
        runCatching {
            if (toneGen == null) {
                toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 80)
            }
            toneGen?.startTone(
                if (strong) android.media.ToneGenerator.TONE_PROP_BEEP2
                else android.media.ToneGenerator.TONE_PROP_BEEP,
                40
            )
        }
    }

    /** Dernier instant (uptime ms) où un octet MIDI est arrivé : alimente la LED "signal". */
    val lastSignalMs = MutableStateFlow(0L)
    /** Battement du métronome : bascule true/false à chaque temps. */
    val metroTick = MutableStateFlow(false)
    /** Compte à rebours avant enregistrement (4..1, puis 0). */
    val countIn = MutableStateFlow(0)

    private var recordStartNanos = 0L
    private var recordedLengthNanos = 0L
    val lengthNanos: Long get() = recordedLengthNanos

    init {
        loadRemote()
        refreshDevices()
        midiManager.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                refreshDevices()
                // Reconnexion auto si c'est une machine qu'on utilisait.
                if (openDevs.none { it.id == info.id } && lastDeviceName != null &&
                    deviceLabel(info) == lastDeviceName
                ) {
                    connect(info)
                }
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                refreshDevices()
                if (openDevs.any { it.id == info.id }) {
                    statusMessage.value = "Appareil débranché — reconnexion possible"
                    disconnectDevice(info.id)
                }
            }
        }, handler)
    }

    fun refreshDevices() {
        devices.value = midiManager.devices.toList()
    }

    fun deviceLabel(info: MidiDeviceInfo): String {
        val b = info.properties
        return b.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: b.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Appareil MIDI ${info.id}"
    }

    /** Connecte un appareil SANS fermer les autres : plusieurs machines à la fois. */
    fun connect(info: MidiDeviceInfo) {
        if (openDevs.any { it.id == info.id }) {
            statusMessage.value = "${deviceLabel(info)} est déjà connecté"
            return
        }
        statusMessage.value = "Connexion…"
        midiManager.openDevice(info, { opened ->
            if (opened == null) {
                statusMessage.value = "Échec : autorisation USB refusée ?"
                return@openDevice
            }
            val dev = OpenDev(info.id, deviceLabel(info), opened)
            // Ouvre TOUS les ports de sortie (certaines machines en exposent plusieurs).
            for (p in 0 until info.outputPortCount) {
                opened.openOutputPort(p)?.let { port ->
                    val rec = PortReceiver(info.id)
                    port.connect(rec)
                    dev.outputs.add(port)
                    dev.receivers.add(rec)
                }
            }
            if (info.inputPortCount > 0) {
                dev.input = opened.openInputPort(0)
            }
            openDevs.add(dev)
            lastDeviceName = dev.name
            refreshConnectedState()
            statusMessage.value = when {
                dev.outputs.isEmpty() -> "${dev.name} : aucune sortie MIDI détectée"
                else -> "${dev.name} · ${dev.outputs.size} port(s) d'écoute"
            }
        }, handler)
    }

    private fun refreshConnectedState() {
        val list = openDevs.map { it.id to it.name }
        connectedDevices.value = list
        connectedName.value = when {
            list.isEmpty() -> null
            list.size == 1 -> list[0].second
            else -> "${list.size} appareils connectés"
        }
        if (targetDeviceId.value != null && list.none { it.first == targetDeviceId.value }) {
            targetDeviceId.value = null
        }
    }

    /** Ferme un seul appareil. */
    fun disconnectDevice(id: Int) {
        val dev = openDevs.find { it.id == id } ?: return
        closeDev(dev)
        openDevs.remove(dev)
        refreshConnectedState()
        statusMessage.value = "${dev.name} déconnecté"
    }

    private fun closeDev(dev: OpenDev) {
        dev.outputs.forEachIndexed { i, port ->
            dev.receivers.getOrNull(i)?.let { rec -> runCatching { port.disconnect(rec) } }
            runCatching { port.close() }
        }
        dev.outputs.clear()
        dev.receivers.clear()
        runCatching { dev.input?.close() }
        dev.input = null
        runCatching { dev.device.close() }
    }

    /** Scanne et appaire une machine Bluetooth MIDI (Volca, certains Korg). */
    fun connectBluetooth(btDevice: android.bluetooth.BluetoothDevice) {
        statusMessage.value = "Appairage Bluetooth…"
        midiManager.openBluetoothDevice(btDevice, { opened ->
            if (opened == null) {
                statusMessage.value = "Bluetooth : appairage impossible"
                return@openBluetoothDevice
            }
            val info = opened.info
            val dev = OpenDev(info.id, deviceLabel(info), opened)
            for (p in 0 until info.outputPortCount) {
                opened.openOutputPort(p)?.let { port ->
                    val rec = PortReceiver(info.id)
                    port.connect(rec)
                    dev.outputs.add(port)
                    dev.receivers.add(rec)
                }
            }
            if (info.inputPortCount > 0) dev.input = opened.openInputPort(0)
            openDevs.add(dev)
            lastDeviceName = dev.name
            refreshConnectedState()
            statusMessage.value = "Connecté en Bluetooth : ${dev.name}"
        }, handler)
    }

    fun disconnect() {
        lastDeviceName = null          // coupe la reconnexion auto
        if (masterClock.value) toggleMasterClock()
        stopPlayback()
        closePorts()
        statusMessage.value = "Tout déconnecté"
    }

    private fun closePorts() {
        openDevs.forEach { closeDev(it) }
        openDevs.clear()
        refreshConnectedState()
    }

    /** Ports d'entrée des appareils visés par l'envoi (cible choisie, ou tous). */
    private fun outTargets(): List<MidiInputPort> {
        val t = targetDeviceId.value
        return openDevs.filter { t == null || it.id == t }.mapNotNull { it.input }
    }

    private fun hasOutput(): Boolean = outTargets().isNotEmpty()

    /** Envoie des octets MIDI vers les appareils visés. */
    private fun sendBytes(bytes: ByteArray, offset: Int, len: Int) {
        outTargets().forEach { port -> runCatching { port.send(bytes, offset, len) } }
    }

    /** Envoie vers tous les appareils SAUF celui d'où vient le message (routage THRU). */
    private fun sendBytesExcept(srcId: Int, bytes: ByteArray, offset: Int, len: Int) {
        openDevs.forEach { dev ->
            if (dev.id != srcId) dev.input?.let { port ->
                runCatching { port.send(bytes, offset, len) }
            }
        }
    }

    // ----- Enregistrement -----

    /** Lance un compte à rebours de 4 temps, puis démarre l'enregistrement. */
    fun startRecordingWithCountIn() {
        if (isRecording.value) return
        scope.launch {
            val beatMs = 60_000L / bpm.value.coerceIn(20, 300)
            for (b in 4 downTo 1) {
                countIn.value = b
                metroTick.value = !metroTick.value
                click(strong = true)
                delay(beatMs)
            }
            countIn.value = 0
            beginRecording()
        }
    }

    fun startRecording() { beginRecording() }

    private fun beginRecording() {
        if (!overdub.value) clearAll()
        // Boucle pattern : longueur fixe, calculée au BPM du départ.
        val quarter = 60_000_000_000L / bpm.value.coerceIn(20, 300)
        activeLoopNanos = if (loopBars.value > 0) loopBars.value * 4L * quarter else 0L
        if (activeLoopNanos > 0) recordedLengthNanos = activeLoopNanos
        recordStartNanos = System.nanoTime()
        isRecording.value = true
        startMetronome()
        scope.launch {
            while (isRecording.value) {
                val e = System.nanoTime() - recordStartNanos
                elapsedNanos.value = if (activeLoopNanos > 0) e % activeLoopNanos else e
                delay(33)
            }
        }
        // Sauvegarde automatique : une session "secours-auto" toutes les 30 s
        scope.launch {
            while (isRecording.value) {
                delay(30_000)
                if (!isRecording.value) break
                runCatching {
                    val evs = allEvents()
                    if (evs.isNotEmpty()) {
                        val len = if (activeLoopNanos > 0) activeLoopNanos
                        else System.nanoTime() - recordStartNanos
                        SessionStore.save(context.filesDir, "secours-auto", evs, len, bpm.value)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        if (!isRecording.value) return
        isRecording.value = false
        stopMetronome()
        val len = System.nanoTime() - recordStartNanos
        if (activeLoopNanos > 0) {
            recordedLengthNanos = activeLoopNanos
        } else if (len > recordedLengthNanos) {
            recordedLengthNanos = len
        }
        elapsedNanos.value = recordedLengthNanos
        // Dernière sauvegarde de secours, avec la prise complète
        scope.launch {
            runCatching {
                val evs = allEvents()
                if (evs.isNotEmpty()) {
                    SessionStore.save(context.filesDir, "secours-auto", evs, recordedLengthNanos, bpm.value)
                }
            }
        }
    }

    fun setLoopBars(bars: Int) { loopBars.value = bars }

    fun toggleClick() { clickEnabled.value = !clickEnabled.value }

    fun clearAll() {
        trackEvents.forEach { it.clear() }
        tracks.value = List(16) { TrackUiState(channel = it) }
        noteTracks.value = emptyList()
        recordedLengthNanos = 0L
        elapsedNanos.value = 0L
        revision.value++
    }

    fun toggleMute(channel: Int) {
        tracks.value = tracks.value.map {
            if (it.channel == channel) it.copy(muted = !it.muted) else it
        }
    }

    fun toggleSolo(channel: Int) {
        tracks.value = tracks.value.map {
            if (it.channel == channel) it.copy(soloed = !it.soloed) else it
        }
    }

    /** Une piste s'entend si elle n'est pas mutée ET (aucune piste en solo OU elle est en solo). */
    private fun audibleChannels(): Set<Int> {
        val list = tracks.value
        val anySolo = list.any { it.soloed }
        return list.filter { !it.muted && (!anySolo || it.soloed) }.map { it.channel }.toSet()
    }

    // ----- Mode ER-1 (pistes par note) -----

    fun toggleNoteMode() { noteMode.value = !noteMode.value }

    fun toggleNoteMute(note: Int) {
        noteTracks.value = noteTracks.value.map {
            if (it.note == note) it.copy(muted = !it.muted) else it
        }
    }

    fun toggleNoteSolo(note: Int) {
        noteTracks.value = noteTracks.value.map {
            if (it.note == note) it.copy(soloed = !it.soloed) else it
        }
    }

    private fun audibleNotes(): Set<Int> {
        val list = noteTracks.value
        val anySolo = list.any { it.soloed }
        return list.filter { !it.muted && (!anySolo || it.soloed) }.map { it.note }.toSet()
    }

    /** Toutes les frappes (note-on) d'un numéro de note, tous canaux confondus, pour la timeline. */
    fun snapshotNoteEvents(note: Int): List<RecordedEvent> =
        trackEvents.flatMap { list ->
            synchronized(list) {
                list.filter { it.command == 0x90 && it.data1 == note && it.data2 > 0 }
            }
        }

    // ----- Sync MIDI Clock -----

    fun toggleSync() { syncEnabled.value = !syncEnabled.value }

    // ----- Routage THRU -----

    fun toggleThru() { thruEnabled.value = !thruEnabled.value }
    fun setThruTranspose(v: Int) { thruTranspose.value = v.coerceIn(-24, 24) }
    fun setThruChannel(v: Int) { thruChannel.value = v.coerceIn(-1, 15) }

    // ----- Télécommande (pads/touches d'un contrôleur USB) -----

    fun toggleRemote() { remoteEnabled.value = !remoteEnabled.value }

    fun startRemoteLearn(action: String) {
        remoteLearn.value = action
        statusMessage.value = "Appuie sur le pad à assigner à $action"
    }

    fun cancelRemoteLearn() { remoteLearn.value = null }

    fun clearRemote(action: String) {
        remoteMap.value = remoteMap.value - action
        saveRemote()
    }

    private fun assignRemote(action: String, note: Int) {
        remoteMap.value = remoteMap.value + (action to note)
        remoteLearn.value = null
        remoteEnabled.value = true
        saveRemote()
        statusMessage.value = "$action assigné à la note $note"
    }

    private fun runRemoteAction(action: String) {
        when (action) {
            "REC" -> if (isRecording.value) stopRecording() else startRecordingWithCountIn()
            "PLAY" -> if (isPlaying.value) stopPlayback() else startPlayback()
            "STOP" -> { if (isRecording.value) stopRecording(); stopPlayback() }
            "OVERDUB" -> toggleOverdub()
        }
    }

    private fun prefs() =
        context.getSharedPreferences("fab", android.content.Context.MODE_PRIVATE)

    private fun saveRemote() {
        val raw = remoteMap.value.entries.joinToString(",") { "${it.key}:${it.value}" }
        prefs().edit().putString("remote", raw).apply()
    }

    private fun loadRemote() {
        val raw = prefs().getString("remote", "") ?: ""
        if (raw.isBlank()) return
        val map = HashMap<String, Int>()
        for (part in raw.split(",")) {
            val kv = part.split(":")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { map[kv[0]] = it }
        }
        remoteMap.value = map
    }

    /** MIDI Learn : décode ce que la machine envoie quand on tourne un potard. */
    private fun onLearnCC(cc: Int, value: Int) {
        when (cc) {
            99 -> nrpnSeenMsb = value
            98 -> nrpnSeenLsb = value
            6 -> {
                if (nrpnSeenMsb >= 0 && nrpnSeenLsb >= 0) {
                    val pair = nrpnSeenMsb to nrpnSeenLsb
                    handler.post { learnedNrpn.value = pair }
                }
            }
            else -> handler.post { learnedCC.value = cc }
        }
    }

    /** Un tick d'horloge = 1/24e de noire. On lisse les intervalles pour déduire le BPM. */
    private fun onClockTick() {
        val now = System.nanoTime()
        lastClockMs.value = SystemClock.uptimeMillis()
        if (lastClockNanos != 0L) {
            val dt = (now - lastClockNanos).toDouble()
            // Ignore les trous (pause de la machine) supérieurs à ~1/2 seconde
            if (dt < 500_000_000.0) {
                clockDtSmooth = if (clockDtSmooth == 0.0) dt else clockDtSmooth * 0.9 + dt * 0.1
                if (syncEnabled.value && clockDtSmooth > 0) {
                    val newBpm = (60_000_000_000.0 / (clockDtSmooth * 24.0)).toInt()
                    if (newBpm in 20..300 && newBpm != bpm.value) bpm.value = newBpm
                }
            } else {
                clockDtSmooth = 0.0
            }
        }
        lastClockNanos = now
    }

    /** Start machine : si le sync est armé, l'enregistrement démarre pile sur le premier temps. */
    private fun onMachineStart() {
        if (syncEnabled.value && !isRecording.value) handler.post { beginRecording() }
    }

    private fun onMachineStop() {
        if (syncEnabled.value && isRecording.value) handler.post { stopRecording() }
    }

    // ----- Quantize -----

    fun setQuantize(div: Int) { quantize.value = div }

    private fun gridNanos(): Long {
        val div = quantize.value
        if (div <= 0) return 0
        val quarter = 60_000_000_000L / bpm.value.coerceIn(20, 300)
        return (quarter * 4) / div
    }

    /** Cale les notes (on/off) sur la grille ; les CC et potards restent intacts. */
    private fun maybeQuantize(ev: RecordedEvent): RecordedEvent {
        val g = gridNanos()
        if (g <= 0) return ev
        if (ev.command != 0x90 && ev.command != 0x80) return ev
        val q = ((ev.timeNanos + g / 2) / g) * g
        return ev.copy(timeNanos = q)
    }

    /** Copie quantifiée (et swinguée) de toutes les pistes, pour l'export .mid. */
    fun quantizedCopy(): Array<MutableList<RecordedEvent>> =
        Array(16) { ch ->
            synchronized(trackEvents[ch]) {
                trackEvents[ch].map { maybeSwing(maybeQuantize(it)) }
                    .sortedBy { it.timeNanos }.toMutableList()
            }
        }

    // ----- Mixette MIDI -----

    /** Envoie un Control Change immédiat à la machine. */
    fun sendCC(ch: Int, cc: Int, value: Int) {
        sendBytes(
            byteArrayOf((0xB0 or ch).toByte(), cc.toByte(), value.coerceIn(0, 127).toByte()), 0, 3
        )
    }

    fun setVolume(ch: Int, v: Int) {
        volumes.value = volumes.value.toMutableList().also { it[ch] = v.coerceIn(0, 127) }
        sendCC(ch, 7, v)
    }

    fun setPan(ch: Int, v: Int) {
        pans.value = pans.value.toMutableList().also { it[ch] = v.coerceIn(0, 127) }
        sendCC(ch, 10, v)
    }

    fun setTranspose(ch: Int, t: Int) {
        transposes.value = transposes.value.toMutableList().also { it[ch] = t.coerceIn(-24, 24) }
    }

    fun setSwing(v: Int) { swing.value = v.coerceIn(50, 75) }

    /** Applique la transposition de la mixette aux notes, à la lecture. */
    private fun applyTranspose(ev: RecordedEvent): RecordedEvent {
        if (ev.command != 0x90 && ev.command != 0x80) return ev
        val t = transposes.value[ev.channel]
        if (t == 0) return ev
        return ev.copy(data1 = (ev.data1 + t).coerceIn(0, 127))
    }

    /** Swing MPC : le contre-temps de double-croche est repoussé selon le pourcentage. */
    private fun maybeSwing(ev: RecordedEvent): RecordedEvent {
        val s = swing.value
        if (s <= 50) return ev
        if (ev.command != 0x90 && ev.command != 0x80) return ev
        val g = (60_000_000_000L / bpm.value.coerceIn(20, 300)) / 4 // double-croche
        val idx = Math.round(ev.timeNanos.toDouble() / g)
        if (idx % 2 == 0L) return ev
        val micro = ev.timeNanos - idx * g
        val swung = (idx - 1) * g + (2.0 * g * s / 100.0).toLong()
        return ev.copy(timeNanos = (swung + micro).coerceAtLeast(0))
    }

    // ----- Piano roll -----

    /** Une note avec sa durée, pour l'affichage en barres. */
    data class NoteBar(val note: Int, val start: Long, val dur: Long, val vel: Int)

    /** Reconstruit les paires note-on/note-off d'un canal en barres. */
    fun noteBars(ch: Int): List<NoteBar> {
        val list = synchronized(trackEvents[ch]) { trackEvents[ch].sortedBy { it.timeNanos } }
        val open = HashMap<Int, RecordedEvent>()
        val out = ArrayList<NoteBar>()
        for (ev in list) {
            if (ev.command == 0x90 && ev.data2 > 0) {
                open[ev.data1] = ev
            } else if (ev.command == 0x80 || (ev.command == 0x90 && ev.data2 == 0)) {
                open.remove(ev.data1)?.let { on ->
                    out.add(NoteBar(ev.data1, on.timeNanos, (ev.timeNanos - on.timeNanos).coerceAtLeast(1), on.data2))
                }
            }
        }
        // Notes jamais refermées : durée par défaut de 60 ms
        open.values.forEach { out.add(NoteBar(it.data1, it.timeNanos, 60_000_000L, it.data2)) }
        return out
    }

    /** Ajoute une note (on + off) depuis le piano roll. */
    fun addNote(ch: Int, note: Int, timeNanos: Long, durNanos: Long, vel: Int = 100) {
        synchronized(trackEvents[ch]) {
            trackEvents[ch].add(RecordedEvent(timeNanos, 0x90 or ch, note, vel.coerceIn(1, 127)))
            trackEvents[ch].add(RecordedEvent(timeNanos + durNanos, 0x80 or ch, note, 0))
            trackEvents[ch].sortBy { it.timeNanos }
        }
        if (timeNanos + durNanos > recordedLengthNanos) recordedLengthNanos = timeNanos + durNanos
        recomputeUiStates()
    }

    /** Change la vélocité d'une frappe (glisser vertical dans le piano roll). */
    fun setHitVelocity(ch: Int, note: Int, timeNanos: Long, vel: Int) {
        synchronized(trackEvents[ch]) {
            val i = trackEvents[ch].indexOfFirst {
                it.command == 0x90 && it.data2 > 0 && it.data1 == note && it.timeNanos == timeNanos
            }
            if (i >= 0) trackEvents[ch][i] = trackEvents[ch][i].copy(data2 = vel.coerceIn(1, 127))
        }
        revision.value++
    }

    // ----- Générateur de rythmes euclidiens -----

    /** Répartit `pulses` coups le plus régulièrement possible sur `steps` pas. */
    fun euclidPattern(steps: Int, pulses: Int, rotate: Int): BooleanArray {
        val n = steps.coerceIn(1, 64)
        val out = BooleanArray(n)
        val p = pulses.coerceIn(0, n)
        if (p == 0) return out
        for (i in 0 until n) out[i] = ((i * p) % n) < p
        if (rotate == 0) return out
        val r = ((rotate % n) + n) % n
        val rot = BooleanArray(n)
        for (i in 0 until n) rot[(i + r) % n] = out[i]
        return rot
    }

    /** Écrit un motif euclidien sur une piste, répété sur plusieurs mesures. */
    fun generateEuclid(
        ch: Int, note: Int, steps: Int, pulses: Int,
        rotate: Int, bars: Int, vel: Int = 100
    ) {
        val pat = euclidPattern(steps, pulses, rotate)
        val quarter = 60_000_000_000L / bpm.value.coerceIn(20, 300)
        val barNanos = quarter * 4
        val stepNanos = barNanos / pat.size
        val dur = (stepNanos * 8) / 10
        val nBars = bars.coerceIn(1, 16)
        synchronized(trackEvents[ch]) {
            for (b in 0 until nBars) {
                for (i in pat.indices) {
                    if (!pat[i]) continue
                    val t = b * barNanos + i * stepNanos
                    trackEvents[ch].add(RecordedEvent(t, 0x90 or ch, note, vel.coerceIn(1, 127)))
                    trackEvents[ch].add(RecordedEvent(t + dur, 0x80 or ch, note, 0))
                }
            }
            trackEvents[ch].sortBy { it.timeNanos }
        }
        val end = nBars * barNanos
        if (end > recordedLengthNanos) recordedLengthNanos = end
        recomputeUiStates()
    }

    /** Humanise une piste : micro-décalages de timing et de force, pour enlever le côté robot. */
    fun humanize(ch: Int, timingMs: Int, velAmount: Int) {
        val bars = noteBars(ch)
        if (bars.isEmpty()) return
        val rnd = java.util.Random()
        synchronized(trackEvents[ch]) {
            trackEvents[ch].removeAll { it.command == 0x90 || it.command == 0x80 }
            for (b in bars) {
                val dt = ((rnd.nextDouble() - 0.5) * 2.0 * timingMs * 1_000_000.0).toLong()
                val t = (b.start + dt).coerceAtLeast(0L)
                val v = (b.vel + ((rnd.nextDouble() - 0.5) * 2.0 * velAmount).toInt())
                    .coerceIn(1, 127)
                trackEvents[ch].add(RecordedEvent(t, 0x90 or ch, b.note, v))
                trackEvents[ch].add(RecordedEvent(t + b.dur, 0x80 or ch, b.note, 0))
            }
            trackEvents[ch].sortBy { it.timeNanos }
        }
        recomputeUiStates()
    }

    /** Copie les notes d'une mesure vers une autre (piano roll, canal courant). */
    fun copyBar(ch: Int, srcBar: Int, dstBar: Int, barNanos: Long) {
        if (srcBar == dstBar || barNanos <= 0) return
        val src0 = srcBar * barNanos
        val src1 = src0 + barNanos
        val toCopy = noteBars(ch).filter { it.start >= src0 && it.start < src1 }
        if (toCopy.isEmpty()) return
        val offset = (dstBar - srcBar) * barNanos
        synchronized(trackEvents[ch]) {
            for (b in toCopy) {
                val t = b.start + offset
                if (t < 0) continue
                trackEvents[ch].add(RecordedEvent(t, 0x90 or ch, b.note, b.vel))
                trackEvents[ch].add(RecordedEvent(t + b.dur, 0x80 or ch, b.note, 0))
            }
            trackEvents[ch].sortBy { it.timeNanos }
        }
        val end = (dstBar + 1) * barNanos
        if (end > recordedLengthNanos) recordedLengthNanos = end
        recomputeUiStates()
    }

    // ----- Sessions et édition -----

    /** Tous les événements, triés, pour la sauvegarde de session. */
    fun allEvents(): List<RecordedEvent> =
        trackEvents.flatMap { synchronized(it) { it.toList() } }.sortedBy { it.timeNanos }

    /** Recalcule les compteurs et pistes d'après le contenu réel des événements. */
    fun recomputeUiStates() {
        val counts = IntArray(16)
        val lastNotes = IntArray(16) { -1 }
        val noteCounts = LinkedHashMap<Int, Int>()
        for (ch in 0..15) synchronized(trackEvents[ch]) {
            for (ev in trackEvents[ch]) {
                counts[ch]++
                if (ev.command == 0x90 && ev.data2 > 0) {
                    lastNotes[ch] = ev.data1
                    noteCounts[ev.data1] = (noteCounts[ev.data1] ?: 0) + 1
                }
            }
        }
        tracks.value = List(16) { TrackUiState(it, counts[it], 0L, lastNotes[it]) }
        noteTracks.value = noteCounts.entries.sortedBy { it.key }
            .map { NoteTrackUiState(note = it.key, eventCount = it.value) }
        revision.value++
    }

    /** Recharge une session sauvegardée. */
    fun restore(events: List<RecordedEvent>, length: Long, newBpm: Int) {
        stopPlayback()
        if (isRecording.value) stopRecording()
        trackEvents.forEach { it.clear() }
        for (ev in events) synchronized(trackEvents[ev.channel]) { trackEvents[ev.channel].add(ev) }
        recordedLengthNanos = length
        elapsedNanos.value = length
        bpm.value = newBpm.coerceIn(20, 300)
        recomputeUiStates()
    }

    /** Supprime la frappe (note-on) la plus proche de ce temps, et son note-off associé. */
    fun deleteHit(channelHint: Int?, note: Int, timeNanos: Long) {
        var bestCh = -1; var bestIdx = -1; var bestDt = Long.MAX_VALUE
        for (ch in 0..15) {
            if (channelHint != null && ch != channelHint) continue
            synchronized(trackEvents[ch]) {
                trackEvents[ch].forEachIndexed { i, ev ->
                    if (ev.command == 0x90 && ev.data2 > 0 && ev.data1 == note) {
                        val dt = kotlin.math.abs(ev.timeNanos - timeNanos)
                        if (dt < bestDt) { bestDt = dt; bestCh = ch; bestIdx = i }
                    }
                }
            }
        }
        if (bestCh < 0) return
        synchronized(trackEvents[bestCh]) {
            val onEv = trackEvents[bestCh][bestIdx]
            trackEvents[bestCh].removeAt(bestIdx)
            val offIdx = trackEvents[bestCh].indexOfFirst {
                it.data1 == note && it.timeNanos >= onEv.timeNanos &&
                        (it.command == 0x80 || (it.command == 0x90 && it.data2 == 0))
            }
            if (offIdx >= 0) trackEvents[bestCh].removeAt(offIdx)
        }
        recomputeUiStates()
    }

    fun toggleOverdub() { overdub.value = !overdub.value }

    // ----- Métronome visuel -----

    private fun startMetronome() {
        stopMetronome()
        metronomeJob = scope.launch {
            val beatMs = 60_000L / bpm.value.coerceIn(20, 300)
            while (isRecording.value) {
                metroTick.value = !metroTick.value
                click(strong = false)
                delay(beatMs)
            }
        }
    }

    private fun stopMetronome() { metronomeJob?.cancel(); metronomeJob = null }

    /**
     * Un récepteur par port : chaque appareil a son propre décodage (running status),
     * indispensable quand plusieurs machines parlent en même temps.
     */
    private inner class PortReceiver(private val srcId: Int) : MidiReceiver() {
        private var runningStatus = 0
        private val pending = IntArray(2)
        private var pendingCount = 0
        private var needed = 0
        private var sysexOn = false
        private val sysexBuf = java.io.ByteArrayOutputStream()
        private val thruBuf = ByteArray(3)

        private fun dataBytesFor(status: Int): Int = when (status and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (count > 0) {
                val now = SystemClock.uptimeMillis()
                lastSignalMs.value = now
                deviceActivity.value = deviceActivity.value + (srcId to now)
            }
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b == 0xF8 -> onClockTick()
                    b == 0xFA -> onMachineStart()
                    b == 0xFC -> onMachineStop()
                    b >= 0xF8 -> { /* autres temps réel : ignorés */ }
                    b == 0xF0 -> {
                        sysexOn = true
                        sysexBuf.reset()
                        sysexBuf.write(b)
                        runningStatus = 0; pendingCount = 0
                    }
                    b == 0xF7 -> {
                        if (sysexOn) {
                            sysexBuf.write(b)
                            val dump = SysexCapture(sysexBuf.toByteArray(), SystemClock.uptimeMillis())
                            handler.post {
                                sysexDumps.value = (sysexDumps.value + dump).takeLast(20)
                                statusMessage.value = "Dump reçu : ${dump.bytes.size} octets"
                            }
                        }
                        sysexOn = false
                    }
                    b >= 0xF0 -> { runningStatus = 0; pendingCount = 0; sysexOn = false }
                    sysexOn -> sysexBuf.write(b)
                    b >= 0x80 -> { runningStatus = b; pendingCount = 0; needed = dataBytesFor(b) }
                    else -> {
                        if (runningStatus != 0) {
                            pending[pendingCount++] = b
                            if (pendingCount == needed) {
                                handleMessage(
                                    runningStatus, pending[0],
                                    if (needed == 2) pending[1] else 0
                                )
                                pendingCount = 0
                            }
                        }
                    }
                }
                i++
            }
        }

        /** Un message complet reçu : apprentissage, télécommande, THRU, puis enregistrement. */
        private fun handleMessage(status: Int, d1: Int, d2: Int) {
            val cmd = status and 0xF0

            // MIDI Learn (potards de la machine)
            if (learnArmed.value && cmd == 0xB0) onLearnCC(d1, d2)

            // Télécommande : un pad/une touche déclenche une fonction de l'app
            if (cmd == 0x90 && d2 > 0) {
                val learning = remoteLearn.value
                if (learning != null) {
                    handler.post { assignRemote(learning, d1) }
                } else if (remoteEnabled.value) {
                    val action = remoteMap.value.entries.find { it.value == d1 }?.key
                    if (action != null) {
                        handler.post { runRemoteAction(action) }
                        return // ce pad pilote l'app, on ne l'enregistre pas
                    }
                }
            }

            // Routage THRU vers les autres appareils
            if (thruEnabled.value && openDevs.size > 1) {
                var st = status
                var n1 = d1
                val forced = thruChannel.value
                if (forced in 0..15) st = (cmd or forced)
                if (cmd == 0x90 || cmd == 0x80) {
                    n1 = (d1 + thruTranspose.value).coerceIn(0, 127)
                }
                thruBuf[0] = st.toByte(); thruBuf[1] = n1.toByte(); thruBuf[2] = d2.toByte()
                sendBytesExcept(srcId, thruBuf, 0, if (needed == 1) 2 else 3)
            }

            if (isRecording.value) {
                commit(System.nanoTime() - recordStartNanos, status, d1, d2)
            }
        }

        private fun commit(t0: Long, status: Int, d1: Int, d2: Int) {
            val t = if (activeLoopNanos > 0) t0 % activeLoopNanos else t0
            val ev = RecordedEvent(t, status, d1, d2)
            val ch = ev.channel
            synchronized(trackEvents[ch]) { trackEvents[ch].add(ev) }
            handler.post {
                tracks.value = tracks.value.map {
                    if (it.channel == ch) it.copy(
                        eventCount = it.eventCount + 1,
                        lastEventUptimeMs = SystemClock.uptimeMillis(),
                        lastNote = if (ev.command == 0x90 && ev.data2 > 0) d1 else it.lastNote
                    ) else it
                }
                // Mode ER-1 : chaque note frappée crée/alimente sa propre piste.
                if (ev.command == 0x90 && ev.data2 > 0) {
                    val now = SystemClock.uptimeMillis()
                    val cur = noteTracks.value
                    val existing = cur.find { it.note == d1 }
                    noteTracks.value = if (existing != null) {
                        cur.map {
                            if (it.note == d1) it.copy(eventCount = it.eventCount + 1, lastEventUptimeMs = now)
                            else it
                        }
                    } else {
                        (cur + NoteTrackUiState(note = d1, eventCount = 1, lastEventUptimeMs = now))
                            .sortedBy { it.note }
                    }
                }
                revision.value++
            }
        }
    }

    // ----- Lecture -----

    fun startPlayback() {
        if (!hasOutput()) {
            statusMessage.value = "Pas d'entrée MIDI pour la lecture"
            return
        }
        if (recordedLengthNanos == 0L) return
        stopPlayback()
        val byNote = noteMode.value
        val audible = audibleChannels()
        val audNotes = if (byNote) audibleNotes() else emptySet()
        val all = trackEvents
            .flatMapIndexed { ch, list ->
                if (!byNote && ch !in audible) emptyList()
                else synchronized(list) { list.toList() }
            }
            .filter { ev ->
                // En mode note : on filtre les note-on/note-off par note audible,
                // les autres messages (CC, NRPN, pitch bend…) passent toujours.
                !byNote || (ev.command != 0x90 && ev.command != 0x80) || ev.data1 in audNotes
            }
            .map { applyTranspose(maybeSwing(maybeQuantize(it))) }
            .sortedBy { it.timeNanos }
        if (all.isEmpty()) return
        isPlaying.value = true
        playJob = scope.launch {
            val looping = loopBars.value > 0
            val cycle = recordedLengthNanos.coerceAtLeast(1)
            val buf = ByteArray(3)
            val startAll = System.nanoTime()
            var base = startAll
            // Tête de lecture fluide pour l'affichage
            val ticker = launch {
                while (isPlaying.value) {
                    elapsedNanos.value = (System.nanoTime() - startAll) % cycle
                    delay(33)
                }
            }
            do {
                for (ev in all) {
                    val target = base + ev.timeNanos
                    val waitMs = (target - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) delay(waitMs)
                    if (!isPlaying.value) break
                    buf[0] = ev.status.toByte(); buf[1] = ev.data1.toByte(); buf[2] = ev.data2.toByte()
                    val len = if (ev.command == 0xC0 || ev.command == 0xD0) 2 else 3
                    sendBytes(buf, 0, len)
                }
                base += cycle
            } while (isPlaying.value && looping)
            ticker.cancel()
            allNotesOff()
            isPlaying.value = false
            elapsedNanos.value = recordedLengthNanos
        }
    }

    fun stopPlayback() {
        isPlaying.value = false
        playJob?.cancel(); playJob = null
        allNotesOff()
    }

    private fun allNotesOff() {
        if (!hasOutput()) return
        val buf = ByteArray(3)
        for (ch in 0..15) {
            buf[0] = (0xB0 or ch).toByte(); buf[1] = 123.toByte(); buf[2] = 0
            sendBytes(buf, 0, 3)
        }
    }

    fun release() {
        disconnect()
        runCatching { toneGen?.release() }
        toneGen = null
    }
}
