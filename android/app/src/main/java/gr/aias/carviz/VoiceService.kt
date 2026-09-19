package gr.aias.carviz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
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

        /**
         * Πόσο κρατάμε την εστίαση ήχου μετά τη σιωπή.
         *
         * Ήταν ενάμισι δευτερόλεπτο, και το πληρώναμε δύο φορές. Πρώτον, η
         * `requestAudioFocus` είναι κλήση προς άλλη διεργασία και έμπαινε στον
         * κρίσιμο δρόμο της **πρώτης συλλαβής κάθε ατάκας**. Δεύτερον, με
         * παύσεις 3,6 δευτερολέπτων ανάμεσα στις ατάκες, το ραδιόφωνο χαμήλωνε
         * και ξαναδυνάμωνε σε κάθε γύρο — αντλία αντί για συνομιλία.
         *
         * Οκτώ δευτερόλεπτα σημαίνει ότι μέσα σε μια συνομιλία η εστίαση
         * ζητιέται μία φορά και μετά υπάρχει ήδη.
         */
        private const val FOCUS_HOLD_NS = 8_000_000_000L

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
    /** Πόσες φορές δοκιμάστηκε σύνδεση — μπαίνει στα διαγνωστικά. */
    private var attempt = 0
    /** Πότε γράφτηκε τελευταία η γραμμή «στάθμη». */
    private var diagMark = 0L
    /** Από πότε κρατάει η σιωπή — για την καθυστερημένη παράδοση εστίασης. */
    @Volatile private var quietSince = 0L
    /** Τελευταία θέση κεφαλής και πότε κουνήθηκε — ανίχνευση παγώματος. */
    private var lastHead = -1L
    private var lastHeadMoveAt = 0L
    /** Το προηγούμενο υπόλοιπο· το ρολόι παγώματος ξεκινά όταν εμφανιστεί ουρά. */
    private var prevPending = 0L
    /** Πόσες φορές ξαναδέθηκε η χρονογραμμή: πραγματικά παγώματα και διαφωνίες. */
    @Volatile private var freezes = 0
    @Volatile private var desyncs = 0
    /** Μετρητές της διαδρομής μικρόφωνο → δίκτυο, για να μη χάνεται τίποτα σιωπηλά. */
    @Volatile private var reads = 0
    @Volatile private var readFails = 0
    @Volatile private var sent = 0
    @Volatile private var sendFails = 0
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
        routeToPhone()
        watchDevices()
        raiseCallVolume()
        val rec = Rec.start(this)
        note("εγγραφή", rec?.substringAfterLast('/') ?: "δεν ξεκίνησε")
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
        abandonFocus()
        restoreCallVolume()
        unwatchDevices()
        releasePhoneRoute()
        Rec.stop()
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

    /**
     * Τι δίκτυο υπάρχει, με λόγια — για τα διαγνωστικά.
     *
     * Η πρώτη διάγνωση της αποτυχίας στο αυτοκίνητο ήταν «δεν υπήρχε ίντερνετ»,
     * και ήταν ελλιπής: είχε ανοίξει hotspot, απλώς λίγο αργότερα από το πρώτο
     * πάτημα. Χωρίς καταγραφή του δικτύου, η διαφορά ανάμεσα σε «δεν υπήρχε
     * γραμμή», «υπήρχε Wi-Fi χωρίς έξοδο» και «υπήρχαν όλα και έφταιγε αλλού»
     * ήταν αδύνατο να βγει εκ των υστέρων. Τώρα γράφεται πριν από κάθε
     * προσπάθεια.
     *
     * Το `VALIDATED` είναι η ουσιαστική διάκριση: το `INTERNET` σημαίνει μόνο
     * ότι το δίκτυο **ισχυρίζεται** έξοδο, ενώ το `VALIDATED` ότι το σύστημα
     * την επαλήθευσε. Ένα hotspot που μόλις σηκώθηκε έχει το πρώτο πριν από
     * το δεύτερο.
     */
    private fun networkNote(): String = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null) "κανένα δίκτυο" else buildString {
            append(when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "κινητό δίκτυο"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "άλλο"
            })
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                append(" · χωρίς έξοδο")
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                append(" · ανεπιβεβαίωτη έξοδος")
            else append(" · εντάξει")
        }
    } catch (e: Throwable) { "άγνωστο" }

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
            note("δίκτυο", networkNote() + " · αναμονή")
            Log.w(TAG, "καμία σύνδεση· περιμένουμε")
            retryLater()
            return
        }
        val url = "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=$id"
        Voice.status = "σύνδεση…"
        note("δίκτυο", "${networkNote()} · προσπάθεια ${++attempt}")
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
                note("δίκτυο", networkNote() + " · απέτυχε: $why")
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
            // Στέλνουμε ~40 ms τη φορά. Ήταν 100, και η ανίχνευση τέλους λόγου
            // στον server δεν μπορεί να δει τη σιωπή πριν φτάσει το κομμάτι που
            // την περιέχει: κάθε κομμάτι είναι κβάντο καθυστέρησης. Κάτω από 40
            // ms δεν αξίζει — το κόστος του πλαισίου WebSocket μεγαλώνει.
            val chunk = ShortArray(IN_RATE / 25)
            val bytes = ByteArray(chunk.size * 2)
            var silent = 0
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                // ΚΑΘΕ ΑΠΟΤΥΧΙΑ ΜΕΤΡΙΕΤΑΙ.
                //
                // Στο S24 έφτασαν στον server μόνο 36,6 από τα 171 δευτερόλεπτα
                // της κλήσης. Τα 134 που λείπουν δεν εξηγούνται από την πύλη —
                // εκείνη έκλεινε μόνο όσο μιλούσε ο ΑΙΑΣ, δηλαδή 15 δευτερόλεπτα,
                // και ακόμη και τότε στέλναμε σιωπή, που μετριέται κανονικά.
                // Άρα κάτι άλλο σταμάτησε να στέλνει, και το καταπίναμε χωρίς
                // ίχνος: το `continue` εδώ και το `catch` της αποστολής.
                if (n <= 0) { readFails++; continue }
                reads++
                Rec.writeMic(chunk, n)
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
                if (silent == 750) note("μικρόφωνο", "σιωπή 30 δευτερολέπτων — το πήρε άλλος;")

                // ΚΑΤΑΣΤΟΛΗ ΗΧΟΥΣ.
                //
                // Η ακύρωση ηχούς του κινητού ακυρώνει ό,τι παίζει ΤΟ ΚΙΝΗΤΟ.
                // Εδώ ο ήχος βγαίνει από τα ηχεία του αυτοκινήτου μέσω της
                // προβολής, οπότε δεν τον βλέπει καθόλου — και ο ΑΙΑΣ ακούει
                // τον εαυτό του. Το αποτέλεσμα δεν είναι μόνο ότι κόβεται:
                // η φωνή του γίνεται «λόγος του χρήστη», απομαγνητοφωνείται
                // στραβά, και μετά από ώρα συνομιλίας με τον εαυτό του τα
                // ελληνικά του διαλύονται και αρχίζει να απαγγέλλει κομμάτια
                // των οδηγιών του.
                //
                // Η πύλη μαθαίνει μόνη της πόσο δυνατή είναι η ηχώ: όσο μιλάει
                // ο ΑΙΑΣ, ό,τι δεν περνάει το κατώφλι θεωρείται ηχώ και ανεβάζει
                // την αναφορά. Ό,τι είναι σαφώς δυνατότερο —ο οδηγός που μιλάει
                // από κοντά— περνάει κανονικά, άρα **η διακοπή με τη φωνή
                // εξακολουθεί να δουλεύει**. Γι' αυτό και προτιμήθηκε από το
                // να κλείνει απλώς το μικρόφωνο.
                // ΤΟ ΜΙΚΡΟΦΩΝΟ ΜΕΝΕΙ ΑΝΟΙΧΤΟ. ΠΑΝΤΑ.
                //
                // Πέρασαν από εδώ δύο μηχανισμοί και οι δύο έφυγαν. Πρώτα
                // κατώφλι στάθμης, που ήταν λάθος σχεδίασης: καμία τιμή δεν
                // ξεχωρίζει τον οδηγό από την ηχώ, γιατί η ηχώ μεγαλώνει με την
                // ένταση των ηχείων. Μετά σιωπή όσο μιλούσε, που δούλευε αλλά
                // σκότωνε τη διακοπή με τη φωνή.
                //
                // Και τα δύο ήταν μπαλώματα σε λάθος σημείο. Η αιτία ήταν ότι ο
                // ήχος έφευγε στα ηχεία του αυτοκινήτου, όπου ο ακυρωτής ηχούς
                // του κινητού δεν έχει αναφορά. Τώρα που ο ήχος μένει στο
                // κινητό —δες [routeToPhone]— ο ακυρωτής δουλεύει κανονικά,
                // όπως αποδείχθηκε σε ανοιχτή ακρόαση: εκεί δεν κόπηκε ποτέ.
                //
                // Άρα το μικρόφωνο μένει ανοιχτό και η διακοπή με τη φωνή
                // επιστρέφει. Αν η ηχώ ξαναφανεί, θα φανεί αμέσως στις
                // απομαγνητοφωνήσεις ως «ο χρήστης επαναλαμβάνει τον agent».

                val b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP)
                try {
                    val w = ws
                    if (w == null) sendFails++
                    else if (w.send(JSONObject().put("user_audio_chunk", b64).toString())) sent++
                    else sendFails++
                } catch (e: Throwable) { sendFails++ }
            }
        }, "aias-mic").start()
    }

    // ------------------------------------------------------------ ηχεία

    /**
     * Τα χαρακτηριστικά του ήχου μας. **Καθοδήγηση πλοήγησης**, όχι «βοηθός».
     *
     * Ήταν `USAGE_ASSISTANT` και δεν ακουγόταν τίποτα — ούτε στον εξομοιωτή
     * ούτε στο αυτοκίνητο. Δύο λόγοι, και οι δύο διορθώνονται εδώ.
     *
     * Πρώτος: το κανάλι του «βοηθού» στο Android Auto το κρατάει ο Βοηθός της
     * Google. Εμείς είμαστε δηλωμένοι **εφαρμογή πλοήγησης** — η κατηγορία που
     * μας δίνει και την επιφάνεια σχεδίασης — και το κανάλι της καθοδήγησης
     * είναι ακριβώς αυτό που κάθε αυτοκίνητο ξέρει να παίζει και να χαμηλώνει
     * τη μουσική από πάνω του. Η φωνή του ΑΙΑΝΤΑ είναι, από τη σκοπιά του
     * host, οδηγία πλοήγησης.
     *
     * Δεύτερος: δες την [requestFocus].
     */
    private val outAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: Any? = null
    @Volatile private var hasFocus = false

    /** Ο παρατηρητής συσκευών ήχου — δες [watchDevices]. */
    private var deviceWatcher: Any? = null

    /** Η ένταση κλήσης πριν την πειράξουμε — δες [raiseCallVolume]. */
    private var savedCallVolume = -1

    /**
     * Ο ήχος μένει ΣΤΟ ΚΙΝΗΤΟ, και αυτό δεν είναι υποχώρηση.
     *
     * Ως τώρα η φωνή έφευγε στα ηχεία του αυτοκινήτου μέσω της προβολής. Εκεί
     * το κινητό δεν ξαναβλέπει ποτέ το σήμα, άρα ο ακυρωτής ηχούς του δεν έχει
     * **αναφορά** και δεν μπορεί να αφαιρέσει τίποτα: ο ΑΙΑΣ ακούει τον εαυτό
     * του και κόβεται. Η παρατήρηση που το έλυσε ήταν του χρήστη — σε ανοιχτή
     * ακρόαση στο κινητό δεν συμβαίνει ποτέ, στο αυτοκίνητο πάντα.
     *
     * Με χρήση `VOICE_COMMUNICATION` και στις δύο άκρες, η αναπαραγωγή γίνεται
     * η αναφορά της εγγραφής: ο ακυρωτής ξέρει ακριβώς τι παίζει και το κόβει.
     * Παράπλευρα, η προβολή δεν αρπάζει αυτό το κανάλι — είναι κανάλι κλήσης.
     *
     * Και ταιριάζει με τη χρήση: ακουστικό στο αυτί για τον οδηγό, ή ανοιχτή
     * ακρόαση με ένταση που ελέγχεται από το κινητό. Στην οθόνη του
     * αυτοκινήτου μένει αυτό που θέλαμε εξαρχής — οι τελείες.
     */
    private fun routeToPhone() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // ΤΟ BLUETOOTH ΤΟΥ ΑΥΤΟΚΙΝΗΤΟΥ ΔΕΝ ΕΙΝΑΙ ΑΚΟΥΣΤΙΚΟ.
            //
            // Εδώ ήταν και το `TYPE_BLUETOOTH_SCO`, με σκεπτικό «ο οδηγός
            // διάλεξε ακουστικό, δεν του το χαλάμε». Στο αυτοκίνητο όμως το
            // ίδιο το MG είναι ζευγαρωμένο ως hands-free: η εξαίρεση έπιανε
            // ακριβώς τη μία περίπτωση που έπρεπε να αποφύγουμε, κι έτσι όλο
            // το νόημα της έκδοσης 32 —ο ήχος να μένει στο κινητό— μπορούσε
            // να ακυρωθεί από αυτή τη μία γραμμή.
            //
            // Ακουστικό είναι μόνο ό,τι μπαίνει με καλώδιο στο κινητό.
            val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (headset) { note("ήχος", "ακουστικό — χωρίς επιβολή"); return }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val spk = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                // Η ΕΠΙΣΤΡΟΦΗ ΜΕΤΡΑΕΙ. Την αγνοούσαμε, κι έτσι μια αποτυχία
                // δρομολόγησης ήταν ακριβώς τόσο σιωπηλή όσο μια επιτυχία.
                val ok = spk != null && am.setCommunicationDevice(spk)
                if (!ok) note("ήχος", "ΔΕΝ κρατήθηκε στο ηχείο του κινητού")
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } catch (e: Throwable) {
            // ΚΑΙ ΣΤΑ ΔΙΑΓΝΩΣΤΙΚΑ, όχι μόνο στο logcat: στο αυτοκίνητο δεν
            // υπάρχει logcat, και μια `SecurityException` εδώ σημαίνει ότι ο
            // ήχος δεν κρατήθηκε ποτέ στο κινητό.
            Log.w(TAG, "δεν μπόρεσα να κρατήσω τον ήχο στο κινητό", e)
            note("ήχος", "η δρομολόγηση απέτυχε: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Η δρομολόγηση ξαναμπαίνει σε ΚΑΘΕ αλλαγή συσκευών ήχου.
     *
     * Μία φορά στην εκκίνηση δεν αρκεί, και το πληρώσαμε ολόκληρη διαδρομή:
     * στις 18/09 η υπηρεσία ξεκίνησε 19:40:17 και το Bluetooth του
     * αυτοκινήτου συνδέθηκε 19:40:21 — τέσσερα δευτερόλεπτα αργότερα. Η
     * [routeToPhone] είχε ήδη αποφασίσει σε έναν κόσμο χωρίς αυτοκίνητο, και
     * ο ήχος μετακόμισε από κάτω της. Το διαγνωστικό το έγραψε καθαρά:
     * «ήχος: Bluetooth (μουσική)», δηλαδή στα ηχεία του MG.
     */
    private fun watchDevices() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = recheck()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = recheck()
                private fun recheck() {
                    if (!running) return
                    routeToPhone()
                    // Και ξαναμετράμε πού βγήκε τελικά — αλλιώς η γραμμή
                    // «ήχος» έμενε από το άνοιγμα του καναλιού και έλεγε
                    // ψέματα για την υπόλοιπη διαδρομή.
                    track?.let { noteRoute(it) }
                    raiseCallVolume()
                }
            }
            deviceWatcher = cb
            am.registerAudioDeviceCallback(cb, null)
        } catch (e: Throwable) { }
    }

    /**
     * Η ένταση της ΚΛΗΣΗΣ, που δεν είναι η ένταση που ανεβάζει ο οδηγός.
     *
     * Στις 18/09 δεν ακούστηκε ούτε λέξη, και ο λόγος ήταν αυτός ο αριθμός:
     *
     *     STREAM_VOICE_CALL … 80 (bt_a2dp): 1      (min 1, max 8)
     *
     * Ο ΑΙΑΣ παίζει ως `USAGE_VOICE_COMMUNICATION`, άρα στο κανάλι κλήσης.
     * Στο Bluetooth του αυτοκινήτου το κανάλι αυτό ήταν στο **ένα στα οκτώ**,
     * δηλαδή στο απόλυτο ελάχιστο. Και όταν ο οδηγός πάτησε ένταση, το
     * σύστημα κούνησε το κανάλι **μουσικής** (`stream=3`, 0→6 και μετά 0→15),
     * επειδή για εκείνο δεν υπήρχε ενεργή κλήση. Η φωνή έμεινε στο ένα.
     *
     * Δεν είναι κάτι που μπορεί να βρει ο χρήστης: η ένταση κλήσης ρυθμίζεται
     * μόνο ΜΕΣΑ σε κλήση. Άρα τη σηκώνουμε εμείς και τη γυρνάμε πίσω στο
     * τέλος. Η τιμή είναι ανά συσκευή εξόδου, γι' αυτό ξαναμπαίνει σε κάθε
     * αλλαγή δρομολόγησης.
     */
    private fun raiseCallVolume() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val cur = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val want = max - max / 8
            if (cur >= want) { note("ένταση", "κλήση $cur/$max — εντάξει"); return }
            // Κρατάμε μόνο την πρώτη τιμή που βρήκαμε: αν τη σώζαμε ξανά σε
            // κάθε αλλαγή, θα γυρνούσαμε πίσω τη δική μας.
            if (savedCallVolume < 0) savedCallVolume = cur
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0)
            val got = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            note("ένταση", "κλήση $cur → $got (από $max)")
        } catch (e: Throwable) {
            note("ένταση", "δεν άλλαξε: ${e.javaClass.simpleName}")
        }
    }

    private fun restoreCallVolume() {
        val v = savedCallVolume
        savedCallVolume = -1
        if (v < 0) return
        val am = getSystemService(AudioManager::class.java) ?: return
        try { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, v, 0) } catch (e: Throwable) { }
    }

    private fun unwatchDevices() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            (deviceWatcher as? AudioDeviceCallback)
                ?.let { am.unregisterAudioDeviceCallback(it) }
        } catch (e: Throwable) { }
        deviceWatcher = null
    }

    private fun releasePhoneRoute() {
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
            @Suppress("DEPRECATION")
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Throwable) { }
    }

    /**
     * Ζητά την εστίαση ήχου πριν μιλήσει.
     *
     * **Δεν τη ζητούσαμε ποτέ.** Αυτό ήταν αρκετό για να μην ακουστεί τίποτα:
     * στο αυτοκίνητο ο host δεν ανοίγει κανάλι προς τα ηχεία για ροή που δεν
     * έχει εστίαση, όσο σωστά κι αν δρομολογείται. Και η δρομολόγηση ΗΤΑΝ
     * σωστή — το διαγνωστικό έγραφε «προβολή στο αυτοκίνητο» — γι' αυτό και
     * κόντεψε να με παραπλανήσει: ο ήχος έφευγε κανονικά προς το Android Auto,
     * απλώς δεν είχε άδεια να βγει από την άλλη μεριά.
     *
     * `TRANSIENT_MAY_DUCK` και όχι μόνιμη: ο ΑΙΑΣ μιλάει σε ριπές. Χαμηλώνει
     * τη μουσική όσο μιλάει και την αφήνει να επανέλθει μόλις σωπάσει, αντί
     * να την κρατά πνιγμένη για όλη τη διαδρομή.
     */
    private fun requestFocus() {
        // Η ΣΙΩΠΗ ΤΕΛΕΙΩΣΕ ΤΩΡΑ, όχι όταν φτάσει ο ήχος στην κεφαλή.
        //
        // Χωρίς αυτή τη γραμμή ο μετρητής σιωπής κρατούσε την προηγούμενη
        // παύση, και ο επόμενος κύκλος των 16 ms παρέδιδε την εστίαση **20
        // χιλιοστά αφού μόλις τη ζητήσαμε** — πριν βγει ένα δείγμα. Μετρημένο
        // στο αυτοκίνητο 18/09, τέσσερις φορές στη σειρά: 22,699→22,752 ·
        // 22,981→23,000 · 23,997→24,015 · 24,833→24,841.
        quietSince = 0L
        if (hasFocus) return
        val am = getSystemService(AudioManager::class.java) ?: return
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val r = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(outAttrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = r
                am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Throwable) { false }
        hasFocus = ok
        note("ήχος", "${Voice.route} · $outRate Hz · εστίαση " +
            if (ok) "εγκρίθηκε" else "ΑΠΟΡΡΙΦΘΗΚΕ")
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        val am = getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? AudioFocusRequest)
                    ?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Throwable) { }
    }

    private fun openTrack() {
        try { track?.release() } catch (e: Throwable) { }
        val min = AudioTrack.getMinBufferSize(
            outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(outAttrs)
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
                // Ο δίαυλος που ΣΗΚΩΝΕΙ το Android Auto για να στείλει τον ήχο
                // του κινητού στο αυτοκίνητο. Μετρημένο στον εξομοιωτή
                // 12/09/2026: με ενεργή προβολή, ο ήχος μας βγαίνει εδώ και όχι
                // στο ηχείο του κινητού — δηλαδή το Android Auto τον παραλαμβάνει
                // κανονικά. Ήταν το ένα από τα δύο άγνωστα που κουβαλούσαμε.
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "προβολή στο αυτοκίνητο"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "ακουστικά"
                else -> "τύπος ${d.type}"
            }
            Voice.route = name
            note("ήχος", "$name · $outRate Hz · εστίαση " +
                (if (hasFocus) "εγκρίθηκε" else "δεν ζητήθηκε ακόμη"))
        }, "aias-route").start()
    }

    private fun enqueue(b64: String) {
        // Η ΕΣΤΙΑΣΗ ΖΗΤΙΕΤΑΙ ΕΔΩ, ΜΟΛΙΣ ΦΤΑΣΕΙ ΤΟ ΠΑΚΕΤΟ.
        //
        // Ήταν στο νήμα αναπαραγωγής, ένα βήμα πριν το `write`: δηλαδή μετά
        // την αποκωδικοποίηση, μετά την ουρά και μετά την παράδοση σε άλλο
        // νήμα, με όλα αυτά στον δρόμο της πρώτης συλλαβής. Η
        // `requestAudioFocus` είναι κλήση προς άλλη διεργασία και δεν έχει
        // καμία δουλειά εκεί. Με το [FOCUS_HOLD_NS] επιστρέφει συνήθως
        // αμέσως, γιατί την κρατάμε ήδη από την προηγούμενη ατάκα.
        requestFocus()
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

    /**
     * Ξαναδένει τη χρονογραμμή πάνω στην τρέχουσα θέση της κεφαλής.
     *
     * Καλείται πάντα μέσα από `synchronized(env)`. Η διαφορά από την
     * [resetTimeline] είναι ότι εκεί μηδενίζονται **και τα δύο** —σωστό μόνο
     * όταν η ίδια η κεφαλή μηδενίζεται, δηλαδή μετά από `flush()`— ενώ εδώ η
     * κεφαλή συνεχίζει και ο μετρητής μας πάει να τη βρει.
     */
    private fun rebase(head: Long) {
        java.util.Arrays.fill(env, 0f)
        envWrite = (head / envHop).toInt()
        envAcc = 0.0
        envCount = 0
        written = head
        lastHeadMoveAt = 0L
        prevPending = 0L
    }

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
                Rec.pushAgent(pcm)
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

                // ΑΝΙΧΝΕΥΣΗ ΚΟΛΛΗΜΕΝΗΣ ΚΕΦΑΛΗΣ.
                //
                // Η κατάσταση «μιλάει» βγαίνει από το `γραμμένα − κεφαλή`. Αν η
                // κεφαλή σταματήσει να προχωράει ενώ υπάρχει υπόλοιπο — χαμένη
                // εστίαση, παγωμένο AudioTrack — η διαφορά μένει θετική **για
                // πάντα** και ο ΑΙΑΣ θεωρεί ότι μιλάει αιώνια. Με κλειστό
                // μικρόφωνο όσο μιλάει, αυτό σημαίνει ότι δεν ξανακούει ποτέ.
                //
                // Έτσι ακριβώς χάθηκαν ενενήντα πέντε δευτερόλεπτα στο S24:
                // από τα 171 δευτερόλεπτα της κλήσης, μόνο 36 έφτασαν στην
                // απομαγνητοφώνηση. Ο χρήστης μιλούσε σε τοίχο.
                if (head != lastHead) { lastHead = head; lastHeadMoveAt = now }

                var e = 0f
                var speaking = false
                synchronized(env) {
                    // Το υπόλοιπο στον απομονωτή: όσο είναι θετικό, ακούγεται.
                    val pending = written - head

                    // ΤΟ ΡΟΛΟΪ ΤΟΥ ΠΑΓΩΜΑΤΟΣ ΞΕΚΙΝΑΕΙ ΟΤΑΝ ΕΜΦΑΝΙΣΤΕΙ ΟΥΡΑ.
                    //
                    // Εδώ ήταν το σφάλμα που σκότωνε τις τελείες. Ανάμεσα σε
                    // δύο ατάκες η κεφαλή μένει ακίνητη — φυσιολογικά, δεν
                    // υπάρχει ήχος να παίξει. Μετά από ενάμισι δευτερόλεπτο
                    // ο ανιχνευτής θεωρούσε ότι πάγωσε. Και μόλις ο ΑΙΑΣ
                    // ξανάπαιρνε τον λόγο, το πρώτο κομμάτι έμπαινε στην ουρά
                    // ενώ η κεφαλή δεν είχε προλάβει ακόμη να κουνηθεί — άρα
                    // ο ανιχνευτής χτυπούσε **στην αρχή κάθε ατάκας**, ακριβώς
                    // τότε που δεν έπρεπε.
                    if (pending > 0 && prevPending <= 0) lastHeadMoveAt = now
                    prevPending = pending

                    val stalled = lastHeadMoveAt != 0L &&
                        now - lastHeadMoveAt > 1_500_000_000L
                    speaking = pending > envHop / 2 && !stalled

                    if (stalled && pending > 0) {
                        // ΚΑΙ ΟΤΑΝ ΠΑΓΩΣΕΙ ΣΤ' ΑΛΗΘΕΙΑ: ΞΑΝΑΔΕΝΟΥΜΕ, ΔΕΝ ΜΗΔΕΝΙΖΟΥΜΕ.
                        //
                        // Το παλιό `written = 0` άφηνε την κεφαλή να τρέχει και
                        // τον μετρητή στο μηδέν. Από εκείνη τη στιγμή το
                        // `pending` ήταν αρνητικό **για πάντα** — «δεν μιλάει
                        // ποτέ» — και ο δείκτης της περιβάλλουσας έπεφτε έξω
                        // από κάθε έγκυρο παράθυρο, δηλαδή στάθμη μηδέν για
                        // όλη την υπόλοιπη συνομιλία. Η βλάβη ήταν μόνιμη και
                        // καθάριζε μόνο με διακοπή, που κάνει `flush`: γι' αυτό
                        // «μία δούλευε και μία δεν δούλευε».
                        playQueue.clear()
                        rebase(head)
                        freezes++
                        note("φωνή", "η ροή ήχου πάγωσε — ξαναδέθηκε")
                    }

                    val idx = (head / envHop).toInt()
                    if (idx in 0 until envWrite && envWrite - idx <= ENV_SIZE) {
                        e = env[idx % ENV_SIZE]
                    } else if (pending > 0) {
                        // Η κεφαλή και η χρονογραμμή διαφώνησαν — λ.χ. επειδή
                        // το σύστημα ξαναδρομολόγησε τον ήχο και μηδένισε την
                        // κεφαλή από κάτω μας. Ως τώρα αυτό έβγαζε σιωπηλά
                        // μηδενική στάθμη· τώρα ξαναδένει, δηλαδή μια αναλαμπή
                        // 20 ms αντί για βλάβη που κρατάει ως το τέλος.
                        rebase(head)
                        desyncs++
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
                    quietSince = 0L
                } else {
                    // Σβήσιμο με τον χρόνο: 120 ms σταθερά, ίδιο σε κάθε ρυθμό.
                    Voice.level *= exp(-dt / 0.12).toFloat()
                    if (Voice.level < 0.004f) Voice.level = 0f
                    if (Voice.mode == "speak") Voice.mode = "listen"

                    // Η εστίαση κρατιέται ενάμισι δευτερόλεπτο μετά τη σιωπή.
                    //
                    // Με άμεση παράδοση, ο εξομοιωτής έγραφε «Audio stream
                    // close, buffered = 1024»: έκλεινε το κανάλι με δείγματα
                    // ακόμη μέσα, δηλαδή έτρωγε την ουρά της τελευταίας λέξης,
                    // και το άνοιγε πάλι στην επόμενη πρόταση. Ο λόγος μιλάει
                    // σε ριπές με κενά — δεν έχει νόημα να γυρίζει το κανάλι
                    // μαζί τους.
                    if (quietSince == 0L) quietSince = now
                    else if (now - quietSince > FOCUS_HOLD_NS) abandonFocus()
                }
                Voice.note(Voice.level)

                // Μία φορά το δευτερόλεπτο, το εύρος στα διαγνωστικά.
                //
                // Γραφόταν από τη [VizActivity], που τρέχει μόνο όταν κοιτάς
                // τις τελείες στο κινητό — δηλαδή **ποτέ στο αυτοκίνητο**.
                // Εκεί ζωγραφίζει ο [SurfaceRenderer] και η γραμμή δεν
                // ενημερωνόταν καθόλου: γυρνώντας από τη διαδρομή θα βλέπαμε
                // τιμές από την τελευταία δοκιμή στο σπίτι και θα τις
                // περνούσαμε για μετρήσεις του αυτοκινήτου. Ανήκει εδώ, στην
                // υπηρεσία που παράγει τους αριθμούς, όχι σε μια οθόνη.
                if (System.nanoTime() - diagMark > 1_000_000_000L) {
                    diagMark = System.nanoTime()
                    note("στάθμη", "%.2f – %.2f · %s · μικρ %.4f · καδ %s · υψ %s · ξαναδ %d+%d".format(
                        Voice.lo, Voice.hi, Voice.mode, Voice.micHi,
                        Voice.histLine(), Voice.extLine(), freezes, desyncs))
                    note("μικρόφωνο",
                        "διαβ %d/απέτ %d · εστ %d/απέτ %d · κορ %.4f".format(
                            reads, readFails, sent, sendFails, Voice.micHi))
                    note("εγγραφή", "%s · %s".format(
                        Rec.path?.substringAfterLast('/') ?: "—", Rec.elapsed()))
                    Voice.rollWindow()
                }
            }
        }, "aias-level").start()
    }
}
