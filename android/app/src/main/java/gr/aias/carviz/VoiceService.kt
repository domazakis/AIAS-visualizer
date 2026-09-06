package gr.aias.carviz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Η υπηρεσία φωνής: μικρόφωνο → agent ElevenLabs → ήχος.
 *
 * Τρέχει στο **κινητό** ως foreground service, τελείως έξω από το Android
 * Auto. Αυτό ήταν η αρχιτεκτονική απόφαση της πρώτης μέρας: το `CarAudioRecord`
 * της Car App Library είναι για σύντομη είσοδο που ξεκινά ο χρήστης και ο host
 * μπορεί να κόψει — άχρηστο για βοηθό που ακούει συνέχεια. Παράπλευρο όφελος:
 * αν το Android Auto δυστροπήσει, ο βοηθός συνεχίζει να δουλεύει· χάνεις μόνο
 * την οθόνη του αυτοκινήτου.
 *
 * Το πρωτόκολλο είναι το WebSocket του ElevenLabs. Για δημόσιο agent δεν
 * χρειάζεται αυθεντικοποίηση — αρκεί το `agent_id` στο URL.
 */
class VoiceService : Service() {

    companion object {
        private const val TAG = "AiasVoice"
        private const val CHANNEL = "aias-voice"
        private const val NOTIF_ID = 1

        /** Η είσοδος του agent είναι PCM 16 kHz, μονοφωνικό, 16 bit. */
        private const val IN_RATE = 16000

        /** Πόσο μετά το τελευταίο καρέ ήχου θεωρούμε ότι σταμάτησε να μιλάει. */
        private const val SPEAK_TAIL_MS = 300L
    }

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var outRate = 16000
    @Volatile private var lastAudioAt = 0L

