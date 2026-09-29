package gr.aias.carviz

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Ο φάκελος του ΑΙΑΝΤΑ στο Dropbox, μέσα στο `Εφαρμογές/`.
 *
 * ΓΙΑΤΙ DROPBOX. Η μνήμη πρέπει να είναι ίδια σε όποιο κινητό κουμπώσει ο
 * Γιάννης, και να τη βλέπει ο Claude στο chat, που φιλτράρει τις σημειώσεις
 * — και ο Claude έχει πρόσβαση στο Dropbox. Η εφαρμογή στο Dropbox έχει
 * δικαίωμα **μόνο στον δικό της φάκελο**, όχι σε όλο τον λογαριασμό.
 *
 * ΧΩΡΙΣ SDK, ΧΩΡΙΣ ΑΝΑΚΑΤΕΥΘΥΝΣΗ. Η σύνδεση γίνεται μία φορά ανά κινητό: ο
 * browser ανοίγει τη σελίδα του Dropbox, ο Γιάννης πατά «Allow», το Dropbox
 * δείχνει έναν κωδικό, και αυτός επικολλάται στην εφαρμογή. Είναι η ροή
 * PKCE χωρίς `redirect_uri` — η ίδια που χρησιμοποιεί το επίσημο SDK για
 * εφαρμογές χωρίς διεύθυνση επιστροφής. Κανένα μυστικό δεν ζει στην εφαρμογή:
 * το app key δεν είναι μυστικό, και ο κωδικός ανανέωσης μένει στον ιδιωτικό
 * χώρο της εφαρμογής, που δεν έχει αντίγραφο ασφαλείας.
 *
 * Όλες οι κλήσεις είναι **αργές και μπλοκάρουν**: μόνο από νήμα παρασκηνίου.
 */
object Cloud {

    private const val TAG = "AiasCloud"
    private const val PREFS = "cloud"

    private val KEY: String = BuildConfig.DROPBOX_KEY

    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Για τα διαγνωστικά και την οθόνη μνήμης. Χωρίς περιεχόμενο. */
    @Volatile var status: String = "—"
        internal set

    fun configured() = KEY.isNotBlank()

    fun linked(ctx: Context) = configured() && prefs(ctx).getString("refresh", null) != null

    // ------------------------------------------------------------- σύνδεση

    /** Η σελίδα του Dropbox όπου ο Γιάννης πατά «Allow» και παίρνει τον κωδικό. */
    fun authUrl(ctx: Context): String {
        val raw = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64(raw)
        prefs(ctx).edit().putString("verifier", verifier).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://www.dropbox.com/oauth2/authorize?client_id=$KEY&response_type=code" +
            "&token_access_type=offline&code_challenge=$challenge&code_challenge_method=S256"
    }

    /** Ανταλλάσσει τον κωδικό με κλειδί ανανέωσης. `null` σημαίνει επιτυχία. */
    fun finish(ctx: Context, code: String): String? {
        val verifier = prefs(ctx).getString("verifier", null)
            ?: return "Ξεκίνα πάλι από το «Άνοιξε το Dropbox»."
        return try {
            val o = token(FormBody.Builder()
                .add("code", code.trim())
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .add("client_id", KEY)
                .build()) ?: return "Το Dropbox δεν δέχτηκε τον κωδικό. Ίσως έληξε — ξαναδοκίμασε."
            val refresh = o.optString("refresh_token")
            if (refresh.isEmpty()) return "Το Dropbox δεν έδωσε κλειδί ανανέωσης."
            store(ctx, o)
            prefs(ctx).edit().putString("refresh", refresh).remove("verifier").apply()
            status = "συνδέθηκε"
            null
        } catch (e: Throwable) {
            Log.w(TAG, "σύνδεση", e)
            "Δεν έγινε σύνδεση: ${e.javaClass.simpleName}"
        }
    }

    fun unlink(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        status = "αποσυνδέθηκε"
    }

    // -------------------------------------------------------------- αρχεία

    sealed class Got {
        class Ok(val text: String, val rev: String) : Got()
        object Missing : Got()
        class Fail(val why: String) : Got()
    }

    sealed class Put {
        class Ok(val rev: String) : Put()
        /** Κάποιος άλλος —άλλο κινητό, ο Claude— άλλαξε το αρχείο στο μεταξύ. */
        object Conflict : Put()
        class Fail(val why: String) : Put()
    }

