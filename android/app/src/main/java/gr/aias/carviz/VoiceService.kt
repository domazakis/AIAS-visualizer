package gr.aias.carviz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

        /** Πόσα δείγματα καλύπτει μία τιμή της περιβάλλουσας: ~20 ms. */
        private const val ENV_MS = 20

        /**
         * Θέσεις στον κυκλικό πίνακα της περιβάλλουσας. Στα 20 ms η καθεμιά,
         * οι 8192 είναι δυόμισι λεπτά συνεχούς ομιλίας — πολλαπλάσιο του
         * μέγιστου που μπορεί να προηγείται η γραφή της ανάγνωσης.
         */
        private const val ENV_SIZE = 8192

        /** Το ανοιχτό WebSocket, για τη [δοκιμή]. */
        @Volatile private var live: WebSocket? = null

        /**
         * Βάζει τον agent να μιλήσει **χωρίς να μιλήσει κανείς**.
         *
         * Επί δέκα μέρες κάθε δοκιμή της εικόνας απαιτούσε άνθρωπο να πει κάτι
         * σε μικρόφωνο — που σημαίνει ότι τίποτα δεν ελεγχόταν από εδώ, και
         * κάθε λάθος στη στάθμη το ανακάλυπτε ο οδηγός στον δρόμο. Μια γραπτή
         * ατάκα κάνει τον βοηθό να απαντήσει κανονικά, με πραγματικό ήχο, άρα
         * ελέγχεται ολόκληρη η αλυσίδα ως τα ηχεία και τις τελείες.
         */
        fun δοκιμή(text: String = "Πες μου με δικά σου λόγια, σε πέντε έξι προτάσεις, τι είναι ο χάρτης και γιατί τον φτιάχνει ο άνθρωπος."): Boolean {
            val w = live ?: return false
            return try {
                w.send(JSONObject().put("type", "user_message").put("text", text).toString())
            } catch (e: Throwable) { false }
        }
    }

    private var ws: WebSocket? = null
    private val retrying = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var outRate = 16000

    /** Ουρά αναπαραγωγής. Φραγμένη: αν γεμίσει, καλύτερα να χαθεί ήχος παρά μνήμη. */
    private val playQueue = ArrayBlockingQueue<ShortArray>(64)

    // ------------------------------------------------ η περιβάλλουσα του ήχου
    //
    // Η στάθμη ΔΕΝ βγαίνει πια από το κομμάτι τη στιγμή που το γράφουμε: αυτό
    // ήταν το λάθος που έκανε την εικόνα άσχετη με τη φωνή. Το `track.write()`
    // ΜΠΛΟΚΑΡΕΙ όσο αδειάζει ο απομονωτής, άρα η στάθμη οριζόταν μία φορά ανά
    // κομμάτι, μισό δευτερόλεπτο ΠΡΙΝ ακουστεί ο ήχος, και έμενε παγωμένη σε
    // όλο του το μήκος. Τώρα κρατάμε την ενέργεια ανά 20 ms σε κυκλικό πίνακα
    // και τη διαβάζουμε με τη ΘΕΣΗ ΤΗΣ ΚΕΦΑΛΗΣ ΑΝΑΠΑΡΑΓΩΓΗΣ — δηλαδή με ό,τι
    // βγαίνει από το ηχείο αυτή τη στιγμή. Ο συγχρονισμός γίνεται ταυτότητα,
    // όχι εκτίμηση.

    private val env = FloatArray(ENV_SIZE)
    /** Πόσες θέσεις έχουν γραφτεί — αυξάνει μονότονα από το τελευταίο άδειασμα. */
    @Volatile private var envWrite = 0
    /** Δείγματα που δόθηκαν στο [AudioTrack] από το τελευταίο άδειασμα. */
    @Volatile private var written = 0L
    private var envAcc = 0.0
    private var envCount = 0
    private var envHop = 320

    /** Η κορυφή των τελευταίων δευτερολέπτων, για τον αυτόματο έλεγχο κέρδους. */
    private var peak = 0.0

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
        startLevelTicker()
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

    // ------------------------------------------------------------ δίκτυο

    /**
     * Υπάρχει ίντερνετ;
     *
     * Μπήκε επειδή στο αυτοκίνητο το κινητό **δεν έχει κάρτα SIM** και δεν
     * υπάρχει Wi-Fi· το WebSocket απέτυχε με μήνυμα `null`, τα διαγνωστικά
     * έγραψαν «σφάλμα: null» και στην οθόνη φαινόταν απλώς ένας βοηθός που δεν
     * μιλάει. Η διαφορά ανάμεσα σε «δεν έχω γραμμή» και «κάτι χάλασε» πρέπει
     * να είναι ορατή πριν ξεκινήσουμε, όχι να συμπεραίνεται μετά.
     */
    private fun online(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Throwable) { true }   // σε αμφιβολία, δοκιμάζουμε

    // ------------------------------------------------------------ σύνδεση

    private fun connect() {
        val id = BuildConfig.AGENT_ID
        if (id.isBlank()) {
            Voice.status = "λείπει το agent ID"
            note("φωνή", "λείπει το agent ID — δες local.properties")
            Log.e(TAG, "AGENT_ID κενό")
            return
        }
        if (!online()) {
            Voice.status = "χωρίς ίντερνετ"
            note("φωνή", "χωρίς ίντερνετ — ο agent είναι στο δίκτυο")
            Log.w(TAG, "καμία σύνδεση· περιμένουμε")
            retryLater()
            return
        }
        val url = "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=$id"
        Voice.status = "σύνδεση…"
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "συνδέθηκε")
                live = webSocket

                Voice.status = "συνδεδεμένος"
                Voice.mode = "listen"
                note("φωνή", "συνδεδεμένος στον agent")
                webSocket.send(JSONObject()
                    .put("type", "conversation_initiation_client_data").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "αποτυχία WebSocket", t)
                // Το `t.message` είναι συχνά κενό· η κλάση λέει πάντα κάτι.
                live = null

                val why = t.message ?: t.javaClass.simpleName
                val msg = if (!online()) "χωρίς ίντερνετ" else "σφάλμα σύνδεσης: $why"
                Voice.status = msg
                Voice.mode = "idle"
                note("φωνή", msg)
                retryLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                live = null
                Log.i(TAG, "έκλεισε: $code $reason")
                Voice.status = "αποσυνδέθηκε"
                Voice.mode = "idle"
                retryLater()
            }
        })
    }

    /**
     * Ξαναδοκιμάζει σε τρία δευτερόλεπτα, όσο τρέχει η υπηρεσία.
     *
     * Χωρίς αυτό, μία αποτυχία στην αρχή —μπαίνοντας στο αυτοκίνητο πριν
     * πιάσει το hotspot, ας πούμε— σήμαινε βουβό βοηθό για όλη τη διαδρομή,
     * με το κουμπί να λέει «Σταμάτα» σαν να δούλευε.
     */
    private fun retryLater() {
        // Η αποτυχία φτάνει και από το `onFailure` και από το `onClosed`· χωρίς
        // τον μανδαλωτή θα ξεκινούσαν δύο προσπάθειες για το ίδιο πράγμα.
        if (!running || !retrying.compareAndSet(false, true)) return
        Thread({
            try { Thread.sleep(3000) } catch (e: InterruptedException) { }
            retrying.set(false)
            if (!running) return@Thread
            try { ws?.cancel() } catch (e: Throwable) { }
            ws = null
            connect()
        }, "aias-retry").start()
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
                    flushAudio()
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
            var silent = 0
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var j = 0
                var sum = 0.0
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    sum += (v / 32768.0) * (v / 32768.0)
                    bytes[j++] = (v and 0xFF).toByte()
                    bytes[j++] = ((v shr 8) and 0xFF).toByte()
                }
                // Αν το μικρόφωνο δίνει απόλυτη σιωπή για δέκα δευτερόλεπτα,
                // κάποιος άλλος μας το πήρε — ο Βοηθός του αυτοκινήτου είναι ο
                // υποψήφιος. Το καταγράφουμε: αλλιώς φαίνεται σαν να μη μιλάει
                // κανείς, και δεν ξεχωρίζει από τη σιωπή του οδηγού.
                val mrms = sqrt(sum / n).toFloat()
                Voice.noteMic(mrms)
                if (mrms < 1e-5f) silent++ else silent = 0
                if (silent == 100) note("φωνή", "το μικρόφωνο δίνει σιωπή — το πήρε άλλος;")
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
        envHop = max(1, outRate * ENV_MS / 1000)
        resetTimeline()
        t.play()
        track = t
        noteRoute(t)
    }

    /**
     * Πού βγαίνει τελικά ο ήχος.
     *
     * Στο αυτοκίνητο δεν ακούστηκε τίποτα και δεν υπήρχε τρόπος να ξεχωρίσεις
     * «δεν ήρθε ήχος» από «ήρθε αλλά βγήκε στο ηχείο του κινητού». Το
     * [AudioTrack.getRoutedDevice] το λέει με το όνομα της συσκευής.
     */
    private fun noteRoute(t: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        Thread({
            try { Thread.sleep(400) } catch (e: InterruptedException) { return@Thread }
            val d = try { t.routedDevice } catch (e: Throwable) { null }
            val name = when (d?.type) {
                null -> "άγνωστη"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "ηχείο κινητού"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (μουσική)"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (κλήση)"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                AudioDeviceInfo.TYPE_BUS -> "δίαυλος αυτοκινήτου"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "ακουστικά"
                else -> "τύπος ${d.type}"
            }
            Voice.route = name
            note("ήχος", "$name · $outRate Hz")
        }, "aias-route").start()
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

    // -------------------------------------------------- χρονογραμμή και στάθμη

    private fun resetTimeline() {
        synchronized(env) {
            java.util.Arrays.fill(env, 0f)
            envWrite = 0
            envAcc = 0.0
            envCount = 0
            written = 0L
        }
    }

    /** Το `flush()` μηδενίζει την κεφαλή· μηδενίζουμε μαζί και τη χρονογραμμή. */
    private fun flushAudio() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            resetTimeline()
            t.play()
        } catch (e: Throwable) { }
    }

    /** Γράφει την ενέργεια του κομματιού στη χρονογραμμή, ανά [envHop] δείγματα. */
    private fun fillEnvelope(pcm: ShortArray) {
        synchronized(env) {
            for (s in pcm) {
                val v = s / 32768.0
                envAcc += v * v
                if (++envCount >= envHop) {
                    env[envWrite % ENV_SIZE] = sqrt(envAcc / envCount).toFloat()
                    envWrite++
                    envAcc = 0.0
                    envCount = 0
                }
            }
            written += pcm.size
        }
    }

    /**
     * Το νήμα αναπαραγωγής: αποκλειστικά μεταφορά. Καμία στάθμη εδώ — το
     * `write` μπλοκάρει, και ό,τι υπολογιστεί πριν από αυτό είναι το μέλλον,
     * όχι το παρόν.
     */
    private fun startPlayback() {
        Thread({
            while (running) {
                val pcm = playQueue.poll(120, TimeUnit.MILLISECONDS) ?: continue
                fillEnvelope(pcm)
                try { track?.write(pcm, 0, pcm.size) } catch (e: Throwable) { }
            }
        }, "aias-play").start()
    }

    /**
     * Η στάθμη και η κατάσταση, από τη θέση της κεφαλής αναπαραγωγής.
     *
     * Τρέχει στα 60 Hz — τρεις μετρήσεις ανά τιμή περιβάλλουσας, αρκετές για
     * να μη χάνεται συλλαβή. Η κατάσταση «ομιλία» **δεν** στηρίζεται πια σε
     * χρονόμετρο: μιλάει όσο υπάρχει ήχος στον απομονωτή που δεν έχει βγει
     * ακόμη. Το παλιό χρονόμετρο των 400 ms μετρούσε από την ΑΦΙΞΗ κομματιού,
     * κι επειδή ένα κομμάτι μπορεί να γράφεται για ένα δευτερόλεπτο, η
     * κατάσταση πεταγόταν στην «ακρόαση» στη μέση της πρότασης — ακριβώς το
     * «σταματούσαν οι μπάρες και γινόταν ο κυματισμός ενώ μιλούσε».
     */
    private fun startLevelTicker() {
        Thread({
            var prev = System.nanoTime()
            while (running) {
                try { Thread.sleep(16) } catch (e: InterruptedException) { break }
                val now = System.nanoTime()
                val dt = ((now - prev) / 1e9).coerceIn(0.001, 0.1)
                prev = now

                val t = track
                val head = if (t == null) 0L else try {
                    t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                } catch (e: Throwable) { 0L }

                var e = 0f
                var speaking = false
                synchronized(env) {
                    // Το υπόλοιπο στον απομονωτή: όσο είναι θετικό, ακούγεται.
                    val pending = written - head
                    speaking = pending > envHop / 2
                    val idx = (head / envHop).toInt()
                    if (idx in 0 until envWrite && envWrite - idx <= ENV_SIZE) {
                        e = env[idx % ENV_SIZE]
                    }
                }

                if (speaking) {
                    // ΑΥΤΟΜΑΤΟΣ ΕΛΕΓΧΟΣ ΚΕΡΔΟΥΣ, τώρα σωστά.
                    //
                    // Η προηγούμενη εκδοχή έγραφε `peak = max(rms, peak*0.985)`
                    // και αμέσως μετά `rms/peak`: κάθε κομμάτι πιο δυνατό από
                    // το προηγούμενο **όριζε το ίδιο** την κορυφή και έβγαινε
                    // ακριβώς 1.0. Γι' αυτό «μιλούσε πάντα στο τέρμα». Εδώ η
                    // κορυφή ανεβαίνει ομαλά και πέφτει με τον ΧΡΟΝΟ, όχι με
                    // τον ρυθμό άφιξης των πακέτων· καμία στιγμή δεν ορίζει
                    // μόνη της το ταβάνι της.
                    // Ακαριαία άνοδος, αργή κάθοδος — ο κλασικός ανιχνευτής
                    // κορυφής. Δοκιμάστηκε και η ομαλή άνοδος (0.12 και 0.06
                    // ανά βήμα) με περιθώριο 20% στην αναφορά: δεν άλλαξε
                    // τίποτα, `hi` πάλι 1,00 σε κάθε δευτερόλεπτο ομιλίας.
                    // Ο λόγος είναι ότι η αρχή κάθε συλλαβής ανεβαίνει μέσα σε
                    // δύο-τρεις τιμές περιβάλλουσας, πολύ πιο γρήγορα από
                    // οποιαδήποτε ομαλή άνοδο — άρα την προσπερνούσε πάντα.
                    //
                    // Με ακαριαία άνοδο η κορυφή ΕΙΝΑΙ η δυνατότερη πρόσφατη
                    // στιγμή εξ ορισμού, και ο λόγος `e/peak` γίνεται καθαρή
                    // αναλογία: 1 στη δυνατότερη συλλαβή, μισό στη μισή. Η
                    // δυναμική δεν εξαρτάται πια από τον ρυθμό του ανιχνευτή.
                    if (e > peak) peak = e.toDouble() else peak *= exp(-dt / 2.0)
                    val ref = max(peak, 0.02)
                    val norm = (e / ref).coerceIn(0.0, 1.0)
                    Voice.noteRaw(norm.toFloat())
                    Voice.level = norm.pow(0.75).toFloat()
                    Voice.mode = "speak"
                } else {
                    // Σβήσιμο με τον χρόνο: 120 ms σταθερά, ίδιο σε κάθε ρυθμό.
                    Voice.level *= exp(-dt / 0.12).toFloat()
                    if (Voice.level < 0.004f) Voice.level = 0f
                    if (Voice.mode == "speak") Voice.mode = "listen"
                }
                Voice.note(Voice.level)
            }
        }, "aias-level").start()
    }
}