    /** Ουρά αναπαραγωγής. Φραγμένη: αν γεμίσει, καλύτερα να χαθεί ήχος παρά μνήμη. */
    private val playQueue = ArrayBlockingQueue<ShortArray>(64)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // ο ήχος έρχεται συνεχώς
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForegroundNotice()
        Voice.active = true
        note("φωνή", "η υπηρεσία ξεκίνησε")
        connect()
        startCapture()
        startPlayback()
        startModeTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { ws?.close(1000, "τέλος") } catch (e: Throwable) { }
        try { recorder?.stop(); recorder?.release() } catch (e: Throwable) { }
        try { track?.stop(); track?.release() } catch (e: Throwable) { }
        Voice.reset()
        Voice.status = "ανενεργή"
        note("φωνή", "η υπηρεσία σταμάτησε")
        super.onDestroy()
    }

    private fun note(key: String, value: String) {
        try { Diag.put(this, key, value) } catch (e: Throwable) { }
    }

    // ------------------------------------------------------------ ειδοποίηση

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ΑΙΑΣ — φωνή", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ΑΙΑΣ")
            .setContentText("Ακούει")
            .setSmallIcon(R.drawable.ic_aias)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ------------------------------------------------------------ σύνδεση

    private fun connect() {
        val id = BuildConfig.AGENT_ID
        if (id.isBlank()) {
            Voice.status = "λείπει το agent ID"
            note("φωνή", "λείπει το agent ID — δες local.properties")
            Log.e(TAG, "AGENT_ID κενό")
            return
        }
        val url = "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=$id"
        Voice.status = "σύνδεση…"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "συνδέθηκε")
                Voice.status = "συνδεδεμένος"
                Voice.mode = "listen"
                note("φωνή", "συνδεδεμένος στον agent")
                webSocket.send(JSONObject()
                    .put("type", "conversation_initiation_client_data").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "αποτυχία WebSocket", t)
                Voice.status = "σφάλμα σύνδεσης: ${t.message}"
                Voice.mode = "idle"
                note("φωνή", "σφάλμα: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "έκλεισε: $code $reason")
                Voice.status = "αποσυνδέθηκε"
                Voice.mode = "idle"
            }
        })
    }

    private fun handle(webSocket: WebSocket, text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "conversation_initiation_metadata" -> {
                    val m = o.optJSONObject("conversation_initiation_metadata_event")
                    // π.χ. "pcm_44100" — ο agent διαλέγει, εμείς προσαρμοζόμαστε.
                    val fmt = m?.optString("agent_output_audio_format") ?: "pcm_16000"
                    outRate = fmt.substringAfterLast('_').toIntOrNull() ?: 16000
                    Log.i(TAG, "μορφή εξόδου $fmt → $outRate Hz")
                    note("φωνή", "συνομιλία ξεκίνησε, έξοδος $outRate Hz")
                    openTrack()
                }
                "audio" -> {
                    val b64 = o.optJSONObject("audio_event")?.optString("audio_base_64")
                    if (!b64.isNullOrEmpty()) enqueue(b64)
                }
                "ping" -> {
                    val id = o.optJSONObject("ping_event")?.optInt("event_id") ?: 0
                    webSocket.send(JSONObject().put("type", "pong").put("event_id", id).toString())
                }
                "interruption" -> {
                    // Ο χρήστης έκοψε τον agent: πετάμε ό,τι δεν παίχτηκε ακόμη,
                    // αλλιώς η φωνή συνεχίζει να μιλάει αφού έχει σταματήσει.
                    playQueue.clear()
                    try { track?.pause(); track?.flush(); track?.play() } catch (e: Throwable) { }
                    Voice.mode = "listen"
                }
                "user_transcript" ->
                    Voice.lastUser = o.optJSONObject("user_transcription_event")
                        ?.optString("user_transcript") ?: ""
                "agent_response" ->
                    Voice.lastAgent = o.optJSONObject("agent_response_event")
                        ?.optString("agent_response") ?: ""
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ακατανόητο μήνυμα", e)
        }
    }

    // ------------------------------------------------------------ μικρόφωνο

    private fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, IN_RATE / 5 * 2)   // ~200 ms
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // με ακύρωση ηχούς
                IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Throwable) {
            Log.e(TAG, "δεν άνοιξε το μικρόφωνο", e)
            note("φωνή", "δεν άνοιξε το μικρόφωνο: ${e.message}")
            null
        } ?: return

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            note("φωνή", "το μικρόφωνο δεν αρχικοποιήθηκε — λείπει η άδεια;")
            return
        }
        recorder = rec
        rec.startRecording()

        Thread({
            // Στέλνουμε ~100 ms τη φορά: αρκετά μικρό για να μη φαίνεται
            // καθυστέρηση, αρκετά μεγάλο για να μην πνίγεται το δίκτυο.
            val chunk = ShortArray(IN_RATE / 10)
            val bytes = ByteArray(chunk.size * 2)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var j = 0
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    bytes[j++] = (v and 0xFF).toByte()
                    bytes[j++] = ((v shr 8) and 0xFF).toByte()
                }
                val b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP)
                try {
                    ws?.send(JSONObject().put("user_audio_chunk", b64).toString())
                } catch (e: Throwable) { }
            }
        }, "aias-mic").start()
    }

    // ------------------------------------------------------------ ηχεία

    private fun openTrack() {
        try { track?.release() } catch (e: Throwable) { }
        val min = AudioTrack.getMinBufferSize(
            outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(outRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(min, outRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
    }

    private fun enqueue(b64: String) {
        val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (e: Throwable) { return }
        val pcm = ShortArray(raw.size / 2)
        for (i in pcm.indices) {
            pcm[i] = ((raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)).toShort()
        }
        // Αν η ουρά είναι γεμάτη, ρίχνουμε το παλιότερο: η καθυστέρηση είναι
        // χειρότερη από ένα χαμένο καρέ ήχου.
        if (!playQueue.offer(pcm)) { playQueue.poll(); playQueue.offer(pcm) }
    }

    /**
     * Το νήμα αναπαραγωγής. **Εδώ βγαίνει η στάθμη για τις τελείες**: υπολογίζεται
     * από το ίδιο κομμάτι τη στιγμή που γράφεται στα ηχεία, οπότε η εικόνα
     * ακολουθεί τη φωνή και όχι το δίκτυο.
     */
    private fun startPlayback() {
        Thread({
            while (running) {
                val pcm = playQueue.poll(120, TimeUnit.MILLISECONDS) ?: continue
                var sum = 0.0
                for (s in pcm) { val v = s / 32768.0; sum += v * v }
                val rms = sqrt(sum / maxOf(1, pcm.size))
                // Η φωνή σπάνια ξεπερνά RMS 0.3· κανονικοποιούμε εκεί ώστε να
                // φτάνει η στάθμη στην περιοχή που μετρήθηκε ως πορτοκαλί.
                Voice.level = min(1.0, rms / 0.30).toFloat()
                Voice.mode = "speak"
                lastAudioAt = System.currentTimeMillis()
                try { track?.write(pcm, 0, pcm.size) } catch (e: Throwable) { }
            }
        }, "aias-play").start()
    }

    /** Όταν σταματήσει ο ήχος, επιστρέφουμε στην ακρόαση και σβήνει η στάθμη. */
    private fun startModeTicker() {
        Thread({
            while (running) {
                val quiet = System.currentTimeMillis() - lastAudioAt > SPEAK_TAIL_MS
                if (quiet && Voice.mode == "speak") Voice.mode = "listen"
                if (quiet) Voice.level *= 0.80f
                try { Thread.sleep(50) } catch (e: InterruptedException) { break }
            }
        }, "aias-mode").start()
    }
}