    fun get(ctx: Context, path: String): Got {
        val t = access(ctx) ?: return Got.Fail(status)
        return try {
            http.newCall(Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .header("Authorization", "Bearer $t")
                .header("Dropbox-API-Arg", JSONObject().put("path", path).toString())
                .post(ByteArray(0).toRequestBody(null))
                .build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> {
                        val rev = JSONObject(r.header("Dropbox-API-Result") ?: "{}").optString("rev")
                        Got.Ok(body, rev)
                    }
                    r.code == 409 && body.contains("not_found") -> Got.Missing
                    else -> Got.Fail("HTTP ${r.code}")
                }
            }
        } catch (e: Throwable) {
            Got.Fail(e.javaClass.simpleName)
        }
    }

    /**
     * Γράφει ολόκληρο το αρχείο. Με `rev`: μόνο αν δεν άλλαξε από τότε που το
     * διαβάσαμε. Χωρίς `rev`: μόνο αν δεν υπάρχει. Ποτέ τυφλή αντικατάσταση —
     * αλλιώς μια σημείωση από το κινητό θα έσβηνε ό,τι ενέκρινε ο Claude.
     */
    fun put(ctx: Context, path: String, text: String, rev: String?): Put {
        val t = access(ctx) ?: return Put.Fail(status)
        val mode = if (rev == null) JSONObject().put(".tag", "add")
                   else JSONObject().put(".tag", "update").put("update", rev)
        val arg = JSONObject().put("path", path).put("mode", mode)
            .put("autorename", false).put("mute", true)
        return try {
            http.newCall(Request.Builder()
                .url("https://content.dropboxapi.com/2/files/upload")
                .header("Authorization", "Bearer $t")
                .header("Dropbox-API-Arg", arg.toString())
                .post(text.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
                .build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> Put.Ok(JSONObject(body).optString("rev"))
                    r.code == 409 && body.contains("conflict") -> Put.Conflict
                    else -> Put.Fail("HTTP ${r.code}")
                }
            }
        } catch (e: Throwable) {
            Put.Fail(e.javaClass.simpleName)
        }
    }

    /** Τα ονόματα των αρχείων στη ρίζα του φακέλου της εφαρμογής. `null` σε σφάλμα. */
    fun list(ctx: Context): List<String>? {
        val out = ArrayList<String>()
        var o = rpc(ctx, "files/list_folder", JSONObject().put("path", "")) ?: return null
        while (true) {
            val e = o.optJSONArray("entries")
            if (e != null) for (i in 0 until e.length()) {
                val x = e.getJSONObject(i)
                if (x.optString(".tag") == "file") out += x.optString("name")
            }
            if (!o.optBoolean("has_more")) return out
            o = rpc(ctx, "files/list_folder/continue",
                JSONObject().put("cursor", o.optString("cursor"))) ?: return null
        }
    }

    /** Σβήνει ένα αρχείο. Αν δεν υπάρχει ήδη, είναι κι αυτό επιτυχία. */
    fun delete(ctx: Context, path: String): Boolean =
        rpc(ctx, "files/delete_v2", JSONObject().put("path", path), notFoundOk = true) != null

    private fun rpc(ctx: Context, endpoint: String, arg: JSONObject, notFoundOk: Boolean = false): JSONObject? {
        val t = access(ctx) ?: return null
        return try {
            http.newCall(Request.Builder()
                .url("https://api.dropboxapi.com/2/$endpoint")
                .header("Authorization", "Bearer $t")
                .post(arg.toString().toRequestBody("application/json".toMediaType()))
                .build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.isSuccessful -> JSONObject(body)
                    notFoundOk && r.code == 409 && body.contains("not_found") -> JSONObject()
                    else -> { status = "σφάλμα $endpoint: HTTP ${r.code}"; null }
                }
            }
        } catch (e: Throwable) {
            status = "σφάλμα $endpoint: ${e.javaClass.simpleName}"
            null
        }
    }

    // ------------------------------------------------------------ κλειδιά

    /** Ένα έγκυρο κλειδί πρόσβασης, ανανεωμένο αν χρειάζεται. */
    private fun access(ctx: Context): String? {
        if (!configured()) { status = "δεν έχει ρυθμιστεί"; return null }
        val p = prefs(ctx)
        val refresh = p.getString("refresh", null) ?: run { status = "δεν συνδέθηκε"; return null }
        val cur = p.getString("access", null)
        if (cur != null && System.currentTimeMillis() < p.getLong("expires", 0L) - 60_000L) return cur
        return try {
            val o = token(FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", KEY)
                .build())
            if (o == null) {
                // Ο Γιάννης αφαίρεσε την πρόσβαση από τις ρυθμίσεις του Dropbox.
                status = "η πρόσβαση ανακλήθηκε — ξανασύνδεση"
                unlink(ctx)
                null
            } else {
                store(ctx, o)
                o.optString("access_token")
            }
        } catch (e: Throwable) {
            status = "χωρίς δίκτυο (${e.javaClass.simpleName})"
            null
        }
    }

    /** `null` αν το Dropbox αρνήθηκε (400/401)· εξαίρεση αν δεν απάντησε. */
    private fun token(form: FormBody): JSONObject? =
        http.newCall(Request.Builder()
            .url("https://api.dropboxapi.com/oauth2/token")
            .post(form)
            .build()).execute().use { r ->
            if (r.code == 400 || r.code == 401) return null
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            JSONObject(r.body!!.string())
        }

    private fun store(ctx: Context, o: JSONObject) {
        prefs(ctx).edit()
            .putString("access", o.optString("access_token"))
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600L) * 1000L)
            .apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun b64(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
