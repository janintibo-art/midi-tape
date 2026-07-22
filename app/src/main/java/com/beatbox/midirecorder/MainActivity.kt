package com.beatbox.midirecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatbox.midirecorder.midi.MidiEngine
import com.beatbox.midirecorder.midi.MidiFileWriter
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----- Palette accordée au logo (bleu-noir #06070C, orange #F88E16) OU rose ER-1 -----
private var pinkMode by mutableStateOf(false)

private val Bg: Color get() = if (pinkMode) Color(0xFF190A11) else Color(0xFF06070C)
private val Panel: Color get() = if (pinkMode) Color(0xFF261019) else Color(0xFF10131C)
private val PanelLine: Color get() = if (pinkMode) Color(0xFF43202F) else Color(0xFF1F2433)
private val Ink: Color get() = if (pinkMode) Color(0xFFFFE9F2) else Color(0xFFF2F4F8)
private val InkDim: Color get() = if (pinkMode) Color(0xFFB37E96) else Color(0xFF848CA3)
private val RecRed = Color(0xFFFF3B4A)
private val PlayGreen = Color(0xFF3DDC84)
private val Amber: Color get() = if (pinkMode) Color(0xFFFF4F9A) else Color(0xFFF88E16)

/** 16 teintes, une par canal, pour repérer chaque piste d'un coup d'œil. */
private val ChannelColors = List(16) { i ->
    Color.hsv(hue = (i * 360f / 16f), saturation = 0.72f, value = 0.95f)
}

class MainActivity : ComponentActivity() {

    private lateinit var engine: MidiEngine
    private lateinit var audio: com.beatbox.midirecorder.midi.AudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = MidiEngine(this)
        audio = com.beatbox.midirecorder.midi.AudioEngine(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel)) {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1400)
                    showSplash = false
                }
                Box {
                    AppScreen(engine, audio, onExport = { exportMidi() }, onShare = { shareMidi() })
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(450))) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize().background(Bg)
                        ) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.logo_full),
                                contentDescription = "Fab La Grosse Basse",
                                modifier = Modifier.fillMaxWidth(0.82f)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun exportMidi() {
        val hasEvents = engine.trackEvents.any { it.isNotEmpty() }
        if (!hasEvents) {
            Toast.makeText(this, "Rien à exporter : enregistrez d'abord.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        val file = File(dir, "take_$stamp.mid")
        runCatching {
            MidiFileWriter.write(file, engine.quantizedCopy(), engine.bpm.value)
        }.onSuccess {
            Toast.makeText(this, "Exporté : ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "Échec de l'export : ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Écrit le .mid dans le cache et ouvre la feuille de partage Android. */
    private fun shareMidi() {
        val hasEvents = engine.trackEvents.any { it.isNotEmpty() }
        if (!hasEvents) {
            Toast.makeText(this, "Rien à partager : enregistrez d'abord.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val file = File(cacheDir, "fab_$stamp.mid")
            MidiFileWriter.write(file, engine.quantizedCopy(), engine.bpm.value)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "com.beatbox.midirecorder.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/midi"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Partager la prise MIDI"))
        }.onFailure {
            Toast.makeText(this, "Échec du partage : ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        engine.release()
        audio.release()
        super.onDestroy()
    }
}

@Composable
private fun AppScreen(
    engine: MidiEngine,
    audio: com.beatbox.midirecorder.midi.AudioEngine,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val devices by engine.devices.collectAsState()
    val connected by engine.connectedName.collectAsState()
    val recording by engine.isRecording.collectAsState()
    val playing by engine.isPlaying.collectAsState()
    val elapsed by engine.elapsedNanos.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val bpm by engine.bpm.collectAsState()
    val revision by engine.revision.collectAsState()
    val status by engine.statusMessage.collectAsState()
    val lastSignal by engine.lastSignalMs.collectAsState()
    val metro by engine.metroTick.collectAsState()
    val countIn by engine.countIn.collectAsState()
    val overdubOn by engine.overdub.collectAsState()
    val noteMode by engine.noteMode.collectAsState()
    val noteTracks by engine.noteTracks.collectAsState()
    val syncOn by engine.syncEnabled.collectAsState()
    val lastClock by engine.lastClockMs.collectAsState()
    val quant by engine.quantize.collectAsState()
    val loopBars by engine.loopBars.collectAsState()
    val clickOn by engine.clickEnabled.collectAsState()
    val masterOn by engine.masterClock.collectAsState()
    val audioRec by audio.isRecording.collectAsState()

    // Prise combinée : audio + MIDI démarrés/arrêtés ensemble
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val audioDir = remember { ctx.getExternalFilesDir(null) ?: ctx.filesDir }
    val comboLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { audio.startRecording(audioDir); engine.startRecording() }
    }
    val onCombo: () -> Unit = {
        if (recording || audioRec) {
            engine.stopRecording(); audio.stopRecording()
        } else {
            val ok = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok) { audio.startRecording(audioDir); engine.startRecording() }
            else comboLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    // Lecture combinée : la dernière prise audio + le MIDI, ensemble
    val audioPlaying by audio.isPlaying.collectAsState()
    // L'écran reste allumé pendant les prises et la lecture
    val keepScreenOn = recording || playing || audioRec || audioPlaying
    LaunchedEffect(keepScreenOn) {
        val w = (ctx as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (keepScreenOn) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    val onComboPlay: () -> Unit = {
        if (playing || audioPlaying) {
            engine.stopPlayback(); audio.stopPlayback()
        } else {
            audio.listRecordings(audioDir).firstOrNull()?.let { audio.play(it) }
            engine.startPlayback()
        }
    }

    // Horloge UI pour faire pâlir les LED d'activité.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = android.os.SystemClock.uptimeMillis()
            delay(50)
        }
    }

    var showHelp by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    // Éditeur : (true = par note, false = par canal, identifiant)
    var editTarget by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }

    if (showHelp) {
        HelpScreen(onClose = { showHelp = false })
        return
    }
    if (showSessions) {
        SessionsScreen(engine, onClose = { showSessions = false })
        return
    }
    editTarget?.let { (isNote, id) ->
        EditorScreen(engine, isNote, id, revision, onClose = { editTarget = null })
        return
    }

    val pagerState = rememberPagerState(pageCount = { 8 })
    val uiScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Onglets : on peut aussi balayer de droite à gauche
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            listOf("STUDIO", "LIVE", "PIANO", "MIX", "AUDIO", "SET", "KORG", "SONS").forEachIndexed { i, label ->
                ModeChip(label, selected = pagerState.currentPage == i) {
                    uiScope.launch { pagerState.animateScrollToPage(i) }
                }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 ->
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item { Header(devices.size, connected, status, lastSignal, nowMs, engine, onHelp = { showHelp = true }) }
        item {
        Transport(
            recording = recording,
            playing = playing,
            elapsedNanos = elapsed,
            bpm = bpm,
            connected = connected != null,
            metroOn = metro,
            countIn = countIn,
            overdubOn = overdubOn,
            syncOn = syncOn,
            clockAlive = nowMs - lastClock < 300,
            quant = quant,
            loopBars = loopBars,
            clickOn = clickOn,
            masterOn = masterOn,
            comboActive = recording && audioRec,
            comboPlayActive = playing && audioPlaying,
            onMaster = { engine.toggleMasterClock() },
            onCombo = onCombo,
            onComboPlay = onComboPlay,
            onSync = { engine.toggleSync() },
            onQuantize = { engine.setQuantize(it) },
            onLoop = { engine.setLoopBars(it) },
            onClick = { engine.toggleClick() },
            onSessions = { showSessions = true },
            onShare = onShare,
            onRec = { if (recording) engine.stopRecording() else engine.startRecordingWithCountIn() },
            onPlay = { if (playing) engine.stopPlayback() else engine.startPlayback() },
            onClear = { engine.clearAll() },
            onExport = onExport,
            onOverdub = { engine.toggleOverdub() },
            onBpm = { engine.bpm.value = it.coerceIn(20, 300) }
        )
        }
        // Sélecteur de mode : par canaux (classique) ou par notes (ER-1)
        item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PISTES", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            ModeChip("CANAUX", selected = !noteMode) { if (noteMode) engine.toggleNoteMode() }
            Spacer(Modifier.width(6.dp))
            ModeChip("ER-1", selected = noteMode) { if (!noteMode) engine.toggleNoteMode() }
        }
        }
            if (noteMode) {
                if (noteTracks.isEmpty()) {
                    item {
                        Text(
                            "Mode ER-1 : chaque son (kick, snare, hi-hat…) aura sa piste.\nLance REC et joue un pattern : les pistes apparaîtront ici.",
                            color = InkDim, fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                items(noteTracks, key = { it.note }) { t ->
                    NoteTrackRow(
                        state = t,
                        color = ChannelColors[t.note % 16],
                        engine = engine,
                        lengthNanos = maxOf(engine.lengthNanos, elapsed, 1L),
                        nowMs = nowMs,
                        revision = revision,
                        onMute = { engine.toggleNoteMute(t.note) },
                        onSolo = { engine.toggleNoteSolo(t.note) },
                        onEdit = { editTarget = true to t.note },
                        playheadNanos = if (playing) elapsed else -1L
                    )
                }
            } else {
                items(tracks, key = { it.channel }) { t ->
                    TrackRow(
                        state = t,
                        color = ChannelColors[t.channel],
                        events = engine.trackEvents[t.channel],
                        lengthNanos = maxOf(engine.lengthNanos, elapsed, 1L),
                        nowMs = nowMs,
                        revision = revision,
                        onMute = { engine.toggleMute(t.channel) },
                        onSolo = { engine.toggleSolo(t.channel) },
                        onEdit = { editTarget = false to t.channel },
                        playheadNanos = if (playing) elapsed else -1L
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
    }
                1 -> LivePadsPage(engine)
                2 -> PianoRollPage(engine, revision)
                3 -> MixerPage(engine)
                4 -> AudioPage(audio)
                5 -> SetlistPage(engine)
                6 -> VirtualKorgPage(engine)
                7 -> SysexPage(engine)
            }
        }
    }
}

@Composable
private fun Header(
    deviceCount: Int,
    connected: String?,
    status: String?,
    lastSignal: Long,
    nowMs: Long,
    engine: MidiEngine,
    onHelp: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // LED "signal" : brille fort si un octet MIDI est arrivé il y a moins de 150 ms.
    val signalOn = nowMs - lastSignal < 150
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        // Ligne 1 : titre court + LED + boutons
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "FAB",
                color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black,
                letterSpacing = 5.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                " —",
                color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(8.dp))
            // Pastille "signal reçu"
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (signalOn) PlayGreen else PanelLine)
            )
            Spacer(Modifier.weight(1f))
            // Bouton Aide
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onHelp)
            ) {
                Text("?", color = InkDim, fontSize = 15.sp, fontWeight = FontWeight.Black)
            }
            // Interrupteur thème rose ER-1
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (pinkMode) Color(0xFFFF4F9A) else PanelLine)
                    .clickable { pinkMode = !pinkMode }
            ) {
                Text("R", color = if (pinkMode) Color(0xFF190A11) else InkDim,
                    fontSize = 14.sp, fontWeight = FontWeight.Black)
            }
        }
        // Ligne 2 : sous-titre en pleine largeur, plus rien ne le coupe
        Text(
            "LA GROSSE BASSE",
            color = InkDim, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        // Ligne 3 : état + bouton Connecter
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                status ?: connected ?: "Aucun appareil",
                color = if (connected != null) PlayGreen else InkDim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
        Box {
            OutlinedButton(
                onClick = { engine.refreshDevices(); menuOpen = true },
                shape = RoundedCornerShape(10.dp),
                border = ButtonDefaults.outlinedButtonBorder,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) {
                Text(if (connected != null) "Appareils" else "Connecter ($deviceCount)", fontSize = 13.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val list = engine.devices.value
                val open = engine.connectedDevices.value
                if (list.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Aucun appareil USB/BT détecté") },
                        onClick = { menuOpen = false }
                    )
                }
                // On peut en connecter plusieurs : le menu reste ouvert
                list.forEach { info ->
                    val isOpen = open.any { it.first == info.id }
                    DropdownMenuItem(
                        text = {
                            Text(
                                (if (isOpen) "✓ " else "+ ") + engine.deviceLabel(info),
                                color = if (isOpen) PlayGreen else Ink
                            )
                        },
                        onClick = {
                            if (isOpen) engine.disconnectDevice(info.id) else engine.connect(info)
                        }
                    )
                }
                if (open.isNotEmpty()) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Tout déconnecter", color = RecRed) },
                        onClick = { engine.disconnect(); menuOpen = false }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Fermer", color = InkDim) },
                    onClick = { menuOpen = false }
                )
            }
        }
        }
    }
}

