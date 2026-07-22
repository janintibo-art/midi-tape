package com.beatbox.midirecorder.midi

import java.io.InputStream

/** Lit un fichier .mid (SMF format 0 ou 1) et le convertit en événements horodatés. */
object MidiFileReader {

    data class Result(val events: List<RecordedEvent>, val lengthNanos: Long, val bpm: Int)

    fun read(input: InputStream): Result {
        val data = input.readBytes()
        var pos = 0

        fun u8(): Int = data[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun str4(): String = String(data, pos, 4, Charsets.US_ASCII).also { pos += 4 }
        fun varLen(): Long {
            var v = 0L
            while (true) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) break
            }
            return v
        }

        require(str4() == "MThd") { "Pas un fichier MIDI" }
        val hLen = u32()
        /* format */ u16()
        val nTracks = u16()
        val division = u16()
        pos += (hLen - 6).toInt().coerceAtLeast(0)
        // Division en ticks par noire (le format SMPTE, rare, est remplacé par 480)
        val ppq = if (division and 0x8000 == 0) division.coerceAtLeast(1) else 480

        var usPerQuarter = 500_000L // 120 BPM par défaut
        data class Raw(val tick: Long, val status: Int, val d1: Int, val d2: Int)
        val raws = ArrayList<Raw>()

        repeat(nTracks) {
            if (pos + 8 > data.size) return@repeat
            if (str4() != "MTrk") return@repeat
            val len = u32()
            val end = (pos + len.toInt()).coerceAtMost(data.size)
            var tick = 0L
            var running = 0
            while (pos < end) {
                tick += varLen()
                var st = u8()
                if (st < 0x80) { pos--; st = running }
                running = st
                when {
                    st == 0xFF -> { // méta
                        val type = u8()
                        val l = varLen().toInt()
                        if (type == 0x51 && l == 3) {
                            usPerQuarter = ((u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong())
                                .coerceIn(100_000L, 2_000_000L)
                        } else {
                            pos += l
                        }
                    }
                    st == 0xF0 || st == 0xF7 -> { // sysex : sauté
                        pos += varLen().toInt()
                    }
                    st >= 0x80 -> {
                        val cmd = st and 0xF0
                        val d1 = u8()
                        val d2 = if (cmd == 0xC0 || cmd == 0xD0) 0 else u8()
                        raws.add(Raw(tick, st, d1, d2))
                    }
                }
            }
            pos = end
        }

        val nanosPerTick = (usPerQuarter * 1000L) / ppq
        val events = raws
            .map { RecordedEvent(it.tick * nanosPerTick, it.status, it.d1, it.d2) }
            .sortedBy { it.timeNanos }
        val length = (events.maxOfOrNull { it.timeNanos } ?: 0L) + 200_000_000L
        val bpm = (60_000_000L / usPerQuarter).toInt().coerceIn(20, 300)
        return Result(events, length, bpm)
    }
}
