package gr.aias.carviz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Η οθόνη στο κινητό. Δείχνει τα διαγνωστικά που άφησε ο renderer όσο ήταν
 * συνδεδεμένο το αυτοκίνητο, και από κάτω τις οδηγίες.
 *
 * Υπάρχει ακριβώς γι' αυτό: στο αυτοκίνητο το καλώδιο του Android Auto
 * πιάνει τη θύρα του κινητού, οπότε δεν υπάρχει adb και δεν διαβάζεται
 * logcat. Γυρνώντας, ανοίγεις την εφαρμογή και οι αριθμοί είναι εδώ.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var voice: Button
    private var preview: android.widget.LinearLayout? = null

    /**
     * Το μικρόφωνο ζητείται εδώ και όχι στην υπηρεσία: μια υπηρεσία δεν μπορεί
     * να δείξει διάλογο αδείας, και χωρίς αυτήν το AudioRecord ανοίγει αλλά
     * επιστρέφει σιωπή — που είναι το χειρότερο είδος αποτυχίας, γιατί μοιάζει
     * με «δεν με ακούει».
     */
    private fun toggleVoice() {
        if (Voice.active) {
            stopService(Intent(this, VoiceService::class.java))
            Voice.reset()
            Voice.status = "ανενεργή"
            refresh()
            return
        }
        // ΤΟ ΜΙΚΡΟΦΩΝΟ ΕΙΝΑΙ ΥΠΟΧΡΕΩΤΙΚΟ, Η ΤΟΠΟΘΕΣΙΑ ΟΧΙ.
        //
        // Ζητιούνται μαζί για να μη δει ο χρήστης δύο παράθυρα, αλλά μόνο το
        // πρώτο σταματάει την εκκίνηση. Αν αρνηθεί την τοποθεσία, ο ΑΙΑΣ
        // μιλάει κανονικά και απαντά «δεν ξέρω πού είμαστε» όταν ρωτηθεί.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 2)
        }
        startService(Intent(this, VoiceService::class.java))
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, VoiceService::class.java))
        }
        // Η τοποθεσία μπορεί να εγκριθεί αφού έχει ήδη ξεκινήσει η φωνή.
        if (permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            Where.start(applicationContext)
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply {
            setPadding(56, 72, 56, 56)
            textSize = 16f
        }
        // Οι προεπισκοπήσεις της μέτρησης μπαίνουν από κάτω· στην κανονική
        // χρήση μένει άδειο και δεν φαίνεται.
        val pv = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 0, 56, 56)
        }
        preview = pv
        voice = Button(this).apply {
            // Η ετικέτα μπαίνει και εδώ, όχι μόνο στη [refresh]. Το κουμπί
            // βρέθηκε **κενό** στο κινητό: όποιο μονοπάτι της [onResume]
            // επιστρέφει νωρίς δεν περνάει από τη refresh, και το κουμπί έμενε
            // χωρίς κείμενο. Ένα κουμπί χωρίς όνομα δεν είναι κουμπί.
            text = "Ξεκίνα τη φωνή"
            setOnClickListener { toggleVoice() }
        }
        val viz = Button(this).apply {
            text = "Δες τις τελείες"
            setOnClickListener { startActivity(Intent(this@MainActivity, VizActivity::class.java)) }
        }
        // Κάνει τον ΑΙΑΝΤΑ να απαντήσει χωρίς να του μιλήσει κανείς: ο μόνος
        // τρόπος να ελεγχθεί ο συγχρονισμός εικόνας-ήχου χωρίς δεύτερο άτομο
        // και χωρίς αυτοκίνητο.
        val probe = Button(this).apply {
            text = "Βάλ' τον να μιλήσει"
            setOnClickListener {
                val ok = VoiceService.δοκιμή()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "στάλθηκε — άνοιξε τις τελείες" else "δεν υπάρχει σύνδεση",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ΠΟΥ ΑΚΟΥΓΕΤΑΙ Ο ΑΙΑΣ. Διακόπτης και όχι σταθερά, επειδή μόνο μια
        // διαδρομή απαντά ποια από τις δύο διαδρομές είναι καλύτερη, και
        // επειδή η απάντηση μπορεί να αλλάζει από αυτοκίνητο σε αυτοκίνητο.
        // Αλλάζει πριν ξεκινήσει η υπηρεσία· αν τρέχει, εφαρμόζεται στο
        // επόμενο άνοιγμα.
        val route = Button(this).apply {
            text = Route.label(this@MainActivity)
            setOnClickListener {
                Route.set(this@MainActivity, !Route.inCar(this@MainActivity))
                text = Route.label(this@MainActivity)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "ισχύει από το επόμενο ξεκίνημα της φωνής",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            // Τα κουμπιά ΠΑΝΩ από το κείμενο. Ήταν από κάτω, και με τρεις
            // οθόνες οδηγιών μπροστά τους δεν τα έβρισκε κανείς — ούτε καν το
            // uiautomator, που τα ανέφερε ως ανύπαρκτα επειδή ήταν εκτός
            // ορατής περιοχής.
            val lp = {
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(56, 24, 56, 24) }
            }
            addView(viz, lp())
            addView(voice, lp())
            addView(probe, lp())
            addView(route, lp())
            addView(tv)
            addView(pv)
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /** Ξαναδιαβάζονται σε κάθε εμφάνιση, ώστε να αρκεί ένα βγες-μπες. */
    override fun onResume() {
        super.onResume()
        // Οι ειδικές λειτουργίες ΚΑΤΑΝΑΛΩΝΟΝΤΑΙ.
        //
        // Η Activity κρατάει για πάντα το Intent που την ξεκίνησε, και η
        // onResume τρέχει σε κάθε εμφάνιση. Χωρίς το removeExtra, ένα και μόνο
        // `--ez store true` καθήλωνε την εφαρμογή: κάθε άνοιγμα ξαναζωγράφιζε
        // τα γραφικά και έδειχνε λίστα αρχείων αντί για διαγνωστικά, για
        // πάντα. Έτσι βρέθηκε στο κινητό, με το μεσαίο κουμπί κενό.
        if (intent?.getBooleanExtra("store", false) == true) {
            intent.removeExtra("store")
            tv.text = "Γραφικά καταστήματος…"
            Thread {
                val out = Store.writeAll(this)
                runOnUiThread { tv.text = out }
            }.start()
            return
        }
        if (intent?.getBooleanExtra("bench", false) == true) {
            intent.removeExtra("bench")
            tv.text = "Μέτρηση σε εξέλιξη…"
            // Εκτός του νήματος διεπαφής: κρατάει μερικά δευτερόλεπτα.
            Thread {
                val out = Bench.runAll()
                val full = Bench.preview(false)
                val split = Bench.preview(true)
                runOnUiThread {
                    tv.text = out
                    preview?.let { p ->
                        p.removeAllViews()
                        for (b in listOf(full, split)) {
                            p.addView(android.widget.ImageView(this).apply {
                                setImageBitmap(b)
                                adjustViewBounds = true
                                setPadding(0, 24, 0, 0)
                            })
                        }
                    }
                }
            }.start()
            return
        }
        refresh()
    }

    private fun refresh() {
        voice.text = if (Voice.active) "Σταμάτα τη φωνή" else "Ξεκίνα τη φωνή"
        val v = buildString {
            append("\n\nΦΩΝΗ: ").append(Voice.status)
            if (Voice.active) {
                append("\n  κατάσταση: ").append(Voice.mode)
                if (Voice.lastUser.isNotEmpty()) append("\n  εσύ: ").append(Voice.lastUser)
                if (Voice.lastAgent.isNotEmpty()) append("\n  ΑΙΑΣ: ").append(Voice.lastAgent)
            }
        }
        tv.text = report() + v + "\n\n" + INSTRUCTIONS
    }

    private fun report(): String {
        val rows = Diag.read(this)
        if (rows.isEmpty()) {
            return "ΑΙΑΣ — δοκιμή Android Auto\n\n" +
                "Δεν έχει καταγραφεί τίποτα ακόμη. Αυτό σημαίνει ότι η υπηρεσία " +
                "του αυτοκινήτου δεν ξεκίνησε ούτε μία φορά — η εφαρμογή δεν " +
                "επιλέχθηκε ποτέ στην οθόνη του αυτοκινήτου, ή δεν εμφανίστηκε " +
                "καθόλου στη λίστα."
        }
        // Χωρίς στοίχιση με κενά: η γραμματοσειρά είναι αναλογική και τα
        // κενά δεν ευθυγραμμίζουν τίποτα.
        val body = rows.joinToString("\n\n") { (k, v) -> "$k:\n$v" }
        return "ΑΙΑΣ — τι κατέγραψε η τελευταία σύνδεση\n\n$body"
    }

    private companion object {
        // Χωρίς σκληρές αλλαγές γραμμής μέσα στις παραγράφους — τις
        // αναδιπλώνει το TextView, και η διπλή αναδίπλωση διαβάζεται άσχημα.
        val INSTRUCTIONS = listOf(
            "─────────────────────────────",
            "Αυτή η εκδοχή δεν δείχνει τη σφαίρα. Ζωγραφίζει μόνο μια γραμμή που " +
                "σαρώνει, για να απαντήσει σε μία ερώτηση: παίρνει η εφαρμογή " +
                "επιφάνεια στην οθόνη του αυτοκινήτου;",
            "Πριν συνδέσεις:",
            "1. Άνοιξε την εφαρμογή Android Auto στο κινητό.\n" +
                "2. Πάτα δέκα φορές τον αριθμό έκδοσης.\n" +
                "3. Τρεις τελείες → «Ρυθμίσεις προγραμματιστή».\n" +
                "4. Ενεργοποίησε το «Άγνωστες πηγές».\n" +
                "5. Σύνδεσε στο αυτοκίνητο.",
            "Στην οθόνη του αυτοκινήτου, ψάξε τον ΑΙΑΝΤΑ ανάμεσα στις εφαρμογές " +
                "πλοήγησης.",
            "Γυρνώντας, άνοιξε ξανά αυτή την οθόνη: οι αριθμοί επάνω είναι ό,τι " +
                "χρειάζεται για τη ρύθμιση του πραγματικού renderer — ανάλυση, " +
                "κεντράρισμα και budget.",
            "Η ορατή περιοχή έχει σημασία: το head unit μπορεί να σκεπάζει μέρος " +
                "της επιφάνειας με τα δικά του στοιχεία, και η σφαίρα πρέπει να " +
                "κεντραριστεί εκεί μέσα."
        ).joinToString("\n\n")
    }
}