@Composable
private fun Transport(
    recording: Boolean,
    playing: Boolean,
    elapsedNanos: Long,
    bpm: Int,
    connected: Boolean,
    metroOn: Boolean,
    countIn: Int,
    overdubOn: Boolean,
    syncOn: Boolean,
    clockAlive: Boolean,
    quant: Int,
    loopBars: Int,
    clickOn: Boolean,
    masterOn: Boolean,
    comboActive: Boolean,
    comboPlayActive: Boolean,
    onMaster: () -> Unit,
    onCombo: () -> Unit,
    onComboPlay: () -> Unit,
    onSync: () -> Unit,
    onQuantize: (Int) -> Unit,
    onLoop: (Int) -> Unit,
    onClick: () -> Unit,
    onSessions: () -> Unit,
    onShare: () -> Unit,
    onRec: () -> Unit,
    onPlay: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onOverdub: () -> Unit,
    onBpm: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        // Point de métronome + compteur (ou compte à rebours)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (metroOn) Amber else PanelLine)
            )
            Spacer(Modifier.width(14.dp))
            if (countIn > 0) {
                Text(
                    "· $countIn ·",
                    color = Amber, fontSize = 40.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            } else {
                val totalMs = elapsedNanos / 1_000_000
                val min = totalMs / 60_000
                val sec = (totalMs / 1000) % 60
                val cent = (totalMs / 10) % 100
                Text(
                    String.format(Locale.US, "%02d:%02d.%02d", min, sec, cent),
                    color = if (recording) RecRed else Ink,
                    fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // REC
            PadButton(
                label = if (recording) "STOP" else "REC",
                active = recording,
                activeColor = RecRed,
                enabled = connected || recording,
                modifier = Modifier.weight(1f),
                pulse = true,
                onClick = onRec
            )
            // PLAY
            PadButton(
                label = if (playing) "STOP" else "PLAY",
                active = playing,
                activeColor = PlayGreen,
                enabled = connected && !recording,
                modifier = Modifier.weight(1f),
                onClick = onPlay
            )
            // BPM + Tap
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BPM", color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stepper("−") { onBpm(bpm - 1) }
                    Text(
                        "$bpm", color = Amber, fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Stepper("+") { onBpm(bpm + 1) }
                }
                Spacer(Modifier.height(4.dp))
                // Tap tempo : moyenne des intervalles entre les 4 derniers taps
                val taps = remember { mutableStateListOf<Long>() }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelLine)
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                            taps.add(now)
                            while (taps.size > 4) taps.removeAt(0)
                            if (taps.size >= 2) {
                                val gaps = taps.zipWithNext { a, b -> b - a }
                                val avg = gaps.average()
                                if (avg > 0) onBpm((60000.0 / avg).toInt())
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("TAP", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Prise et lecture combinées : le son (interface USB) + le MIDI, synchronisés
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(
                label = if (comboActive) "■ A+M" else "● REC A+M",
                active = comboActive,
                activeColor = RecRed,
                enabled = true,
                modifier = Modifier.weight(1f).height(44.dp),
                pulse = true,
                onClick = onCombo
            )
            PadButton(
                label = if (comboPlayActive) "■ A+M" else "▶ PLAY A+M",
                active = comboPlayActive,
                activeColor = PlayGreen,
                enabled = true,
                modifier = Modifier.weight(1f).height(44.dp),
                onClick = onComboPlay
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            TextButton(onClick = onClear) { Text("Effacer", color = InkDim, maxLines = 1) }
            // Overdub : réenregistre par-dessus sans effacer les autres pistes
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (overdubOn) PlayGreen else PanelLine)
                    .clickable(onClick = onOverdub)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "OVERDUB",
                    color = if (overdubOn) Bg else InkDim,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onExport) { Text("Export .mid", color = Amber, maxLines = 1) }
        }
        Spacer(Modifier.height(6.dp))
        // Sync horloge machine + quantize (défilable si l'écran est étroit)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            // SYNC : suit l'horloge de la machine (BPM auto, REC calé sur Start/Stop)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (syncOn) PlayGreen else PanelLine)
                    .clickable(onClick = onSync)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // Petite LED : clignote quand des ticks d'horloge arrivent
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                clockAlive -> Amber
                                syncOn -> Bg
                                else -> InkDim
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "SYNC",
                    color = if (syncOn) Bg else InkDim,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
            ModeChip("MASTER", selected = masterOn) { onMaster() }
            Spacer(Modifier.width(12.dp))
            Text("QUANT", color = InkDim, fontSize = 10.sp, letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace)
            ModeChip("OFF", selected = quant == 0) { onQuantize(0) }
            ModeChip("1/8", selected = quant == 8) { onQuantize(8) }
            ModeChip("1/16", selected = quant == 16) { onQuantize(16) }
            ModeChip("1/32", selected = quant == 32) { onQuantize(32) }
        }
        Spacer(Modifier.height(6.dp))
        // Boucle pattern + clic sonore (défilable si l'écran est étroit)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text("LOOP", color = InkDim, fontSize = 10.sp, letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace)
            ModeChip("LIBRE", selected = loopBars == 0) { onLoop(0) }
            ModeChip("2", selected = loopBars == 2) { onLoop(2) }
            ModeChip("4", selected = loopBars == 4) { onLoop(4) }
            ModeChip("8", selected = loopBars == 8) { onLoop(8) }
            Spacer(Modifier.width(12.dp))
            ModeChip(if (clickOn) "CLIC ♪" else "CLIC ✕", selected = clickOn) { onClick() }
        }
        Spacer(Modifier.height(2.dp))
        // Sessions + partage
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSessions) { Text("Sessions", color = InkDim) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShare) { Text("Partager", color = Amber) }
        }
    }
}

@Composable
private fun Stepper(symbol: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(PanelLine)
            .clickable(onClick = onClick)
    ) {
        Text(symbol, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PadButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
    onClick: () -> Unit
) {
    // Battement lumineux quand actif (REC)
    val pulseAlpha = if (active && pulse) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.55f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
            label = "pulseA"
        ).value
    } else 1f
    val bg = when {
        active -> activeColor.copy(alpha = pulseAlpha)
        enabled -> PanelLine
        else -> PanelLine.copy(alpha = 0.4f)
    }
    val fg = when {
        active -> Bg
        enabled -> Ink
        else -> InkDim
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Black,
            letterSpacing = 3.sp, fontFamily = FontFamily.Monospace
        )
    }
}

private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun noteName(n: Int): String =
    if (n < 0) "—" else "${NoteNames[n % 12]}${n / 12 - 1}"

/** Nom probable du son pour une note de batterie (mapping type GM, réglable sur l'ER-1). */
private fun drumHint(n: Int): String = when (n) {
    35, 36 -> "Kick"
    37 -> "Rimshot"
    38, 40 -> "Snare"
    39 -> "Clap"
    41, 43, 45, 47, 48, 50 -> "Tom"
    42 -> "HH fermé"
    44 -> "HH pédale"
    46 -> "HH ouvert"
    49, 57 -> "Crash"
    51, 59 -> "Ride"
    54 -> "Tambourin"
    56 -> "Cowbell"
    70 -> "Maracas"
    else -> "Perc"
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Amber else PanelLine)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) Bg else InkDim,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun NoteTrackRow(
    state: com.beatbox.midirecorder.midi.NoteTrackUiState,
    color: Color,
    engine: MidiEngine,
    lengthNanos: Long,
    nowMs: Long,
    revision: Int,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onEdit: () -> Unit,
    playheadNanos: Long = -1L
) {
    val sinceMs = nowMs - state.lastEventUptimeMs
    val ledAlpha = if (state.lastEventUptimeMs == 0L) 0.15f
    else (1f - (sinceMs / 400f)).coerceIn(0.15f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = ledAlpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(78.dp)) {
            Text(
                drumHint(state.note),
                color = if (state.muted) InkDim else Ink,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                "${noteName(state.note)} · ${state.eventCount} evt",
                color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clickable(onClick = onEdit)
        ) {
            @Suppress("UNUSED_EXPRESSION") revision
            drawLine(
                color = PanelLine,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.5f
            )
            for (ev in engine.snapshotNoteEvents(state.note)) {
                val x = (ev.timeNanos.toFloat() / lengthNanos.toFloat()) * size.width
                val h = (ev.data2 / 127f) * (size.height * 0.9f)
                drawLine(
                    color = if (state.muted) color.copy(alpha = 0.25f) else color,
                    start = Offset(x, size.height / 2 + h / 2),
                    end = Offset(x, size.height / 2 - h / 2),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            // Tête de lecture
            if (playheadNanos in 0..lengthNanos) {
                val px = (playheadNanos.toFloat() / lengthNanos.toFloat()) * size.width
                drawLine(Amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5f)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.soloed) PlayGreen else PanelLine)
                .clickable(onClick = onSolo)
        ) {
            Text("S", color = if (state.soloed) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.muted) Amber else PanelLine)
                .clickable(onClick = onMute)
        ) {
            Text("M", color = if (state.muted) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TrackRow(
    state: com.beatbox.midirecorder.midi.TrackUiState,
    color: Color,
    events: List<com.beatbox.midirecorder.midi.RecordedEvent>,
    lengthNanos: Long,
    nowMs: Long,
    revision: Int,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onEdit: () -> Unit,
    playheadNanos: Long = -1L
) {
    val sinceMs = nowMs - state.lastEventUptimeMs
    val ledAlpha = if (state.lastEventUptimeMs == 0L) 0.15f
    else (1f - (sinceMs / 400f)).coerceIn(0.15f, 1f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // LED d'activité
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = ledAlpha))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(78.dp)) {
            Text(
                String.format(Locale.US, "CH %02d", state.channel + 1),
                color = if (state.muted) InkDim else Ink,
                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
            Text(
                "${state.eventCount} evt · ${noteName(state.lastNote)}",
                color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
        // Mini timeline des frappes enregistrées (appuyer pour éditer)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(28.dp)
                .clickable(onClick = onEdit)
        ) {
            // 'revision' force le redessin quand de nouveaux événements arrivent
            @Suppress("UNUSED_EXPRESSION") revision
            drawLine(
                color = PanelLine,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.5f
            )
            val snapshot = synchronized(events) { events.toList() }
            for (ev in snapshot) {
                if (ev.command != 0x90 || ev.data2 == 0) continue
                val x = (ev.timeNanos.toFloat() / lengthNanos.toFloat()) * size.width
                val h = (ev.data2 / 127f) * (size.height * 0.9f)
                drawLine(
                    color = if (state.muted) color.copy(alpha = 0.25f) else color,
                    start = Offset(x, size.height / 2 + h / 2),
                    end = Offset(x, size.height / 2 - h / 2),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            // Tête de lecture
            if (playheadNanos in 0..lengthNanos) {
                val px = (playheadNanos.toFloat() / lengthNanos.toFloat()) * size.width
                drawLine(Amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5f)
            }
        }
        Spacer(Modifier.width(8.dp))
        // Bouton SOLO
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.soloed) PlayGreen else PanelLine)
                .clickable(onClick = onSolo)
        ) {
            Text(
                "S",
                color = if (state.soloed) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black
            )
        }
        Spacer(Modifier.width(6.dp))
        // Bouton MUTE
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (state.muted) Amber else PanelLine)
                .clickable(onClick = onMute)
        ) {
            Text(
                "M",
                color = if (state.muted) Bg else InkDim,
                fontSize = 13.sp, fontWeight = FontWeight.Black
            )
        }
    }
}

// ================= ÉCRAN D'AIDE =================

