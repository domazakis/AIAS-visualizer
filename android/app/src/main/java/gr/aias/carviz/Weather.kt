package gr.aias.carviz

import android.content.Context
import android.location.Geocoder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Ο καιρός του ΑΙΑΝΤΑ, από το εργαλείο `kairos`.
 *
 * ΓΙΑΤΙ MET NORWAY.
 *
 * Η απαίτηση ήταν υπηρεσία που **επιτρέπει εμπορική χρήση**: ο ΑΙΑΣ ακούγεται
 * σε επεισόδια. Το Open-Meteo, το προφανές, είναι δωρεάν μόνο για μη εμπορική
 * χρήση. Η νορβηγική μετεωρολογική υπηρεσία (api.met.no) δίνει τα δεδομένα
 * της με άδεια CC BY 4.0, χωρίς κλειδί, για όλο τον κόσμο. Ζητάει τρία
 * πράγματα, και τα τρία τηρούνται εδώ:
 *
 * - User-Agent που λέει ποιοι είμαστε και πού θα μας βρουν ([UA]).
 * - Όχι νέα ερώτηση πριν από το `Expires` της προηγούμενης απάντησης ([cache]).
 * - Αναφορά της πηγής όπου δημοσιεύονται τα δεδομένα — αυτό είναι δουλειά των
 *   τίτλων του επεισοδίου, όχι του ΑΙΑΝΤΑ σε κάθε πρόταση.
 *
 * ΓΙΑΤΙ ΑΣΥΓΧΡΟΝΑ, ΕΝΩ ΤΟ GPS ΟΧΙ.
 *
 * Το [Where] απαντά αμέσως γιατί έχει ήδη βρει τα πάντα στο παρασκήνιο. Εδώ
 * αυτό θα σήμαινε να στέλνουμε τη θέση στη MET σε κάθε διαδρομή, ακόμη κι αν
 * ο καιρός δεν ρωτηθεί ποτέ. Προτιμήθηκε: η θέση φεύγει **μόνο όταν ρωτήσει**.
 * Άρα η απάντηση χτυπάει δίκτυο, και δεν γίνεται στο νήμα του WebSocket —
 * εκείνο διαβάζει τον ήχο του ΑΙΑΝΤΑ. Τρέχει σε δικό της νήμα και επιστρέφει
 * με callback, **πάντα μία φορά, πάντα μέσα σε [DEADLINE_MS]**.
 *
 * ΚΑΙ ΟΤΑΝ ΑΠΟΤΥΧΕΙ, ΤΟ ΛΕΕΙ. Όπως το [Where]: ολόκληρη πρόταση, ποτέ κενό.
 *
 * ΓΙΑΤΙ ΤΟΣΟ ΣΥΜΠΑΓΕΣ.
 *
 * Η απάντηση ενός εργαλείου μένει στο ιστορικό της συνομιλίας και ξαναστέλνεται
 * στο μοντέλο σε κάθε γύρο, όπως το `{{memory}}`. Μια πλήρης πρόγνωση της MET
 * είναι 40 KB· εδώ γίνεται ~500 χαρακτήρες: τώρα, οι επόμενες ώρες, σήμερα,
 * αύριο, και τρεις μέρες ακόμη.
 */
object Weather {

    private const val TAG = "AiasWeather"

    private const val URL = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    private const val UA = "AIAS/${BuildConfig.VERSION_NAME} github.com/domazakis/AIAS-visualizer"

    /** Ο agent περιμένει ως 8 δευτ. (ρύθμιση κονσόλας)· εμείς απαντάμε πάντα νωρίτερα. */
    private const val DEADLINE_MS = 6_000L

    /** Αν το `Expires` λείπει. */
    private const val DEFAULT_TTL_MS = 10 * 60_000L

    /** Αν αποτύχει η ανανέωση, μια πρόγνωση ως τριών ωρών είναι ακόμη χρήσιμη. */
    private const val STALE_OK_MS = 3 * 3_600_000L

    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Για τη γραμμή διαγνωστικών — χωρίς τόπο. */
    @Volatile var status: String = "δεν ρωτήθηκε"
        private set

    // ---------------------------------------------------------------- cache

    private class Forecast(
        val key: String,
        val series: JSONArray,
        val fetchedAt: Long,
        val expires: Long,
        val lastModified: String?,
    )

