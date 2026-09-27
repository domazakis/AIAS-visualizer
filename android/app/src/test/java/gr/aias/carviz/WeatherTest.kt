package gr.aias.carviz

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

/**
 * Η σύνοψη του [Weather] πάνω σε πραγματική απάντηση της MET για την Αθήνα
 * (27/09/2026, 14:00Z). Ό,τι βγαίνει εδώ είναι ακριβώς ό,τι θα διάβαζε ο agent.
 */
class WeatherTest {

    companion object {
        @BeforeClass @JvmStatic fun athens() {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
        }

        /** 27/09/2026 14:30Z — μισή ώρα μετά το πρώτο βήμα της πρόγνωσης. */
        const val NOW = 1_790_519_400_000L
    }

    private fun series() = JSONObject(javaClass.getResource("/met-athens.json")!!.readText())
        .getJSONObject("properties").getJSONArray("timeseries")

    @Test fun summary() {
        val s = Weather.summarize(series(), "εδώ", NOW)
        println("${s.length} χαρακτήρες\n$s")
        val o = JSONObject(s)
        val now = o.getJSONObject("now")
        assertEquals(20, now.getInt("temp"))           // 19.9
        assertEquals("ψιλή βροχή", now.getString("sky"))
        assertEquals("βοριάς", now.getString("wind_from"))  // 356°
        assertEquals(4, now.getInt("wind_bf"))           // 5.6 m/s
        assertEquals(4, o.getJSONArray("next_hours").length())
        assertTrue(o.has("tomorrow"))
        assertEquals(3, o.getJSONArray("later").length())
        assertTrue("πολύ μεγάλη για το ιστορικό: ${s.length}", s.length < 900)
    }

    @Test fun skies() {
        assertEquals("καθαρός ουρανός", Weather.sky("clearsky_night"))
        assertEquals("μπόρες με καταιγίδα", Weather.sky("rainshowersandthunder_day"))
        // Τα δύο ορθογραφικά λάθη της MET.
        assertEquals("λίγο χιονόνερο με καταιγίδα", Weather.sky("lightssleetshowersandthunder_day"))
        assertEquals("λίγο χιόνι με καταιγίδα", Weather.sky("lightssnowshowersandthunder_night"))
        assertEquals("άγνωστο", Weather.sky(null))
    }

    @Test fun wind() {
        assertEquals(0, Weather.beaufort(0.1))
        assertEquals(6, Weather.beaufort(12.0))
        assertEquals(12, Weather.beaufort(40.0))
        assertEquals("νοτιάς", Weather.compass(180.0))
        assertEquals("μαΐστρος", Weather.compass(320.0))
        assertEquals("βοριάς", Weather.compass(-10.0))
        assertEquals(null, Weather.compass(Double.NaN))
    }
}