@Composable
private fun HelpTitle(text: String) {
    Text(
        text, color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Black,
        letterSpacing = 1.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun HelpText(text: String) {
    Text(text, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun HelpScreen(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text(
                "AIDE", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black,
                letterSpacing = 3.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onClose)
            ) {
                Text("✕", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            // Le logo, en vitrine
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.logo_full),
                contentDescription = "Logo Fab La Grosse Basse",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            HelpTitle("DÉMARRAGE RAPIDE")
            HelpText(
                "1. Branche la boîte à rythmes en USB (câble OTG ou interface USB-C) ou en Bluetooth MIDI.\n" +
                "2. Appuie sur « Connecter » et choisis ta machine (tu peux en cocher " +
                "PLUSIEURS avec un hub USB alimenté). La pastille verte à côté " +
                "du titre clignote dès qu'un signal MIDI arrive : c'est ton témoin de câblage.\n" +
                "3. Appuie sur REC : un compte à rebours de 4 temps démarre, puis joue ton pattern.\n" +
                "4. STOP pour finir, PLAY pour réécouter sur la machine, " +
                "« Export .mid » pour le fichier, ou « Partager » pour l'envoyer direct.\n\n" +
                "Le gros bouton ● REC MIDI + AUDIO lance les DEUX enregistrements en même " +
                "temps (les notes + le vrai son via l'interface USB), parfaitement synchronisés."
            )

            HelpTitle("LES 5 PAGES")
            HelpText(
                "Balaye l'écran de droite à gauche (ou tape les onglets en haut) :\n\n" +
                "STUDIO — le poste de commande : transport, pistes, réglages.\n\n" +
                "LIVE — 16 pads tactiles façon MPC pour jouer la machine depuis le " +
                "téléphone. Tape = la note part, maintiens = note tenue. Choisis le canal " +
                "et l'octave en haut.\n\n" +
                "PIANO — le piano roll façon FL Studio : clavier piano à gauche avec " +
                "repères d'octaves, grille en double-croches, notes en barres. Tape une " +
                "case vide pour poser une note, tape une note pour l'effacer. La bande " +
                "du bas montre les VÉLOCITÉS : tire un trait du doigt pour régler la " +
                "force de chaque note. Les canaux marqués ● contiennent des notes. " +
                "▲▼ OCT pour naviguer. GRILLE règle la finesse : pas (1/16), demi-pas " +
                "(1/32) ou quart de pas (1/64) pour les roulements et les nuances. " +
                "En bas, le GÉNÉRATEUR EUCLIDIEN répartit des " +
                "coups régulièrement (5 sur 16 = groove latin, 3 sur 8 = rythme " +
                "africain) et HUMANISER enlève le côté robot.\n\n" +
                "MIX — la mixette : volume et pan par piste envoyés en direct à la machine, " +
                "transposition en demi-tons, et le curseur GROOVE (swing).\n\n" +
                "AUDIO — enregistre le SON de la machine en WAV via l'interface USB, " +
                "avec vumètre, spectre animé, égaliseur et mastering.\n\n" +
                "SET — mode concert : enchaîne ta setlist avec PRÉC./SUIV. La section " +
                "SONG construit la structure d'un morceau (A-A-B-A…) : ajoute des " +
                "parties avec +Song, règle leurs répétitions, et JOUER LE MORCEAU " +
                "les enchaîne tout seul.\n\n" +
                "KORG — la télécommande : Start/Stop de la machine, saut de pattern " +
                "(Program Change), curseurs CC assignables et potard NRPN. Le bouton " +
                "APPR. (MIDI Learn) fait le réglage tout seul : appuie dessus, tourne " +
                "le potard voulu sur la machine, il lui est assigné. On y trouve aussi la " +
                "liste des APPAREILS USB (avec leur LED et le choix de la cible " +
                "d'envoi), le ROUTAGE THRU — ce qui arrive d'un appareil est renvoyé " +
                "vers les autres, avec transposition et canal forcé, donc ton clavier " +
                "joue les sons de la machine — et la TÉLÉCOMMANDE : assigne un pad à " +
                "REC, PLAY, STOP ou OVERDUB et pilote l'app sans toucher l'écran.\n\n" +
                "SONS — toute la famille Electribe : les anciennes (ER-1, ES-1, EMX…) " +
                "sauvegardent sons et patterns par dump SysEx ; les récentes (electribe " +
                "2 / sampler) gèrent leurs SAMPLES par carte SD — sors la carte, branche-" +
                "la au téléphone, choisis le dossier KORG, et échange les WAV dans les " +
                "deux sens. Tes prises de la page AUDIO peuvent devenir des samples " +
                "dans la machine !"
            )

            HelpTitle("LES PISTES")
            HelpText(
                "Mode CANAUX : une piste par canal MIDI (1 à 16), le mode classique.\n\n" +
                "Mode ER-1 : fait pour l'Electribe·R, qui envoie tous ses sons sur UN SEUL " +
                "canal et les distingue par numéro de note. Chaque son frappé (Kick, Snare, " +
                "hi-hat…) crée automatiquement sa propre piste.\n\n" +
                "Sur chaque piste : la LED clignote quand le son joue, les traits de la " +
                "timeline montrent chaque frappe (plus le trait est haut, plus c'est fort), " +
                "S = Solo, M = Mute."
            )

            HelpTitle("SYNC, MASTER ET QUANTIZE")
            HelpText(
                "SYNC (esclave) : l'app suit l'horloge MIDI de la machine. Le BPM se règle " +
                "tout seul, et l'enregistrement démarre pile sur le Play de la machine " +
                "(et s'arrête sur son Stop). La LED ambre clignote quand des ticks arrivent.\n\n" +
                "MASTER : l'inverse — l'app ENVOIE son horloge et pilote le tempo de la " +
                "machine (elle démarre sur Start et suit ton BPM). SYNC et MASTER sont " +
                "exclusifs : activer l'un coupe l'autre.\n\n" +
                "QUANT : cale les frappes sur la grille (1/8, 1/16, 1/32) à la lecture et " +
                "à l'export. Réversible : reviens sur OFF pour retrouver le jeu naturel. " +
                "Les mouvements de potards ne sont jamais quantifiés.\n\n" +
                "LOOP : fixe un pattern de 2, 4 ou 8 mesures. L'enregistrement tourne en " +
                "boucle et tout s'empile passage après passage, comme sur l'Electribe. " +
                "CLIC ♪ active ou coupe le métronome sonore."
            )

            HelpTitle("SESSIONS, SECOURS ET PARTAGE")
            HelpText(
                "Sessions : sauvegarde ta prise sous un nom (« Jam mardi »…), recharge-la " +
                "quand tu veux, même après avoir fermé l'app. Tu peux aussi IMPORTER un " +
                "fichier .mid du téléphone pour le rejouer sur la machine, et renommer " +
                "tes prises (MIDI comme audio).\n\n" +
                "Sauvegarde automatique : pendant chaque enregistrement, une session " +
                "« secours-auto » se sauvegarde toutes les 30 secondes et à l'arrêt. " +
                "En cas de plantage : Sessions → secours-auto → Charger.\n\n" +
                "Partager : envoie le .mid par WhatsApp, mail, Drive… Le fichier reprend " +
                "tes réglages de quantize et de groove du moment.\n\n" +
                "Éditeur de frappes : appuie sur la timeline d'une piste (page STUDIO) " +
                "pour ouvrir le grand éditeur — touche une frappe pour l'effacer.\n\n" +
                "Mixette : VOL et PAN partent en direct vers la machine (CC7/CC10) — " +
                "certaines machines les ignorent, vois leur menu MIDI. GROOVE : 50 % = " +
                "droit, 66 % = shuffle classique, appliqué à la lecture et à l'export.\n\n" +
                "Audio : branche la sortie son de la machine sur l'interface USB. " +
                "REC AUDIO capte en WAV ; à la lecture, l'égaliseur apparaît avec un " +
                "curseur par bande (±15 dB).\n\n" +
                "Mastering : les presets (Chaud, Club, Punchy) et les curseurs BASS / " +
                "LOUD / WIDE colorent la lecture. Le bouton « Master » d'une prise crée " +
                "un NOUVEAU fichier _master.wav : basses renforcées, niveau remonté, " +
                "limiteur doux et normalisation — prêt à partager. L'original est gardé " +
                "intact, tu peux refaire un master avec d'autres réglages.\n\n" +
                "FX : effets créatifs — vitesse (change la hauteur comme un vinyle), " +
                "à l'envers, filtre et écho, avec presets. Crée un fichier « _fx ».\n\n" +
                "Couper : ouvre l'éditeur de sample — la zone verte est gardée, le " +
                "reste retiré (fichier « _cut »). Slicer : détecte les coups et crée un " +
                "sample par tranche (« _slice01 »…), parfaits pour la carte SD.\n\n" +
                "Sauvegarde totale (dans Sessions) : un zip avec TOUT — sessions, " +
                "prises, masters, dumps, setlist — à envoyer vers Drive/mail. " +
                "« Restaurer » ramène tout depuis ce zip. « Sauvegarde → clé USB / fichier » " +
                "l'écrit directement où tu veux, y compris sur une clé USB branchée.\n\n" +
                "Page SONS : « Envoyer TOUTES les prises WAV » copie d'un coup toutes " +
                "tes prises vers le dossier choisi (carte SD de l'electribe ou clé USB)."
            )

            HelpTitle("RÉGLER TON ELECTRIBE·R (ER-1)")
            HelpText(
                "Petit guide maison pour que la machine et l'app se parlent bien :\n\n" +
                "• Canal MIDI : d'usine, l'ER-1 émet sur le canal 3. Ça se règle dans le " +
                "mode MIDI de la machine (bouton MIDI, puis molette). Peu importe le canal " +
                "choisi : en mode ER-1, l'app s'en moque, elle trie par son.\n\n" +
                "• Notes des sons : chaque partie (Synth 1 à 4, hi-hats, crash, clap) a un " +
                "numéro de note réglable dans le même menu MIDI. Si un son s'affiche " +
                "« Perc » dans l'app au lieu de « Kick », c'est juste que sa note diffère " +
                "du standard — tout fonctionne quand même.\n\n" +
                "• Horloge : pour que SYNC marche, la machine doit ÉMETTRE son horloge. " +
                "Dans le menu MIDI de l'ER-1, vérifie que Clock est sur interne (int) et " +
                "que l'envoi de l'horloge n'est pas filtré.\n\n" +
                "• Accent : l'ER-1 traduit son accent en vélocité MIDI (de 30 à 127). " +
                "C'est ce qui fait varier la hauteur des traits dans les timelines.\n\n" +
                "• Potards : les mouvements de boutons partent en MIDI (messages NRPN) et " +
                "sont enregistrés puis rejoués par l'app avec le reste."
            )

            HelpTitle("NOTICE OFFICIELLE KORG")
            HelpText(
                "Le mode d'emploi complet en français est téléchargeable gratuitement " +
                "sur le site de Korg (cherche « ER-1 » dans les téléchargements) :"
            )
            Spacer(Modifier.height(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Amber)
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.korg.com/fr/support/download/"))
                            )
                        }
                    }
                    .padding(vertical = 14.dp)
            ) {
                Text(
                    "Ouvrir le site Korg (notice ER-1)",
                    color = Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
            }

            HelpTitle("DÉPANNAGE EXPRESS")
            HelpText(
                "Pas de pastille verte quand tu joues ? Vérifie le câble OTG, puis que le " +
                "téléphone a bien autorisé l'accès USB (débranche/rebranche pour revoir la " +
                "demande).\n\n" +
                "PLAY ou les pads LIVE ne font rien sur la machine ? Il faut que le câble " +
                "aille aussi VERS la machine (MIDI IN) et que ses sons répondent aux notes " +
                "entrantes. Sur les pads, vérifie aussi le canal et l'octave.\n\n" +
                "La LED ambre du SYNC ne clignote pas ? La machine n'envoie pas son " +
                "horloge : vois le réglage Clock dans son menu MIDI.\n\n" +
                "MASTER ne pilote pas la machine ? Son horloge doit être réglée sur " +
                "externe (ext) dans son menu MIDI.\n\n" +
                "REC AUDIO capte le micro au lieu de la machine ? Page AUDIO → SOURCE : " +
                "choisis l'entrée USB (ou laisse AUTO, qui prend l'USB dès qu'elle est " +
                "branchée). La ligne verte « Dernière prise via » confirme la source.\n\n" +
                "REC AUDIO n'enregistre que du silence ? Vérifie que la sortie son de la " +
                "machine entre bien dans l'interface USB, que le volume de la machine est " +
                "monté, et que la permission micro est accordée. Le vumètre doit bouger " +
                "quand la machine joue."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ================= ÉCRAN SESSIONS =================

@Composable
private fun SessionsScreen(engine: MidiEngine, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(com.beatbox.midirecorder.midi.SessionStore.list(ctx.filesDir)) }
    var renaming by remember { mutableStateOf<File?>(null) }

    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    val audioDirS = remember { ctx.getExternalFilesDir(null) ?: ctx.filesDir }

    // Écriture directe de la sauvegarde (clé USB, carte SD, Drive…)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val tmp = File(ctx.cacheDir, "backup_tmp.zip")
                com.beatbox.midirecorder.midi.SessionStore.backupAll(ctx.filesDir, audioDirS, tmp)
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
                tmp.delete()
            }.onSuccess {
                android.widget.Toast.makeText(ctx, "Sauvegarde écrite !", android.widget.Toast.LENGTH_LONG).show()
            }.onFailure {
                android.widget.Toast.makeText(ctx, "Écriture impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Restauration d'une sauvegarde totale
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    com.beatbox.midirecorder.midi.SessionStore.restoreAll(ctx.filesDir, audioDirS, input)
                } ?: 0
            }.onSuccess { n ->
                files = com.beatbox.midirecorder.midi.SessionStore.list(ctx.filesDir)
                android.widget.Toast.makeText(ctx, "$n fichier(s) restauré(s) !", android.widget.Toast.LENGTH_LONG).show()
            }.onFailure {
                android.widget.Toast.makeText(ctx, "Restauration impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Import d'un fichier .mid depuis le téléphone
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val r = com.beatbox.midirecorder.midi.MidiFileReader.read(input)
                    engine.restore(r.events, r.lengthNanos, r.bpm)
                }
            }.onSuccess {
                android.widget.Toast.makeText(ctx, "Fichier .mid importé !", android.widget.Toast.LENGTH_SHORT).show()
                onClose()
            }.onFailure {
                android.widget.Toast.makeText(ctx, "Import impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    renaming?.let { f ->
        RenameDialog(
            initial = f.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                val safe = newName.trim().ifEmpty { f.nameWithoutExtension }
                    .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                f.renameTo(File(f.parentFile, "$safe.${f.extension}"))
                files = com.beatbox.midirecorder.midi.SessionStore.list(ctx.filesDir)
                renaming = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Text(
                "SESSIONS", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black,
                letterSpacing = 3.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onClose)
            ) {
                Text("✕", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Sauvegarder la prise en cours
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nom de la prise (ex : Jam mardi)") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedBorderColor = Amber, unfocusedBorderColor = PanelLine,
                focusedLabelColor = Amber, unfocusedLabelColor = InkDim,
                cursorColor = Amber
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Amber)
                .clickable {
                    val events = engine.allEvents()
                    if (events.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "Rien à sauvegarder : enregistrez d'abord.", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        com.beatbox.midirecorder.midi.SessionStore.save(
                            ctx.filesDir, name, events, engine.lengthNanos, engine.bpm.value
                        )
                        files = com.beatbox.midirecorder.midi.SessionStore.list(ctx.filesDir)
                        name = ""
                        android.widget.Toast.makeText(ctx, "Prise sauvegardée !", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 13.dp)
        ) {
            Text("Sauvegarder la prise en cours", color = Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelLine)
                .clickable { importLauncher.launch("*/*") }
                .padding(vertical = 13.dp)
        ) {
            Text("Importer un fichier .mid", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelLine)
                    .clickable {
                        runCatching {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            val out = File(ctx.cacheDir, "fabkorg_sauvegarde_$stamp.zip")
                            com.beatbox.midirecorder.midi.SessionStore.backupAll(ctx.filesDir, audioDirS, out)
                            shareFile(ctx, out, "application/zip")
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "Sauvegarde impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                    .padding(vertical = 12.dp)
            ) {
                Text("Sauvegarde totale", color = PlayGreen, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelLine)
                    .clickable { restoreLauncher.launch("application/zip") }
                    .padding(vertical = 12.dp)
            ) {
                Text("Restaurer", color = Amber, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(PanelLine)
                .clickable {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    exportBackupLauncher.launch("fabkorg_sauvegarde_$stamp.zip")
                }
                .padding(vertical = 12.dp)
        ) {
            Text("Sauvegarde → clé USB / fichier", color = Ink, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PRISES SAUVEGARDÉES", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            ModeChip("DATE", selected = !sortByName) { sortByName = false }
            Spacer(Modifier.width(6.dp))
            ModeChip("NOM", selected = sortByName) { sortByName = true }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Rechercher…", fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedBorderColor = Amber, unfocusedBorderColor = PanelLine,
                focusedLabelColor = Amber, unfocusedLabelColor = InkDim,
                cursorColor = Amber
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        val shown = files
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { if (sortByName) it.sortedBy { f -> f.name.lowercase() } else it }
        if (shown.isEmpty()) {
            Text("Aucune prise.", color = InkDim, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(shown, key = { it.absolutePath }) { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        f.nameWithoutExtension,
                        color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        runCatching {
                            val loaded = com.beatbox.midirecorder.midi.SessionStore.load(f)
                            engine.restore(loaded.events, loaded.length, loaded.bpm)
                            android.widget.Toast.makeText(ctx, "Prise chargée", android.widget.Toast.LENGTH_SHORT).show()
                            onClose()
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "Fichier illisible", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Charger", color = PlayGreen) }
                    TextButton(onClick = {
                        com.beatbox.midirecorder.midi.SessionStore.addToSetlist(ctx.filesDir, f.name)
                        android.widget.Toast.makeText(ctx, "Ajouté à la setlist (page SET)", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("+Set", color = Amber) }
                    TextButton(onClick = { renaming = f }) { Text("Ren.", color = InkDim) }
                    TextButton(onClick = {
                        f.delete()
                        com.beatbox.midirecorder.midi.SessionStore.removeFromSetlist(ctx.filesDir, f.name)
                        files = com.beatbox.midirecorder.midi.SessionStore.list(ctx.filesDir)
                    }) { Text("Suppr.", color = RecRed) }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

// ================= ÉCRAN ÉDITEUR =================

@Composable
private fun EditorScreen(
    engine: MidiEngine,
    isNote: Boolean,
    id: Int,
    revision: Int,
    onClose: () -> Unit
) {
    val len = engine.lengthNanos.coerceAtLeast(1)
    // Frappes affichées : par note (mode ER-1) ou par canal
    val hits = remember(revision, isNote, id) {
        if (isNote) engine.snapshotNoteEvents(id)
        else synchronized(engine.trackEvents[id]) {
            engine.trackEvents[id].filter { it.command == 0x90 && it.data2 > 0 }
        }
    }
    val title = if (isNote) "${drumHint(id)} · ${noteName(id)}"
    else String.format(Locale.US, "CANAL %02d", id + 1)
    val color = ChannelColors[(if (isNote) id else id) % 16]
    // Bornes de hauteur pour placer les notes verticalement (mode canal)
    val minNote = hits.minOfOrNull { it.data1 } ?: 0
    val maxNote = hits.maxOfOrNull { it.data1 } ?: 127

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ÉDITEUR", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onClose)
            ) {
                Text("✕", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Appuie sur une frappe pour l'effacer. ${hits.size} frappe(s).",
            color = InkDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                .pointerInput(revision) {
                    detectTapGestures { off ->
                        // Trouver la frappe la plus proche du doigt
                        var best: com.beatbox.midirecorder.midi.RecordedEvent? = null
                        var bestD = Float.MAX_VALUE
                        for (ev in hits) {
                            val x = (ev.timeNanos.toFloat() / len.toFloat()) * size.width
                            val y = if (isNote || maxNote == minNote) size.height / 2f
                            else size.height * (1f - (ev.data1 - minNote).toFloat() / (maxNote - minNote).toFloat() * 0.8f - 0.1f)
                            val d = kotlin.math.hypot(off.x - x, off.y - y)
                            if (d < bestD) { bestD = d; best = ev }
                        }
                        val target = best
                        if (target != null && bestD < 60f) {
                            engine.deleteHit(if (isNote) null else id, target.data1, target.timeNanos)
                        }
                    }
                }
        ) {
            // Grille des temps (4 par mesure au BPM courant)
            val quarter = 60_000_000_000L / engine.bpm.value.coerceIn(20, 300)
            var t = 0L; var beat = 0
            while (t < len) {
                val x = (t.toFloat() / len.toFloat()) * size.width
                drawLine(
                    color = if (beat % 4 == 0) PanelLine else PanelLine.copy(alpha = 0.4f),
                    start = Offset(x, 0f), end = Offset(x, size.height),
                    strokeWidth = if (beat % 4 == 0) 2f else 1f
                )
                t += quarter; beat++
            }
            for (ev in hits) {
                val x = (ev.timeNanos.toFloat() / len.toFloat()) * size.width
                val y = if (isNote || maxNote == minNote) size.height / 2f
                else size.height * (1f - (ev.data1 - minNote).toFloat() / (maxNote - minNote).toFloat() * 0.8f - 0.1f)
                val r = 8f + (ev.data2 / 127f) * 12f
                drawCircle(color = color, radius = r, center = Offset(x, y))
                drawCircle(color = Bg, radius = r * 0.45f, center = Offset(x, y))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Astuce : la taille des points suit la force de frappe. En mode canal, la hauteur suit la note.",
            color = InkDim, fontSize = 11.sp
        )
    }
}

// ================= PAGE PIANO ROLL =================

@Composable
private fun PianoRollPage(engine: MidiEngine, revision: Int) {
    var ch by remember { mutableIntStateOf(0) }
    var baseNote by remember { mutableIntStateOf(36) } // C2 par défaut
    val bpm by engine.bpm.collectAsState()
    val quarter = 60_000_000_000L / bpm.coerceIn(20, 300)
    // Résolution de la grille : 16 = double-croche, 32 = demi-pas, 64 = quart de pas
    var gridDiv by remember { mutableIntStateOf(16) }
    val g16 = (quarter * 4) / gridDiv
    // Longueur : la prise, ou 4 mesures par défaut
    val len = maxOf(engine.lengthNanos, quarter * 16)
    val steps = ((len + g16 - 1) / g16).toInt().coerceIn(gridDiv, 512)
    val lanes = 16
    val bars = remember(revision, ch) { engine.noteBars(ch) }
    val color = ChannelColors[ch]
    val playing by engine.isPlaying.collectAsState()
    val elapsed by engine.elapsedNanos.collectAsState()
    var dragBar by remember { mutableStateOf<MidiEngine.NoteBar?>(null) }
    var dragVel by remember { mutableIntStateOf(0) }
    var velTarget by remember { mutableStateOf<MidiEngine.NoteBar?>(null) }
    val trackStates by engine.tracks.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Text(
            "PIANO ROLL", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        // Choix du canal
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for (c in 0..15) {
                val hasNotes = (trackStates.getOrNull(c)?.eventCount ?: 0) > 0
                ModeChip(
                    if (hasNotes) "●${c + 1}" else "${c + 1}",
                    selected = ch == c
                ) { ch = c }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Octaves + infos
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${noteName(baseNote)} → ${noteName(baseNote + lanes - 1)}",
                color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            ModeChip("▼ OCT", selected = false) { baseNote = (baseNote - 12).coerceAtLeast(0) }
            Spacer(Modifier.width(6.dp))
            ModeChip("▲ OCT", selected = false) { baseNote = (baseNote + 12).coerceAtMost(127 - lanes) }
        }
        Spacer(Modifier.height(8.dp))
        // Copier une mesure vers une autre (le geste FL Studio)
        var srcBar by remember { mutableIntStateOf(0) }
        var dstBar by remember { mutableIntStateOf(1) }
        val barNanos = quarter * 4
        val nBars = ((len + barNanos - 1) / barNanos).toInt().coerceAtLeast(2)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text("MESURE", color = InkDim, fontSize = 10.sp, letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace)
            Stepper("−") { srcBar = (srcBar - 1).coerceAtLeast(0) }
            Text("${srcBar + 1}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Stepper("+") { srcBar = (srcBar + 1).coerceAtMost(nBars - 1) }
            Text("→", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Stepper("−") { dstBar = (dstBar - 1).coerceAtLeast(0) }
            Text("${dstBar + 1}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace)
            Stepper("+") { dstBar = (dstBar + 1).coerceAtMost(nBars) }
            ModeChip("COPIER", selected = false) {
                engine.copyBar(ch, srcBar, dstBar, barNanos)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Finesse de la grille
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text("GRILLE", color = InkDim, fontSize = 10.sp, letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace)
            ModeChip("PAS 1/16", selected = gridDiv == 16) { gridDiv = 16 }
            ModeChip("½ PAS 1/32", selected = gridDiv == 32) { gridDiv = 32 }
            ModeChip("¼ PAS 1/64", selected = gridDiv == 64) { gridDiv = 64 }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (dragBar != null) "Vélocité : $dragVel"
            else "Case vide = poser une note · note = l'effacer · glisser haut/bas sur une note = vélocité.",
            color = if (dragBar != null) Amber else InkDim,
            fontSize = 11.sp, fontWeight = if (dragBar != null) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(6.dp))
        // Clavier piano fixe à gauche + grille défilable (façon FL Studio)
        Row {
            val cellW = when (gridDiv) {
                64 -> 11.dp
                32 -> 17.dp
                else -> 26.dp
            }
            val cellH = 22.dp
            // Le clavier
            Canvas(
                modifier = Modifier
                    .width(44.dp)
                    .height(cellH * lanes)
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
            ) {
                val chh = size.height / lanes
                val labelPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.parseColor("#3A3F4F")
                    this.textSize = chh * 0.55f
                    this.isAntiAlias = true
                    this.typeface = android.graphics.Typeface.MONOSPACE
                    this.isFakeBoldText = true
                }
                for (l in 0 until lanes) {
                    val note = baseNote + (lanes - 1 - l)
                    val black = when (note % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
                    // Touche
                    drawRect(
                        color = if (black) Color(0xFF171A22) else Color(0xFFEDEFF4),
                        topLeft = Offset(0f, l * chh),
                        size = androidx.compose.ui.geometry.Size(size.width, chh)
                    )
                    // Relief des touches noires (plus courtes, comme un vrai clavier)
                    if (black) {
                        drawRect(
                            color = Color(0xFFEDEFF4),
                            topLeft = Offset(size.width * 0.62f, l * chh),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.38f, chh)
                        )
                    }
                    // Séparation
                    drawLine(
                        Color(0xFFB9BECC), Offset(0f, (l + 1) * chh),
                        Offset(size.width, (l + 1) * chh), 1f
                    )
                    // Repère d'octave sur chaque C
                    if (note % 12 == 0) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "C${note / 12 - 1}", 3f, (l + 1) * chh - chh * 0.25f, labelPaint
                        )
                    }
                }
            }
            // La grille + la bande de vélocité, qui défilent ensemble
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
            Canvas(
                modifier = Modifier
                    .width(cellW * steps)
                    .height(cellH * lanes)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Panel)
                    .pointerInput(ch, baseNote, revision, steps) {
                        detectDragGestures(
                            onDragStart = { off ->
                                val cw = size.width.toFloat() / steps
                                val chh = size.height.toFloat() / lanes
                                val step = (off.x / cw).toInt().coerceIn(0, steps - 1)
                                val lane = (off.y / chh).toInt().coerceIn(0, lanes - 1)
                                val note = baseNote + (lanes - 1 - lane)
                                val t = step.toLong() * g16
                                dragBar = bars.find {
                                    it.note == note && t < it.start + it.dur && t + g16 > it.start
                                }
                                dragVel = dragBar?.vel ?: 0
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragBar?.let { b ->
                                    dragVel = (dragVel - (amount.y / 2f).toInt()).coerceIn(1, 127)
                                    engine.setHitVelocity(ch, b.note, b.start, dragVel)
                                }
                            },
                            onDragEnd = { dragBar = null },
                            onDragCancel = { dragBar = null }
                        )
                    }
                    .pointerInput(ch, baseNote, revision, steps) {
                        detectTapGestures { off ->
                            val cw = size.width.toFloat() / steps
                            val chh = size.height.toFloat() / lanes
                            val step = (off.x / cw).toInt().coerceIn(0, steps - 1)
                            val lane = (off.y / chh).toInt().coerceIn(0, lanes - 1)
                            val note = baseNote + (lanes - 1 - lane)
                            val t = step.toLong() * g16
                            // Une note existe-t-elle sur cette case ?
                            val hit = bars.find {
                                it.note == note && t < it.start + it.dur && t + g16 > it.start
                            }
                            if (hit != null) {
                                engine.deleteHit(ch, hit.note, hit.start)
                            } else {
                                engine.addNote(ch, note, t, (g16 * 9) / 10, 100)
                            }
                        }
                    }
            ) {
                val cw = size.width / steps
                val chh = size.height / lanes
                val laneWhite = androidx.compose.ui.graphics.lerp(Panel, Ink, 0.10f)
                val laneBlack = androidx.compose.ui.graphics.lerp(Bg, Color.Black, 0.35f)
                val lineSoft = androidx.compose.ui.graphics.lerp(PanelLine, Ink, 0.12f)
                val lineOctave = androidx.compose.ui.graphics.lerp(PanelLine, Ink, 0.45f)
                // Fond des lignes de notes : blanches claires, noires bien sombres
                for (l in 0 until lanes) {
                    val note = baseNote + (lanes - 1 - l)
                    val black = when (note % 12) { 1, 3, 6, 8, 10 -> true; else -> false }
                    drawRect(
                        color = if (black) laneBlack else laneWhite,
                        topLeft = Offset(0f, l * chh),
                        size = androidx.compose.ui.geometry.Size(size.width, chh)
                    )
                }
                // Alternance douce un temps sur deux, pour se repérer horizontalement
                val stepsPerBeat = (gridDiv / 4).coerceAtLeast(1)
                var beat = 0
                var st0 = 0
                while (st0 < steps) {
                    if (beat % 2 == 1) {
                        drawRect(
                            color = Ink.copy(alpha = 0.035f),
                            topLeft = Offset(st0 * cw, 0f),
                            size = androidx.compose.ui.geometry.Size(
                                (stepsPerBeat * cw).coerceAtMost(size.width - st0 * cw),
                                size.height
                            )
                        )
                    }
                    st0 += stepsPerBeat
                    beat++
                }
                // Séparations horizontales, plus marquées à chaque octave (sous les Do)
                for (l in 0..lanes) {
                    val noteAbove = baseNote + (lanes - l)
                    val isOctave = noteAbove % 12 == 0
                    drawLine(
                        color = if (isOctave) lineOctave else lineSoft,
                        start = Offset(0f, l * chh), end = Offset(size.width, l * chh),
                        strokeWidth = if (isOctave) 2f else 1f
                    )
                }
                // Grille verticale : pas fins, temps moyens, mesures épaisses
                for (stv in 0..steps) {
                    val x = stv * cw
                    val w = when {
                        stv % gridDiv == 0 -> 2.5f
                        stv % stepsPerBeat == 0 -> 1.5f
                        else -> 0.5f
                    }
                    val c = when {
                        stv % gridDiv == 0 -> lineOctave
                        stv % stepsPerBeat == 0 -> lineSoft
                        else -> PanelLine
                    }
                    drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
                }
                // Les notes en barres
                for (b in bars) {
                    if (b.note < baseNote || b.note >= baseNote + lanes) continue
                    val l = lanes - 1 - (b.note - baseNote)
                    val x = (b.start.toFloat() / g16) * cw
                    val w = ((b.dur.toFloat() / g16) * cw).coerceAtLeast(6f)
                    drawRoundRect(
                        color = color.copy(alpha = 0.45f + (b.vel / 127f) * 0.55f),
                        topLeft = Offset(x + 1f, l * chh + 2f),
                        size = androidx.compose.ui.geometry.Size((w - 2f).coerceAtLeast(3f), chh - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )
                    // Liseré sombre : deux notes voisines restent bien distinctes
                    drawRoundRect(
                        color = Bg,
                        topLeft = Offset(x + 1f, l * chh + 2f),
                        size = androidx.compose.ui.geometry.Size((w - 2f).coerceAtLeast(3f), chh - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                    )
                }
                // Tête de lecture
                if (playing) {
                    val px = (elapsed.toFloat() / g16) * cw
                    if (px in 0f..size.width) {
                        drawLine(Amber, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            // Bande de vélocité (comme le bas de FL Studio) : tire les traits du doigt
            Canvas(
                modifier = Modifier
                    .width(cellW * steps)
                    .height(58.dp)
                    .clip(RoundedCornerShape(bottomEnd = 10.dp))
                    .background(Panel)
                    .pointerInput(ch, revision, steps) {
                        detectDragGestures(
                            onDragStart = { off ->
                                val cw2 = size.width.toFloat() / steps
                                val t = (off.x / cw2).toLong() * g16
                                val tol = maxOf(g16 * 2, quarter / 8)
                                velTarget = bars.minByOrNull {
                                    kotlin.math.abs(it.start - t)
                                }?.takeIf {
                                    kotlin.math.abs(it.start - t) < tol
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                velTarget?.let { b ->
                                    val v = ((1f - change.position.y / size.height) * 127)
                                        .toInt().coerceIn(1, 127)
                                    engine.setHitVelocity(ch, b.note, b.start, v)
                                }
                            },
                            onDragEnd = { velTarget = null },
                            onDragCancel = { velTarget = null }
                        )
                    }
            ) {
                val cw2 = size.width / steps
                drawLine(PanelLine, Offset(0f, 1f), Offset(size.width, 1f), 1.5f)
                for (b in bars) {
                    val x = (b.start.toFloat() / g16) * cw2 + cw2 * 0.3f
                    val h = (b.vel / 127f) * size.height
                    val c = ChannelColors[b.note % 16]
                    drawLine(
                        color = c,
                        start = Offset(x, size.height),
                        end = Offset(x, size.height - h),
                        strokeWidth = 3f
                    )
                    drawCircle(color = c, radius = 4f, center = Offset(x, size.height - h))
                }
            }
            }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${bars.size} note(s) sur le canal ${ch + 1} · clavier à gauche, vélocités en bas (tire les traits du doigt).",
            color = InkDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))

        // ----- Générateur de rythmes euclidiens -----
        var eSteps by remember { mutableIntStateOf(16) }
        var ePulses by remember { mutableIntStateOf(5) }
        var eRotate by remember { mutableIntStateOf(0) }
        var eBars by remember { mutableIntStateOf(2) }
        var eNote by remember { mutableIntStateOf(36) }
        val pat = remember(eSteps, ePulses, eRotate) {
            engine.euclidPattern(eSteps, ePulses, eRotate)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text("GÉNÉRATEUR EUCLIDIEN", color = Ink, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            // Aperçu du motif
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
            ) {
                val nn = pat.size
                if (nn == 0) return@Canvas
                val w = size.width / nn
                val r = (w * 0.32f).coerceAtMost(size.height * 0.42f)
                for (i in 0 until nn) {
                    drawCircle(
                        color = if (pat[i]) color else PanelLine,
                        radius = r,
                        center = Offset(i * w + w / 2, size.height / 2)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Text("PAS", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Stepper("−") { eSteps = (eSteps - 1).coerceAtLeast(2) }
                Text("$eSteps", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Stepper("+") { eSteps = (eSteps + 1).coerceAtMost(32) }
                Spacer(Modifier.width(6.dp))
                Text("COUPS", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Stepper("−") { ePulses = (ePulses - 1).coerceAtLeast(1) }
                Text("$ePulses", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Stepper("+") { ePulses = (ePulses + 1).coerceAtMost(eSteps) }
                Spacer(Modifier.width(6.dp))
                Text("ROT", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Stepper("−") { eRotate -= 1 }
                Text("$eRotate", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Stepper("+") { eRotate += 1 }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Text("SON", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Stepper("−") { eNote = (eNote - 1).coerceAtLeast(0) }
                Text("${drumHint(eNote)} ${noteName(eNote)}", color = Ink, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Stepper("+") { eNote = (eNote + 1).coerceAtMost(127) }
                Spacer(Modifier.width(6.dp))
                Text("MES.", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Stepper("−") { eBars = (eBars - 1).coerceAtLeast(1) }
                Text("$eBars", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Stepper("+") { eBars = (eBars + 1).coerceAtMost(16) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton(
                    label = "GÉNÉRER", active = false, activeColor = Amber, enabled = true,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    engine.generateEuclid(ch, eNote, eSteps, ePulses, eRotate, eBars)
                }
                PadButton(
                    label = "HUMANISER", active = false, activeColor = PlayGreen, enabled = true,
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    engine.humanize(ch, 12, 16)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Répartit les coups le plus régulièrement possible : 5 coups sur 16 pas donne " +
                "un groove latin, 3 sur 8 un rythme africain. ROT décale le motif. " +
                "HUMANISER ajoute de micro-décalages pour enlever le côté robot.",
                color = InkDim, fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ================= PAGE MIXETTE =================

@Composable
private fun MixerPage(engine: MidiEngine) {
    val volumes by engine.volumes.collectAsState()
    val pans by engine.pans.collectAsState()
    val transposes by engine.transposes.collectAsState()
    val swing by engine.swing.collectAsState()
    val tracks by engine.tracks.collectAsState()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item {
            Text(
                "MIXETTE MIDI", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp)
            )
        }
        // Groove / swing global
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Row {
                    Text("GROOVE (swing)", color = Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    Text("$swing %", color = Amber, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = swing.toFloat(),
                    onValueChange = { engine.setSwing(it.toInt()) },
                    valueRange = 50f..75f,
                    colors = SliderDefaults.colors(
                        thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = PanelLine
                    )
                )
                Text(
                    "50 % = droit · 66 % = shuffle classique. Appliqué à la lecture et à l'export.",
                    color = InkDim, fontSize = 11.sp
                )
            }
        }
        items((0..15).toList(), key = { it }) { ch ->
            val hasEvents = (tracks.getOrNull(ch)?.eventCount ?: 0) > 0
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(ChannelColors[ch].copy(alpha = if (hasEvents) 1f else 0.25f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format(Locale.US, "CH %02d", ch + 1),
                        color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                    )
                    // Transposition
                    Stepper("−") { engine.setTranspose(ch, transposes[ch] - 1) }
                    Text(
                        if (transposes[ch] >= 0) "+${transposes[ch]}" else "${transposes[ch]}",
                        color = if (transposes[ch] == 0) InkDim else Amber,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Stepper("+") { engine.setTranspose(ch, transposes[ch] + 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VOL", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(32.dp))
                    Slider(
                        value = volumes[ch].toFloat(),
                        onValueChange = { engine.setVolume(ch, it.toInt()) },
                        valueRange = 0f..127f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = ChannelColors[ch], activeTrackColor = ChannelColors[ch],
                            inactiveTrackColor = PanelLine
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("PAN", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(32.dp))
                    Slider(
                        value = pans[ch].toFloat(),
                        onValueChange = { engine.setPan(ch, it.toInt()) },
                        valueRange = 0f..127f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = InkDim, activeTrackColor = PanelLine, inactiveTrackColor = PanelLine
                        )
                    )
                }
            }
        }
        item {
            Text(
                "VOL et PAN partent en direct vers la machine (CC7/CC10). Certaines machines les ignorent — vois leur menu MIDI. La transposition s'applique à la lecture.",
                color = InkDim, fontSize = 11.sp
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ================= PAGE AUDIO =================

@Composable
private fun AudioPage(audio: com.beatbox.midirecorder.midi.AudioEngine) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val recording by audio.isRecording.collectAsState()
    val playing by audio.isPlaying.collectAsState()
    val level by audio.level.collectAsState()
    val status by audio.statusMessage.collectAsState()
    val bandLabels by audio.bandLabels.collectAsState()
    val bandLevels by audio.bandLevels.collectAsState()
    var files by remember { mutableStateOf(listOf<File>()) }
    val dir = remember { ctx.getExternalFilesDir(null) ?: ctx.filesDir }
    LaunchedEffect(recording) { files = audio.listRecordings(dir) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var trimFile by remember { mutableStateOf<File?>(null) }
    var fxFile by remember { mutableStateOf<File?>(null) }
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    val bassAmt by audio.bassAmt.collectAsState()
    val loudAmt by audio.loudAmt.collectAsState()
    val wideAmt by audio.wideAmt.collectAsState()
    val rendering by audio.rendering.collectAsState()
    LaunchedEffect(rendering) { if (!rendering) files = audio.listRecordings(dir) }
    val audioInputs by audio.inputs.collectAsState()
    val selectedInputId by audio.selectedInputId.collectAsState()
    val activeInput by audio.activeInputName.collectAsState()
    LaunchedEffect(Unit) { audio.refreshInputs() }

    renaming?.let { f ->
        RenameDialog(
            initial = f.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                val safe = newName.trim().ifEmpty { f.nameWithoutExtension }
                    .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                f.renameTo(File(f.parentFile, "$safe.${f.extension}"))
                files = audio.listRecordings(dir)
                renaming = null
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) audio.startRecording(dir)
        else audio.statusMessage.value = "Permission micro refusée"
    }

    trimFile?.let { f ->
        TrimScreen(audio, f, onClose = {
            trimFile = null
            files = audio.listRecordings(dir)
        })
        return
    }
    fxFile?.let { f ->
        FxScreen(audio, f, onClose = {
            fxFile = null
            files = audio.listRecordings(dir)
        })
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item {
            Text(
                "AUDIO · SON DE LA MACHINE", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "Branche la sortie audio de la machine sur l'interface USB. " +
                    "L'app choisit l'USB en priorité — vérifie la source ci-dessous.",
                    color = InkDim, fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("SOURCE", color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ModeChip("AUTO (USB d'abord)", selected = selectedInputId == null) {
                        audio.selectedInputId.value = null
                    }
                    audioInputs.forEach { d ->
                        ModeChip(audio.inputLabel(d), selected = selectedInputId == d.id) {
                            audio.selectedInputId.value = d.id
                        }
                    }
                }
                activeInput?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("Dernière prise via : $it", color = PlayGreen, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(10.dp))
                // Vumètre
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(PanelLine)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(level.coerceIn(0f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (level > 0.9f) RecRed else PlayGreen)
                    )
                }
                Spacer(Modifier.height(10.dp))
                PadButton(
                    label = if (recording) "STOP AUDIO" else "REC AUDIO",
                    active = recording,
                    activeColor = RecRed,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (recording) audio.stopRecording()
                        else {
                            val ok = androidx.core.content.ContextCompat.checkSelfPermission(
                                ctx, android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (ok) audio.startRecording(dir)
                            else permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        // Analyseur de spectre animé pendant la lecture
        if (playing) {
            item { SpectrumView(audio) }
        }
        // Égaliseur (visible pendant la lecture)
        if (bandLabels.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text("ÉGALISEUR", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    bandLabels.forEachIndexed { i, label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$label Hz", color = InkDim, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
                            Slider(
                                value = (bandLevels.getOrNull(i) ?: 0).toFloat(),
                                onValueChange = { audio.setBand(i, it.toInt()) },
                                valueRange = audio.bandRange.first.toFloat()..audio.bandRange.second.toFloat(),
                                modifier = Modifier.weight(1f).height(32.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Amber, activeTrackColor = Amber,
                                    inactiveTrackColor = PanelLine
                                )
                            )
                            Text(
                                "${(bandLevels.getOrNull(i) ?: 0) / 100} dB",
                                color = Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(44.dp)
                            )
                        }
                    }
                }
            }
        }
        // Panneau mastering
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("MASTERING", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                // Presets : règlent les 3 curseurs d'un coup
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ModeChip("NEUTRE", selected = false) {
                        audio.setBass(0); audio.setLoud(0); audio.setWide(0)
                    }
                    ModeChip("CHAUD", selected = false) {
                        audio.setBass(400); audio.setLoud(300); audio.setWide(200)
                    }
                    ModeChip("CLUB", selected = false) {
                        audio.setBass(700); audio.setLoud(600); audio.setWide(400)
                    }
                    ModeChip("PUNCHY", selected = false) {
                        audio.setBass(500); audio.setLoud(900); audio.setWide(100)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BASS", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = bassAmt.toFloat(),
                        onValueChange = { audio.setBass(it.toInt()) },
                        valueRange = 0f..1000f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(thumbColor = Amber,
                            activeTrackColor = Amber, inactiveTrackColor = PanelLine)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LOUD", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = loudAmt.toFloat(),
                        onValueChange = { audio.setLoud(it.toInt()) },
                        valueRange = 0f..1200f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(thumbColor = Amber,
                            activeTrackColor = Amber, inactiveTrackColor = PanelLine)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WIDE", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(40.dp))
                    Slider(
                        value = wideAmt.toFloat(),
                        onValueChange = { audio.setWide(it.toInt()) },
                        valueRange = 0f..1000f,
                        modifier = Modifier.weight(1f).height(32.dp),
                        colors = SliderDefaults.colors(thumbColor = Amber,
                            activeTrackColor = Amber, inactiveTrackColor = PanelLine)
                    )
                }
                Text(
                    if (rendering) "Mastering en cours…"
                    else "Ces réglages s'entendent à la lecture. Le bouton « Master » d'une " +
                    "prise fabrique un NOUVEAU fichier traité (basses, niveau, limiteur, " +
                    "normalisation) prêt à partager — les curseurs BASS et LOUD servent de recette.",
                    color = if (rendering) Amber else InkDim, fontSize = 10.sp
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PRISES AUDIO", color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                ModeChip("DATE", selected = !sortByName) { sortByName = false }
                Spacer(Modifier.width(6.dp))
                ModeChip("NOM", selected = sortByName) { sortByName = true }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher une prise…", fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                    focusedBorderColor = Amber, unfocusedBorderColor = PanelLine,
                    focusedLabelColor = Amber, unfocusedLabelColor = InkDim,
                    cursorColor = Amber
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        val shown = files
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { if (sortByName) it.sortedBy { f -> f.name.lowercase() } else it }
        if (shown.isEmpty()) {
            item { Text("Aucune prise audio.", color = InkDim, fontSize = 12.sp) }
        }
        items(shown, key = { it.absolutePath }) { f ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    f.nameWithoutExtension, color = Ink, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 1
                )
                WaveformStrip(
                    audio, f,
                    if (f.nameWithoutExtension.endsWith("_master")) Amber else PlayGreen
                )
                Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                TextButton(onClick = {
                    if (playing) audio.stopPlayback() else audio.play(f)
                }) { Text(if (playing) "Stop" else "Lire", color = PlayGreen) }
                TextButton(
                    onClick = { audio.stopPlayback(); trimFile = f },
                    enabled = !rendering
                ) { Text("Couper", color = Ink) }
                TextButton(
                    onClick = { audio.stopPlayback(); fxFile = f },
                    enabled = !rendering
                ) { Text("FX", color = Ink) }
                TextButton(
                    onClick = { audio.stopPlayback(); audio.slice(f) },
                    enabled = !rendering
                ) { Text("Slicer", color = Ink) }
                TextButton(
                    onClick = {
                        audio.stopPlayback()
                        audio.masterize(
                            f,
                            bassDb = bassAmt / 1000.0 * 8.0,
                            driveDb = 2.0 + loudAmt / 1200.0 * 8.0
                        )
                    },
                    enabled = !rendering && !f.nameWithoutExtension.endsWith("_master")
                ) { Text("Master", color = if (rendering) InkDim else PlayGreen) }
                TextButton(onClick = { shareFile(ctx, f, "audio/wav") }) {
                    Text("Part.", color = Amber)
                }
                TextButton(onClick = { renaming = f }) { Text("Ren.", color = InkDim) }
                TextButton(onClick = {
                    audio.stopPlayback(); f.delete(); files = audio.listRecordings(dir)
                }) { Text("Suppr.", color = RecRed) }
            }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ================= PAGE LIVE (PADS) =================

@Composable
private fun LivePad(engine: MidiEngine, ch: Int, note: Int, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val color = ChannelColors[note % 16]
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) color else Panel)
            .border(2.dp, if (pressed) color else PanelLine, RoundedCornerShape(14.dp))
            .pointerInput(ch, note) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        engine.sendNoteOn(ch, note, 110)
                        tryAwaitRelease()
                        engine.sendNoteOff(ch, note)
                        pressed = false
                    }
                )
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                drumHint(note),
                color = if (pressed) Bg else Ink,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1
            )
            Text(
                noteName(note),
                color = if (pressed) Bg else InkDim,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun LivePadsPage(engine: MidiEngine) {
    var ch by remember { mutableIntStateOf(2) } // canal 3, le réglage d'usine de l'ER-1
    var base by remember { mutableIntStateOf(36) }
    val connected by engine.connectedName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Text(
            "LIVE · JOUE LA MACHINE", color = InkDim, fontSize = 11.sp,
            letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (connected != null) "Chaque pad envoie une note à la machine. Maintiens pour tenir la note."
            else "Connecte d'abord la machine (page STUDIO).",
            color = if (connected != null) InkDim else RecRed,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        // Canal + octaves
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for (c in 0..15) {
                ModeChip("${c + 1}", selected = ch == c) { ch = c }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${noteName(base)} → ${noteName(base + 15)} · canal ${ch + 1}",
                color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            ModeChip("▼ OCT", selected = false) { base = (base - 12).coerceAtLeast(0) }
            Spacer(Modifier.width(6.dp))
            ModeChip("▲ OCT", selected = false) { base = (base + 12).coerceAtMost(112) }
        }
        Spacer(Modifier.height(10.dp))
        // Grille 4x4 : notes croissantes du bas-gauche au haut-droit (comme une MPC)
        for (row in 3 downTo 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0..3) {
                    LivePad(engine, ch, base + row * 4 + col, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Astuce : pour l'ER-1 d'usine, reste sur le canal 3 et cherche l'octave où répondent " +
            "les sons (souvent autour de C2–C3).",
            color = InkDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ================= OUTILS PARTAGÉS =================

/** Ouvre la feuille de partage Android pour n'importe quel fichier de l'app. */
private fun shareFile(ctx: android.content.Context, file: File, mime: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "com.beatbox.midirecorder.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Partager ${file.name}"))
    }.onFailure {
        android.widget.Toast.makeText(ctx, "Partage impossible : ${it.message}",
            android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Petite boîte de dialogue pour renommer un fichier. */
@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Renommer", color = Ink) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink, unfocusedTextColor = Ink,
                    focusedBorderColor = Amber, unfocusedBorderColor = PanelLine,
                    cursorColor = Amber
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("OK", color = Amber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = InkDim) }
        }
    )
}

// ================= PAGE SET (MODE CONCERT) =================

@Composable
private fun SetlistPage(engine: MidiEngine) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var names by remember {
        mutableStateOf(com.beatbox.midirecorder.midi.SessionStore.loadSetlist(ctx.filesDir))
    }
    var index by remember { mutableIntStateOf(0) }
    var loadedName by remember { mutableStateOf<String?>(null) }

    // Song mode : structure du morceau (parties + répétitions)
    var song by remember {
        mutableStateOf(com.beatbox.midirecorder.midi.SessionStore.loadSong(ctx.filesDir))
    }
    var songPlaying by remember { mutableStateOf(false) }
    var songPart by remember { mutableIntStateOf(-1) }
    val songScope = rememberCoroutineScope()

    fun saveSong(list: List<com.beatbox.midirecorder.midi.SessionStore.SongPart>) {
        song = list
        com.beatbox.midirecorder.midi.SessionStore.saveSong(ctx.filesDir, list)
    }

    fun startSong() {
        if (song.isEmpty()) return
        songPlaying = true
        songScope.launch {
            for ((pi, part) in song.withIndex()) {
                if (!songPlaying) break
                val f = File(
                    com.beatbox.midirecorder.midi.SessionStore.dir(ctx.filesDir), part.name
                )
                val loaded = runCatching {
                    com.beatbox.midirecorder.midi.SessionStore.load(f)
                }.getOrNull() ?: continue
                var rep = 0
                while (rep < part.repeats && songPlaying) {
                    songPart = pi
                    engine.restore(loaded.events, loaded.length, loaded.bpm)
                    engine.startPlayback()
                    val durMs = (loaded.length / 1_000_000L).coerceAtLeast(500L)
                    var waited = 0L
                    while (songPlaying && waited < durMs) {
                        delay(100)
                        waited += 100
                    }
                    rep++
                }
            }
            engine.stopPlayback()
            songPlaying = false
            songPart = -1
        }
    }

    fun loadAt(i: Int) {
        val name = names.getOrNull(i) ?: return
        val f = File(com.beatbox.midirecorder.midi.SessionStore.dir(ctx.filesDir), name)
        runCatching {
            val loaded = com.beatbox.midirecorder.midi.SessionStore.load(f)
            engine.restore(loaded.events, loaded.length, loaded.bpm)
            loadedName = f.nameWithoutExtension
        }.onFailure {
            android.widget.Toast.makeText(ctx, "Morceau illisible", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item {
            Text(
                "SET · MODE CONCERT", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (names.isEmpty()) {
            item {
                Text(
                    "Setlist vide. Va dans STUDIO → Sessions et appuie sur « +Set » " +
                    "sur les prises à enchaîner en concert.",
                    color = InkDim, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            // Le morceau en cours, en très gros
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        "MORCEAU ${index + 1} / ${names.size}",
                        color = InkDim, fontSize = 11.sp, letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        names.getOrNull(index)?.removeSuffix(".json") ?: "—",
                        color = if (loadedName != null &&
                            loadedName == names.getOrNull(index)?.removeSuffix(".json"))
                            PlayGreen else Ink,
                        fontSize = 24.sp, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    // Gros boutons de concert
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadButton(
                            label = "◀ PRÉC.",
                            active = false, activeColor = Amber, enabled = index > 0,
                            modifier = Modifier.weight(1f),
                            onClick = { index = (index - 1).coerceAtLeast(0); loadAt(index) }
                        )
                        PadButton(
                            label = "CHARGER",
                            active = loadedName == names.getOrNull(index)?.removeSuffix(".json"),
                            activeColor = PlayGreen, enabled = true,
                            modifier = Modifier.weight(1f),
                            onClick = { loadAt(index) }
                        )
                        PadButton(
                            label = "SUIV. ▶",
                            active = false, activeColor = Amber, enabled = index < names.size - 1,
                            modifier = Modifier.weight(1f),
                            onClick = { index = (index + 1).coerceAtMost(names.size - 1); loadAt(index) }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "PRÉC./SUIV. chargent directement. Lance ensuite PLAY sur la page STUDIO.",
                        color = InkDim, fontSize = 10.sp, textAlign = TextAlign.Center
                    )
                }
            }
            item {
                Text("ORDRE DE PASSAGE", color = InkDim, fontSize = 11.sp,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
            }
            items(names.size) { i ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (i == index) PanelLine else Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                        .clickable { index = i; loadAt(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "${i + 1}.", color = Amber, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        names[i].removeSuffix(".json"),
                        color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        saveSong(
                            song + com.beatbox.midirecorder.midi.SessionStore.SongPart(names[i], 1)
                        )
                    }) { Text("+Song", color = Amber) }
                    TextButton(onClick = {
                        com.beatbox.midirecorder.midi.SessionStore.removeFromSetlist(ctx.filesDir, names[i])
                        names = com.beatbox.midirecorder.midi.SessionStore.loadSetlist(ctx.filesDir)
                        if (index >= names.size) index = (names.size - 1).coerceAtLeast(0)
                    }) { Text("✕", color = RecRed) }
                }
            }
        }
        // ----- SONG : structure d'un morceau -----
        item {
            Text("SONG · STRUCTURE DU MORCEAU", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                if (song.isEmpty()) {
                    Text(
                        "Enchaîne tes parties pour construire un morceau (A-A-B-A…). " +
                        "Appuie sur « +Song » dans la liste ci-dessus pour ajouter une partie.",
                        color = InkDim, fontSize = 11.sp
                    )
                } else {
                    song.forEachIndexed { i, part ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                "${i + 1}. ${part.name.removeSuffix(".json")}",
                                color = if (songPart == i) PlayGreen else Ink,
                                fontSize = 13.sp,
                                fontWeight = if (songPart == i) FontWeight.Black else FontWeight.Bold,
                                maxLines = 1, modifier = Modifier.weight(1f)
                            )
                            Stepper("−") {
                                saveSong(song.toMutableList().also { l ->
                                    l[i] = l[i].copy(repeats = (l[i].repeats - 1).coerceAtLeast(1))
                                })
                            }
                            Text(
                                "×${part.repeats}", color = Amber, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Stepper("+") {
                                saveSong(song.toMutableList().also { l ->
                                    l[i] = l[i].copy(repeats = (l[i].repeats + 1).coerceAtMost(64))
                                })
                            }
                            TextButton(onClick = {
                                saveSong(song.filterIndexed { j, _ -> j != i })
                            }) { Text("✕", color = RecRed) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PadButton(
                        label = if (songPlaying) "■ STOP MORCEAU" else "▶ JOUER LE MORCEAU",
                        active = songPlaying, activeColor = PlayGreen, enabled = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (songPlaying) {
                            songPlaying = false
                            engine.stopPlayback()
                        } else startSong()
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Chaque partie est jouée le nombre de fois indiqué, puis la suivante " +
                        "s'enchaîne automatiquement sur la machine.",
                        color = InkDim, fontSize = 10.sp
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ================= VISUALISATIONS AUDIO =================

/** Barres de spectre animées, des graves (gauche) aux aigus (droite). */
@Composable
private fun SpectrumView(audio: com.beatbox.midirecorder.midi.AudioEngine) {
    val spec by audio.spectrum.collectAsState()
    val pink = Color(0xFFFF4F9A)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
            .padding(6.dp)
    ) {
        val n = spec.size
        if (n == 0) return@Canvas
        val bw = size.width / n
        spec.forEachIndexed { i, v ->
            val h = (v * size.height).coerceAtLeast(3f)
            val c = androidx.compose.ui.graphics.lerp(Amber, pink, i / (n - 1f))
            drawRoundRect(
                color = c,
                topLeft = Offset(i * bw + 2f, size.height - h),
                size = androidx.compose.ui.geometry.Size((bw - 4f).coerceAtLeast(2f), h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
        }
    }
}

/** Forme d'onde d'une prise audio, calculée en tâche de fond puis mémorisée. */
@Composable
private fun WaveformStrip(
    audio: com.beatbox.midirecorder.midi.AudioEngine,
    file: File,
    color: Color
) {
    val wf by produceState(initialValue = FloatArray(0), file.absolutePath, file.length()) {
        value = withContext(Dispatchers.IO) { audio.waveform(file) }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        drawLine(
            PanelLine, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = 1f
        )
        if (wf.isEmpty()) return@Canvas
        val bw = size.width / wf.size
        wf.forEachIndexed { i, v ->
            val h = (v * size.height * 0.95f).coerceAtLeast(1.5f)
            val x = i * bw + bw / 2
            drawLine(
                color = color.copy(alpha = 0.85f),
                start = Offset(x, size.height / 2 - h / 2),
                end = Offset(x, size.height / 2 + h / 2),
                strokeWidth = (bw * 0.6f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )
        }
    }
}

// ================= PAGE KORG (MACHINE VIRTUELLE) =================

@Composable
private fun KorgSlider(
    label: String, value: Int, range: IntRange,
    color: Color, onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(52.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = color, activeTrackColor = color, inactiveTrackColor = PanelLine
            )
        )
        Text("$value", color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
    }
}

@Composable
private fun VirtualKorgPage(engine: MidiEngine) {
    val connected by engine.connectedName.collectAsState()
    val devs by engine.connectedDevices.collectAsState()
    val target by engine.targetDeviceId.collectAsState()
    val activity by engine.deviceActivity.collectAsState()
    val thruOn by engine.thruEnabled.collectAsState()
    val thruTr by engine.thruTranspose.collectAsState()
    val thruCh by engine.thruChannel.collectAsState()
    val remoteOn by engine.remoteEnabled.collectAsState()
    val remoteMap by engine.remoteMap.collectAsState()
    val remoteLearning by engine.remoteLearn.collectAsState()
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = android.os.SystemClock.uptimeMillis()
            delay(80)
        }
    }
    var ch by remember { mutableIntStateOf(2) } // canal 3, réglage d'usine ER-1
    var program by remember { mutableIntStateOf(0) }
    // 6 curseurs CC assignables (défauts utiles : volume, pan, filtre, résonance, attaque, relâchement)
    val ccNums = remember { mutableStateListOf(7, 10, 74, 71, 73, 72) }
    val ccVals = remember { mutableStateListOf(100, 64, 64, 0, 0, 64) }
    val ccNames = listOf("VOLUME", "PAN", "FILTRE", "RESO", "ATTAQ.", "RELÂCH.")
    // Potard NRPN façon ER-1
    var nrpnMsb by remember { mutableIntStateOf(5) }
    var nrpnLsb by remember { mutableIntStateOf(0) }
    var nrpnVal by remember { mutableIntStateOf(64) }

    // MIDI Learn : tourne un potard sur la machine, l'app assigne toute seule
    val learnArmed by engine.learnArmed.collectAsState()
    val learnedCC by engine.learnedCC.collectAsState()
    val learnedNrpn by engine.learnedNrpn.collectAsState()
    var learnIndex by remember { mutableStateOf<Int?>(null) }
    var learnNrpnArmed by remember { mutableStateOf(false) }

    LaunchedEffect(learnedCC) {
        val cc = learnedCC
        val idx = learnIndex
        if (cc != null && idx != null) {
            ccNums[idx] = cc
            learnIndex = null
            engine.stopLearn()
        }
    }
    LaunchedEffect(learnedNrpn) {
        val pair = learnedNrpn
        if (pair != null && learnNrpnArmed) {
            nrpnMsb = pair.first
            nrpnLsb = pair.second
            learnNrpnArmed = false
            engine.stopLearn()
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item {
            Text(
                "KORG VIRTUELLE · TÉLÉCOMMANDE", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (learnArmed) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Amber)
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        "APPRENTISSAGE — tourne un potard sur la machine",
                        color = Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item {
            Text(
                if (connected != null) "Connectée : $connected"
                else "Connecte d'abord la machine (page STUDIO).",
                color = if (connected != null) PlayGreen else RecRed,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }
        // ----- Appareils USB connectés + cible d'envoi -----
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("APPAREILS USB", color = Ink, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                if (devs.isEmpty()) {
                    Text(
                        "Aucun appareil. Avec un hub USB alimenté, tu peux en brancher " +
                        "plusieurs : machine + clavier + seconde boîte à rythmes.",
                        color = InkDim, fontSize = 11.sp
                    )
                } else {
                    devs.forEach { (id, name) ->
                        val alive = nowMs - (activity[id] ?: 0L) < 250
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Box(
                                Modifier.size(9.dp).clip(CircleShape)
                                    .background(if (alive) PlayGreen else PanelLine)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = Ink, fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { engine.disconnectDevice(id) }) {
                                Text("✕", color = RecRed)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("ENVOYER VERS", color = InkDim, fontSize = 10.sp,
                        letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        ModeChip("TOUS", selected = target == null) {
                            engine.targetDeviceId.value = null
                        }
                        devs.forEach { (id, name) ->
                            ModeChip(name.take(12), selected = target == id) {
                                engine.targetDeviceId.value = id
                            }
                        }
                    }
                }
            }
        }
        // ----- Routage THRU -----
        if (devs.size > 1) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ROUTAGE THRU", color = Ink, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f))
                        ModeChip(if (thruOn) "ACTIF" else "COUPÉ", selected = thruOn) {
                            engine.toggleThru()
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ce qui arrive d'un appareil est renvoyé vers les autres : " +
                        "ton clavier joue directement les sons de la machine.",
                        color = InkDim, fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Text("TRANSPOSE", color = InkDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Stepper("−") { engine.setThruTranspose(thruTr - 1) }
                        Text(if (thruTr >= 0) "+$thruTr" else "$thruTr",
                            color = if (thruTr == 0) InkDim else Amber, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Stepper("+") { engine.setThruTranspose(thruTr + 1) }
                        Spacer(Modifier.width(8.dp))
                        Text("CANAL", color = InkDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace)
                        Stepper("−") { engine.setThruChannel(thruCh - 1) }
                        Text(if (thruCh < 0) "—" else "${thruCh + 1}",
                            color = if (thruCh < 0) InkDim else Amber, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Stepper("+") { engine.setThruChannel(thruCh + 1) }
                    }
                }
            }
        }
        // ----- Télécommande de l'app -----
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TÉLÉCOMMANDE", color = Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    ModeChip(if (remoteOn) "ACTIVE" else "COUPÉE", selected = remoteOn) {
                        engine.toggleRemote()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pilote l'app depuis un pad ou un clavier USB, les mains sur les machines.",
                    color = InkDim, fontSize = 10.sp
                )
                Spacer(Modifier.height(6.dp))
                listOf("REC", "PLAY", "STOP", "OVERDUB").forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(action, color = Ink, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(76.dp))
                        val note = remoteMap[action]
                        Text(
                            if (note != null) "${noteName(note)} (${note})" else "non assigné",
                            color = if (note != null) PlayGreen else InkDim,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        ModeChip(
                            if (remoteLearning == action) "APPUIE…" else "APPR.",
                            selected = remoteLearning == action
                        ) {
                            if (remoteLearning == action) engine.cancelRemoteLearn()
                            else engine.startRemoteLearn(action)
                        }
                        if (note != null) {
                            TextButton(onClick = { engine.clearRemote(action) }) {
                                Text("✕", color = RecRed)
                            }
                        }
                    }
                }
            }
        }
        // Transport de la machine
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("▶ START", active = false, activeColor = PlayGreen,
                    enabled = connected != null, modifier = Modifier.weight(1f)) {
                    engine.sendStart()
                }
                PadButton("■ STOP", active = false, activeColor = RecRed,
                    enabled = connected != null, modifier = Modifier.weight(1f)) {
                    engine.sendStop()
                }
            }
        }
        // Canal
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                Text("CANAL", color = InkDim, fontSize = 10.sp, letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 6.dp))
                for (c in 0..15) {
                    ModeChip("${c + 1}", selected = ch == c) { ch = c }
                }
            }
        }
        // Changement de pattern à distance
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("PATTERN", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stepper("−") { program = (program - 1).coerceAtLeast(0) }
                    Text(
                        String.format(Locale.US, "%02d", program + 1),
                        color = Amber, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Stepper("+") { program = (program + 1).coerceAtMost(127) }
                    Spacer(Modifier.weight(1f))
                    ModeChip("ENVOYER", selected = false) {
                        engine.sendProgramChange(ch, program)
                    }
                }
                Text(
                    "Envoie un Program Change : la machine saute au pattern choisi.",
                    color = InkDim, fontSize = 10.sp
                )
            }
        }
        // Curseurs CC assignables
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("CURSEURS CC (assignables)", color = Ink, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                ccNames.forEachIndexed { i, name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("−") { ccNums[i] = (ccNums[i] - 1).coerceAtLeast(0) }
                        Text(
                            "CC${ccNums[i]}", color = InkDim, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center
                        )
                        Stepper("+") { ccNums[i] = (ccNums[i] + 1).coerceAtMost(127) }
                        Spacer(Modifier.width(8.dp))
                        ModeChip(
                            if (learnIndex == i) "TOURNE UN POTARD…" else "APPR.",
                            selected = learnIndex == i
                        ) {
                            if (learnIndex == i) { learnIndex = null; engine.stopLearn() }
                            else { learnIndex = i; learnNrpnArmed = false; engine.startLearn() }
                        }
                    }
                    KorgSlider(name, ccVals[i], 0..127, ChannelColors[i * 2]) { v ->
                        ccVals[i] = v
                        engine.sendCC(ch, ccNums[i], v)
                    }
                }
                Text(
                    "Chaque curseur envoie son CC en direct. Ajuste le numéro de CC (−/+) " +
                    "selon le tableau MIDI de ta machine.",
                    color = InkDim, fontSize = 10.sp
                )
            }
        }
        // Potard NRPN (les boutons de l'ER-1)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("POTARD NRPN (ER-1)", color = Ink, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MSB", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Stepper("−") { nrpnMsb = (nrpnMsb - 1).coerceAtLeast(0) }
                    Text("$nrpnMsb", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                    Stepper("+") { nrpnMsb = (nrpnMsb + 1).coerceAtMost(127) }
                    Spacer(Modifier.width(8.dp))
                    Text("LSB", color = InkDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Stepper("−") { nrpnLsb = (nrpnLsb - 1).coerceAtLeast(0) }
                    Text("$nrpnLsb", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace)
                    Stepper("+") { nrpnLsb = (nrpnLsb + 1).coerceAtMost(127) }
                    Spacer(Modifier.width(8.dp))
                    ModeChip(
                        if (learnNrpnArmed) "TOURNE…" else "APPR.",
                        selected = learnNrpnArmed
                    ) {
                        if (learnNrpnArmed) { learnNrpnArmed = false; engine.stopLearn() }
                        else { learnNrpnArmed = true; learnIndex = null; engine.startLearn() }
                    }
                }
                KorgSlider("VALEUR", nrpnVal, 0..127, Amber) { v ->
                    nrpnVal = v
                    engine.sendNRPN(ch, nrpnMsb, nrpnLsb, v)
                }
                Text(
                    "Les potards de l'ER-1 parlent en NRPN. Astuce : tourne un bouton sur " +
                    "la machine, note le couple MSB/LSB affiché sur la LED signal (ou dans " +
                    "sa notice), règle-le ici, et pilote ce paramètre du téléphone.",
                    color = InkDim, fontSize = 10.sp
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ================= PAGE SONS (DUMPS SYSEX) =================

@Composable
private fun SysexPage(engine: MidiEngine) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val captures by engine.sysexDumps.collectAsState()
    var files by remember {
        mutableStateOf(com.beatbox.midirecorder.midi.SessionStore.listSyx(ctx.filesDir))
    }
    var renaming by remember { mutableStateOf<File?>(null) }

    // ----- Carte SD des Electribe récentes (electribe 2 / sampler) -----
    val prefs = remember { ctx.getSharedPreferences("fab", 0) }
    var treeUri by remember {
        mutableStateOf(prefs.getString("sd_tree", null)?.let { Uri.parse(it) })
    }
    var sdFiles by remember {
        mutableStateOf<List<androidx.documentfile.provider.DocumentFile>>(emptyList())
    }
    val interesting = remember { setOf("wav", "all", "e2sallpat", "e2spat", "syx") }
    fun refreshSd() {
        sdFiles = treeUri?.let { u ->
            runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, u)
                    ?.listFiles()
                    ?.filter { it.isFile && (it.name?.substringAfterLast('.', "")?.lowercase() in interesting) }
                    ?.sortedBy { it.name?.lowercase() }
            }.getOrNull()
        } ?: emptyList()
    }
    LaunchedEffect(treeUri) { refreshSd() }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs.edit().putString("sd_tree", uri.toString()).apply()
            treeUri = uri
        }
    }
    val audioDir = remember { ctx.getExternalFilesDir(null) ?: ctx.filesDir }
    var appWavs by remember {
        mutableStateOf(audioDir.listFiles { f -> f.extension == "wav" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    renaming?.let { f ->
        RenameDialog(
            initial = f.nameWithoutExtension,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                val safe = newName.trim().ifEmpty { f.nameWithoutExtension }
                    .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                f.renameTo(File(f.parentFile, "$safe.syx"))
                files = com.beatbox.midirecorder.midi.SessionStore.listSyx(ctx.filesDir)
                renaming = null
            }
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        item {
            Text(
                "SONS & SAMPLES · TOUTE LA FAMILLE ELECTRIBE", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // Tableau de compatibilité
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text("QUEL MODÈLE ?", color = Ink, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(
                    "• ER-1, EA-1, ES-1, EM-1, EMX, ESX (1999-2010) : sons et patterns par " +
                    "MIDI SysEx → section « dumps » plus bas.\n" +
                    "• electribe 2 / electribe sampler (récentes) : samples et patterns par " +
                    "CARTE SD → section « samples » juste en dessous.\n" +
                    "Toutes profitent des pages STUDIO, LIVE, PIANO, MIX, KORG et AUDIO.",
                    color = InkDim, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
        // ----- Samples via carte SD (machines récentes) -----
        item {
            Text("SAMPLES · CARTE SD OU CLÉ USB", color = InkDim,
                fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "1. Éteins la machine, sors sa carte SD, branche-la au téléphone " +
                    "(lecteur USB-C). Une simple clé USB fonctionne aussi pour " +
                    "transporter tes fichiers.\n" +
                    "2. « Choisir le dossier » → navigue jusqu'à la carte, dossier KORG " +
                    "(ou son sous-dossier Sample).\n" +
                    "3. Dépose tes prises WAV vers la carte, ou récupère les samples de la " +
                    "machine dans l'app. Remets la carte, et importe depuis le menu de la machine.",
                    color = InkDim, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Amber)
                        .clickable { treeLauncher.launch(null) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        if (treeUri == null) "Choisir le dossier (carte SD)"
                        else "Changer de dossier",
                        color = Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (treeUri != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Dossier connecté · ${sdFiles.size} fichier(s)",
                            color = PlayGreen, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                        )
                        ModeChip("RAFRAÎCHIR", selected = false) { refreshSd() }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PanelLine)
                            .clickable {
                                runCatching {
                                    val tree = androidx.documentfile.provider.DocumentFile
                                        .fromTreeUri(ctx, treeUri!!)
                                    var n = 0
                                    appWavs.forEach { f ->
                                        val dst = tree?.createFile("audio/wav", f.name)
                                        if (dst != null) {
                                            ctx.contentResolver.openOutputStream(dst.uri)?.use { out ->
                                                f.inputStream().use { inp -> inp.copyTo(out) }
                                            }
                                            n++
                                        }
                                    }
                                    refreshSd()
                                    android.widget.Toast.makeText(ctx, "$n fichier(s) copiés", android.widget.Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    android.widget.Toast.makeText(ctx, "Copie impossible : ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            .padding(vertical = 11.dp)
                    ) {
                        Text("Envoyer TOUTES les prises WAV →", color = Amber,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        // Fichiers présents sur la carte
        if (treeUri != null) {
            items(sdFiles.size) { i ->
                val doc = sdFiles[i]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${doc.name} · ${doc.length() / 1024} Ko",
                        color = Ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 1, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        // Récupérer le sample de la carte vers l'app (visible en page AUDIO)
                        runCatching {
                            val dst = File(audioDir, doc.name ?: "sample.wav")
                            ctx.contentResolver.openInputStream(doc.uri)?.use { inp ->
                                dst.outputStream().use { out -> inp.copyTo(out) }
                            }
                            appWavs = audioDir.listFiles { f -> f.extension == "wav" }
                                ?.sortedByDescending { it.lastModified() } ?: emptyList()
                            android.widget.Toast.makeText(ctx, "Récupéré dans l'app (page AUDIO)", android.widget.Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "Copie impossible", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("← App", color = PlayGreen) }
                    TextButton(onClick = {
                        runCatching { doc.delete(); refreshSd() }
                    }) { Text("Suppr.", color = RecRed) }
                }
            }
            // Envoyer une prise de l'app vers la carte
            item {
                Text("ENVOYER UNE PRISE VERS LA CARTE", color = InkDim, fontSize = 11.sp,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
            }
            if (appWavs.isEmpty()) {
                item {
                    Text("Aucune prise WAV dans l'app (enregistre en page AUDIO).",
                        color = InkDim, fontSize = 12.sp)
                }
            }
            items(appWavs, key = { "app_" + it.absolutePath }) { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        f.nameWithoutExtension, color = Ink, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        runCatching {
                            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri!!)
                            val dst = tree?.createFile("audio/wav", f.name)
                                ?: error("création impossible")
                            ctx.contentResolver.openOutputStream(dst.uri)?.use { out ->
                                f.inputStream().use { inp -> inp.copyTo(out) }
                            }
                            refreshSd()
                            android.widget.Toast.makeText(ctx, "Copié sur la carte SD !", android.widget.Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "Envoi impossible : ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("→ Carte", color = Amber) }
                }
            }
        }
        // ----- Dumps SysEx (anciennes machines) -----
        item {
            Text("DUMPS SYSEX (ER-1, ES-1, EMX…)", color = InkDim,
                fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "Sauvegarde et restaure les SONS et PATTERNS de la machine par MIDI " +
                    "(dump SysEx).\n\n" +
                    "1. Sur la machine, lance l'envoi : menu MIDI → Data Dump (voir sa notice).\n" +
                    "2. Le dump apparaît ci-dessous : sauvegarde-le sous un nom.\n" +
                    "3. Plus tard, « Envoyer » restaure ce dump dans la machine.\n\n" +
                    "Note : l'ER-1 rose fait de la synthèse (pas de samples) — ce sont ses " +
                    "réglages de sons et ses patterns qui se sauvegardent ici. Sur une " +
                    "Electribe à samples (ES-1, E2S), les samples transitent aussi en SysEx.",
                    color = InkDim, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
        item {
            Text("DUMPS REÇUS (en direct)", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        }
        if (captures.isEmpty()) {
            item {
                Text("En attente… déclenche le Data Dump sur la machine.",
                    color = InkDim, fontSize = 12.sp)
            }
        }
        items(captures.size) { i ->
            val cap = captures[captures.size - 1 - i] // plus récent en premier
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, PlayGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "Dump · ${cap.bytes.size} octets",
                    color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                    com.beatbox.midirecorder.midi.SessionStore.saveSyx(
                        ctx.filesDir, "machine_$stamp", cap.bytes
                    )
                    files = com.beatbox.midirecorder.midi.SessionStore.listSyx(ctx.filesDir)
                    android.widget.Toast.makeText(ctx, "Dump sauvegardé", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Sauver", color = Amber) }
            }
        }
        item {
            Text("SAUVEGARDES", color = InkDim, fontSize = 11.sp,
                letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
        }
        if (files.isEmpty()) {
            item { Text("Aucune sauvegarde pour l'instant.", color = InkDim, fontSize = 12.sp) }
        }
        items(files, key = { it.absolutePath }) { f ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .border(1.dp, PanelLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "${f.nameWithoutExtension} · ${f.length()} o",
                    color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    TextButton(onClick = { engine.sendSysex(f.readBytes()) }) {
                        Text("Envoyer", color = PlayGreen)
                    }
                    TextButton(onClick = { shareFile(ctx, f, "application/octet-stream") }) {
                        Text("Part.", color = Amber)
                    }
                    TextButton(onClick = { renaming = f }) { Text("Ren.", color = InkDim) }
                    TextButton(onClick = {
                        f.delete()
                        files = com.beatbox.midirecorder.midi.SessionStore.listSyx(ctx.filesDir)
                    }) { Text("Suppr.", color = RecRed) }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

// ================= ÉCRAN DE DÉCOUPE DE SAMPLE =================

@Composable
private fun TrimScreen(
    audio: com.beatbox.midirecorder.midi.AudioEngine,
    file: File,
    onClose: () -> Unit
) {
    var start by remember { mutableFloatStateOf(0f) }
    var end by remember { mutableFloatStateOf(1f) }
    var norm by remember { mutableStateOf(true) }
    val rendering by audio.rendering.collectAsState()
    val wf by produceState(initialValue = FloatArray(0), file.absolutePath) {
        value = withContext(Dispatchers.IO) { audio.waveform(file, bins = 160) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("DÉCOUPE DE SAMPLE", color = InkDim, fontSize = 11.sp,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
                Text(file.nameWithoutExtension, color = Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    maxLines = 1)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onClose)
            ) {
                Text("✕", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Forme d'onde avec la zone gardée en clair
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, PanelLine, RoundedCornerShape(14.dp))
        ) {
            drawLine(PanelLine, Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2), 1f)
            if (wf.isNotEmpty()) {
                val bw = size.width / wf.size
                wf.forEachIndexed { i, v ->
                    val frac = i / wf.size.toFloat()
                    val inside = frac >= start && frac <= end
                    val h = (v * size.height * 0.92f).coerceAtLeast(1.5f)
                    val x = i * bw + bw / 2
                    drawLine(
                        color = if (inside) PlayGreen else InkDim.copy(alpha = 0.3f),
                        start = Offset(x, size.height / 2 - h / 2),
                        end = Offset(x, size.height / 2 + h / 2),
                        strokeWidth = (bw * 0.6f).coerceAtLeast(1.5f),
                        cap = StrokeCap.Round
                    )
                }
            }
            // Bornes de découpe
            val xs = start * size.width
            val xe = end * size.width
            drawLine(Amber, Offset(xs, 0f), Offset(xs, size.height), 3f)
            drawLine(Amber, Offset(xe, 0f), Offset(xe, size.height), 3f)
        }
        Spacer(Modifier.height(10.dp))
        Text("DÉBUT", color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Slider(
            value = start,
            onValueChange = { start = it.coerceIn(0f, end - 0.01f) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Amber,
                activeTrackColor = Amber, inactiveTrackColor = PanelLine)
        )
        Text("FIN", color = InkDim, fontSize = 10.sp, letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace)
        Slider(
            value = end,
            onValueChange = { end = it.coerceIn(start + 0.01f, 1f) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = Amber,
                activeTrackColor = Amber, inactiveTrackColor = PanelLine)
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip(if (norm) "NORMALISER ✓" else "NORMALISER", selected = norm) { norm = !norm }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { audio.play(file) }) { Text("Écouter", color = PlayGreen) }
        }
        Spacer(Modifier.height(10.dp))
        PadButton(
            label = if (rendering) "DÉCOUPE…" else "COUPER → nouveau sample",
            active = false, activeColor = Amber, enabled = !rendering,
            modifier = Modifier.fillMaxWidth()
        ) {
            audio.stopPlayback()
            audio.trim(file, start, end, norm)
            onClose()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "L'original est gardé intact : la découpe crée un fichier « _cut ». " +
            "La zone verte est conservée, le reste est retiré.",
            color = InkDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ================= ÉCRAN EFFETS CRÉATIFS =================

@Composable
private fun FxScreen(
    audio: com.beatbox.midirecorder.midi.AudioEngine,
    file: File,
    onClose: () -> Unit
) {
    var speed by remember { mutableFloatStateOf(1f) }
    var reverse by remember { mutableStateOf(false) }
    var lowpass by remember { mutableFloatStateOf(20000f) }
    var delayMs by remember { mutableFloatStateOf(0f) }
    var feedback by remember { mutableFloatStateOf(0.35f) }
    val rendering by audio.rendering.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("EFFETS CRÉATIFS", color = InkDim, fontSize = 11.sp,
                    letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
                Text(file.nameWithoutExtension, color = Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    maxLines = 1)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(PanelLine)
                    .clickable(onClick = onClose)
            ) {
                Text("✕", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Presets rapides
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            ModeChip("NEUTRE", selected = false) {
                speed = 1f; reverse = false; lowpass = 20000f; delayMs = 0f
            }
            ModeChip("GRAVE", selected = false) {
                speed = 0.7f; lowpass = 20000f; delayMs = 0f
            }
            ModeChip("AIGU", selected = false) {
                speed = 1.5f; lowpass = 20000f; delayMs = 0f
            }
            ModeChip("SOURD", selected = false) {
                speed = 1f; lowpass = 1200f; delayMs = 0f
            }
            ModeChip("ÉCHO", selected = false) {
                speed = 1f; lowpass = 20000f; delayMs = 220f; feedback = 0.45f
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("VITESSE", color = InkDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
            Slider(
                value = speed,
                onValueChange = { speed = it },
                valueRange = 0.5f..2f,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = SliderDefaults.colors(thumbColor = Amber,
                    activeTrackColor = Amber, inactiveTrackColor = PanelLine)
            )
            Text(String.format(Locale.US, "%.2fx", speed), color = Amber, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.width(50.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FILTRE", color = InkDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
            Slider(
                value = lowpass,
                onValueChange = { lowpass = it },
                valueRange = 200f..20000f,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = SliderDefaults.colors(thumbColor = Amber,
                    activeTrackColor = Amber, inactiveTrackColor = PanelLine)
            )
            Text(
                if (lowpass >= 19000f) "off" else "${(lowpass / 100).toInt() * 100}",
                color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(50.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ÉCHO", color = InkDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
            Slider(
                value = delayMs,
                onValueChange = { delayMs = it },
                valueRange = 0f..600f,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = SliderDefaults.colors(thumbColor = Amber,
                    activeTrackColor = Amber, inactiveTrackColor = PanelLine)
            )
            Text(
                if (delayMs < 5f) "off" else "${delayMs.toInt()}ms",
                color = Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(50.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("REPRISE", color = InkDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.width(64.dp))
            Slider(
                value = feedback,
                onValueChange = { feedback = it },
                valueRange = 0f..0.8f,
                modifier = Modifier.weight(1f).height(32.dp),
                colors = SliderDefaults.colors(thumbColor = InkDim,
                    activeTrackColor = PanelLine, inactiveTrackColor = PanelLine)
            )
            Text(String.format(Locale.US, "%.0f%%", feedback * 100), color = InkDim,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(50.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeChip(if (reverse) "À L'ENVERS ✓" else "À L'ENVERS", selected = reverse) {
                reverse = !reverse
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { audio.play(file) }) { Text("Écouter l'original", color = PlayGreen) }
        }
        Spacer(Modifier.height(10.dp))
        PadButton(
            label = if (rendering) "TRAITEMENT…" else "APPLIQUER → nouveau son",
            active = false, activeColor = Amber, enabled = !rendering,
            modifier = Modifier.fillMaxWidth()
        ) {
            audio.stopPlayback()
            audio.applyFx(
                file,
                speed = speed.toDouble(),
                reverse = reverse,
                lowpassHz = lowpass.toDouble(),
                delayMs = delayMs.toInt(),
                feedback = feedback.toDouble()
            )
            onClose()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "La vitesse change aussi la hauteur, comme un vinyle : 0,70x pour un kick " +
            "bien grave, 1,50x pour des percus qui claquent. L'original est gardé : " +
            "le résultat s'appelle « _fx ».",
            color = InkDim, fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}