    /**
     * Μία θέση ανά κλειδί, και λίγες: μια κουβέντα ρωτάει για εδώ και ίσως για
     * έναν προορισμό. Μόνο στη μνήμη — τίποτα δεν γράφεται στον δίσκο.
     */
    private val cache = object : LinkedHashMap<String, Forecast>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Forecast>?) = size > 4
    }

    // ------------------------------------------------------------ η απάντηση

    /**
     * Ξεκινά την αναζήτηση και επιστρέφει αμέσως. Το [answer] καλείται
     * **ακριβώς μία φορά**, από άλλο νήμα.
     *
     * @param topos ό,τι είπε ο agent για τόπο· κενό σημαίνει «εδώ».
     */
    fun ask(ctx: Context, topos: String?, answer: (String) -> Unit) {
        val once = AtomicBoolean(false)
        fun reply(s: String) { if (once.compareAndSet(false, true)) answer(s) }

        val app = ctx.applicationContext
        val t0 = System.currentTimeMillis()
        Thread({
            val s = try {
                lookup(app, topos?.trim().orEmpty())
            } catch (e: Throwable) {
                Log.w(TAG, "αποτυχία", e)
                status = "σφάλμα ${e.javaClass.simpleName}"
                FAILED
            }
            if (once.get()) status = "άργησε ${System.currentTimeMillis() - t0} ms"
            reply(s)
        }, "aias-weather").start()

        // Ο φύλακας: ό,τι κι αν κρεμάσει —γεωκωδικοποίηση, δίκτυο— ο agent
        // παίρνει πρόταση πριν λήξει ο δικός του χρόνος.
        Thread({
            try { Thread.sleep(DEADLINE_MS) } catch (e: InterruptedException) { }
            if (!once.get()) status = "δεν πρόλαβε"
            reply(FAILED)
        }, "aias-weather-deadline").start()
    }

    private const val FAILED =
        "Η υπηρεσία καιρού δεν απάντησε, οπότε αυτή τη στιγμή δεν ξέρω τι καιρό κάνει."

    private fun lookup(ctx: Context, topos: String): String {
        val lat: Double
        val lon: Double
        val label: String

        if (topos.isEmpty()) {
            val l = Where.fix()
                ?: return "Δεν ξέρω πού είμαστε, οπότε δεν μπορώ να δω τον καιρό εδώ."
                    .also { status = "χωρίς στίγμα" }
            lat = l.latitude; lon = l.longitude; label = "εδώ"
        } else {
            if (!Geocoder.isPresent()) {
                status = "χωρίς γεωκωδικοποίηση"
                return "Δεν μπορώ να βρω μέρη με το όνομα αυτή τη στιγμή."
            }
            @Suppress("DEPRECATION")
            val a = Geocoder(ctx, Locale("el", "GR")).getFromLocationName(topos, 1)?.firstOrNull()
            if (a == null) {
                status = "άγνωστος τόπος"
                return "Δεν βρήκα μέρος με το όνομα «$topos»."
            }
            lat = a.latitude; lon = a.longitude
            label = listOfNotNull(a.locality ?: a.subAdminArea ?: a.featureName, a.adminArea)
                .distinct().joinToString(", ").ifEmpty { topos }
        }

        val f = forecast(lat, lon) ?: return FAILED
        val out = summarize(f.series, label, System.currentTimeMillis())
        status = "εντάξει · πρόγνωση πριν ${(System.currentTimeMillis() - f.fetchedAt) / 60_000}′"
        return out
    }

    /**
     * Δύο δεκαδικά: ~1 χλμ. Αρκεί για καιρό, κρατάει τη θέση θολή για τη MET,
     * και κάνει τα κλειδιά του cache να ταιριάζουν όσο κινούμαστε λίγο.
     */
    private fun forecast(lat: Double, lon: Double): Forecast? {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        val now = System.currentTimeMillis()
        val have = synchronized(cache) { cache[key] }
        if (have != null && now < have.expires) return have

        val req = Request.Builder()
            .url(String.format(Locale.US, "%s?lat=%.2f&lon=%.2f", URL, lat, lon))
            .header("User-Agent", UA)
            .apply { have?.lastModified?.let { header("If-Modified-Since", it) } }
            .build()

        return try {
            http.newCall(req).execute().use { r ->
                val expires = r.headers.getDate("Expires")?.time
                    ?.coerceIn(now + 60_000L, now + 2 * 3_600_000L)
                    ?: (now + DEFAULT_TTL_MS)
                when {
                    r.code == 304 && have != null -> {
                        val f = Forecast(key, have.series, now, expires, have.lastModified)
                        synchronized(cache) { cache[key] = f }
                        f
                    }
                    r.isSuccessful -> {
                        val series = JSONObject(r.body!!.string())
                            .getJSONObject("properties").getJSONArray("timeseries")
                        val f = Forecast(key, series, now, expires, r.header("Last-Modified"))
                        synchronized(cache) { cache[key] = f }
                        f
                    }
                    else -> {
                        Log.w(TAG, "HTTP ${r.code}")
                        status = "HTTP ${r.code}"
                        staleOrNull(have, now)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "δίκτυο", e)
            status = "δίκτυο: ${e.javaClass.simpleName}"
            staleOrNull(have, now)
        }
    }

    private fun staleOrNull(have: Forecast?, now: Long): Forecast? =
        if (have != null && now - have.fetchedAt < STALE_OK_MS) have
        else null

    // -------------------------------------------------------------- σύνοψη

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val DAYS = arrayOf("", "Κυριακή", "Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο")

    private class Step(
        val at: Long,
        val day: Int,        // ημέρα από σήμερα: 0, 1, 2…
        val hour: Int,       // τοπική ώρα
        val temp: Double,
        val windMs: Double,
        val windFrom: Double,
        val sky1: String?, val rain1: Double?,
        val sky6: String?, val rain6: Double?,
    )

    /**
     * Η συμπαγής απάντηση. Κλειδιά στα αγγλικά γιατί κοστίζουν λιγότερα tokens·
     * οι λέξεις που θα πει ο ΑΙΑΣ στα ελληνικά, για να μη μεταφράζει. Αριθμοί
     * σε ψηφία — το prompt του λέει να τους προφέρει ολογράφως.
     */
    internal fun summarize(series: JSONArray, label: String, now: Long): String {
        val tz = TimeZone.getDefault()
        val today = Calendar.getInstance(tz).apply { timeInMillis = now }
        val steps = ArrayList<Step>(series.length())
        for (i in 0 until series.length()) {
            val o = series.getJSONObject(i)
            val at = synchronized(ISO) { ISO.parse(o.getString("time"))!!.time }
            val d = o.getJSONObject("data")
            val inst = d.getJSONObject("instant").getJSONObject("details")
            val c = Calendar.getInstance(tz).apply { timeInMillis = at }
            steps += Step(
                at = at,
                day = daysBetween(today, c),
                hour = c.get(Calendar.HOUR_OF_DAY),
                temp = inst.optDouble("air_temperature"),
                windMs = inst.optDouble("wind_speed", 0.0),
                windFrom = inst.optDouble("wind_from_direction", Double.NaN),
                sky1 = d.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code"),
                rain1 = d.optJSONObject("next_1_hours")?.optJSONObject("details")?.optDouble("precipitation_amount"),
                sky6 = d.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code"),
                rain6 = d.optJSONObject("next_6_hours")?.optJSONObject("details")?.optDouble("precipitation_amount"),
            )
        }

        // «Τώρα» είναι το βήμα της τρέχουσας ώρας — η πρόγνωση μπορεί να
        // είναι λίγο παλιά, οπότε το πρώτο βήμα δεν είναι απαραίτητα το τώρα.
        val cur = steps.lastOrNull { it.at <= now } ?: steps.firstOrNull()
            ?: return "Η υπηρεσία καιρού έδωσε άδεια πρόγνωση."

        val out = JSONObject()
        out.put("place", label)
        out.put("now", JSONObject()
            .put("temp", cur.temp.roundToInt())
            .put("sky", sky(cur.sky1 ?: cur.sky6))
            .put("wind_bf", beaufort(cur.windMs))
            .apply { compass(cur.windFrom)?.let { put("wind_from", it) } }
            .apply { cur.rain1?.takeIf { it > 0 }?.let { put("rain_mm_1h", r1(it)) } })

        // Οι επόμενες οκτώ ώρες σε τέσσερα συνεχόμενα δίωρα, από την επόμενη
        // ώρα: αρκεί για «θα βρέξει;». Ο ουρανός του διώρου είναι της ώρας με
        // τη μεγαλύτερη βροχή — αν βρέξει έστω και για μία ώρα, αυτό λέγεται.
        val next = JSONArray()
        for (k in 0 until 4) {
            val from = cur.at + (1 + 2 * k) * 3_600_000L
            val win = steps.filter { it.at >= from && it.at < from + 2 * 3_600_000L && it.sky1 != null }
            if (win.isEmpty()) break
            val worst = win.maxByOrNull { it.rain1 ?: 0.0 }!!
            next.put(JSONObject()
                .put("hours", "%02d-%02d".format(win.first().hour, (win.first().hour + 2) % 24))
                .put("temp", win.first().temp.roundToInt())
                .put("sky", sky(worst.sky1))
                .apply { rainBetween(steps, from, from + 2 * 3_600_000L).takeIf { it > 0 }?.let { put("rain_mm", r1(it)) } })
        }
        if (next.length() > 0) out.put("next_hours", next)

        day(steps, 0, cur.at)?.let { out.put("rest_of_today", it) }
        day(steps, 1, 0L)?.let { out.put("tomorrow", it) }
        val later = JSONArray()
        for (n in 2..4) day(steps, n, 0L)?.let { later.put(it) }
        if (later.length() > 0) out.put("later", later)

        return out.toString()
    }

    /** Μία μέρα: μέγιστη, ελάχιστη, ουρανός πρωί και απόγευμα, βροχή συνολικά. */
    private fun day(steps: List<Step>, n: Int, from: Long): JSONObject? {
        val ss = steps.filter { it.day == n && it.at >= from }
        if (ss.size < 2) return null
        val o = JSONObject()
        if (n >= 2) o.put("day", DAYS[Calendar.getInstance().apply {
            timeInMillis = ss.first().at }.get(Calendar.DAY_OF_WEEK)])
        o.put("max", ss.maxOf { it.temp }.roundToInt())
        o.put("min", ss.minOf { it.temp }.roundToInt())
        skyNear(ss, 9)?.let { if (ss.any { it.hour <= 12 }) o.put("morning", it) }
        skyNear(ss, 15)?.let { if (ss.any { it.hour in 13..20 }) o.put("afternoon", it) }
        val rain = rainBetween(ss, ss.first().at, ss.last().at + 3_600_000L)
        if (rain > 0) o.put("rain_mm", r1(rain))
        // Ο αέρας μόνο για σήμερα και αύριο· πιο μακριά κοστίζει χωρίς να ρωτιέται.
        if (n <= 1) o.put("wind_bf_max", ss.maxOf { beaufort(it.windMs) })
        return o
    }

    private fun skyNear(ss: List<Step>, hour: Int): String? =
        ss.minByOrNull { kotlin.math.abs(it.hour - hour) }?.let { sky(it.sky1 ?: it.sky6) }

    /**
     * Βροχή στο διάστημα [from, to). Όπου υπάρχει ωριαία τιμή μετράει αυτή· στις
     * μακρινές μέρες η MET δίνει μόνο εξάωρα, και τότε μετράνε εκείνα.
     */
    private fun rainBetween(steps: List<Step>, from: Long, to: Long): Double {
        var sum = 0.0
        var coveredTo = from
        for (s in steps) {
            if (s.at < coveredTo || s.at >= to) continue
            when {
                s.rain1 != null -> { sum += s.rain1; coveredTo = s.at + 3_600_000L }
                s.rain6 != null -> { sum += s.rain6; coveredTo = s.at + 6 * 3_600_000L }
            }
        }
        return sum
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun noon(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return Math.round((noon(b) - noon(a)) / 86_400_000.0).toInt()
    }

    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0

    /** Η κλίμακα που λένε οι Έλληνες για τον αέρα. */
    internal fun beaufort(ms: Double): Int {
        val limits = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)
        return limits.indexOfFirst { ms < it }.let { if (it < 0) 12 else it }
    }

    /** Από πού φυσάει — η ελληνική ονομασία του ανέμου. */
    internal fun compass(deg: Double): String? {
        if (deg.isNaN()) return null
        val names = arrayOf("βοριάς", "γραίγος", "λεβάντες", "σορόκος",
                            "νοτιάς", "γαρμπής", "πουνέντες", "μαΐστρος")
        return names[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
    }

    /**
     * Οι κωδικοί της MET σε λέξεις. Η MET έχει δύο ορθογραφικά λάθη στους
     * δικούς της κωδικούς (`lightssleet…`, `lightssnow…`)· διορθώνονται πριν
     * από τη σύγκριση.
     */
    internal fun sky(code: String?): String {
        if (code.isNullOrEmpty()) return "άγνωστο"
        val base = code.substringBefore('_').replace("lightss", "lights")
        val thunder = base.endsWith("andthunder")
        val core = base.removeSuffix("andthunder")
        val word = when (core) {
            "clearsky" -> "καθαρός ουρανός"
            "fair" -> "κυρίως αίθριος"
            "partlycloudy" -> "λίγα σύννεφα"
            "cloudy" -> "συννεφιά"
            "fog" -> "ομίχλη"
            "lightrain" -> "ψιλή βροχή"
            "rain" -> "βροχή"
            "heavyrain" -> "δυνατή βροχή"
            "lightrainshowers" -> "λίγες ψιχάλες"
            "rainshowers" -> "μπόρες"
            "heavyrainshowers" -> "δυνατές μπόρες"
            "lightsleet", "lightsleetshowers" -> "λίγο χιονόνερο"
            "sleet", "sleetshowers" -> "χιονόνερο"
            "heavysleet", "heavysleetshowers" -> "πολύ χιονόνερο"
            "lightsnow", "lightsnowshowers" -> "λίγο χιόνι"
            "snow", "snowshowers" -> "χιόνι"
            "heavysnow", "heavysnowshowers" -> "πυκνό χιόνι"
            else -> core
        }
        return if (thunder) "$word με καταιγίδα" else word
    }
}
