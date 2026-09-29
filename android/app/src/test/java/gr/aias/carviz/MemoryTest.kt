package gr.aias.carviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ο κανόνας του ορίου (απόφαση 29/09/2026): από τα αρχεία δεν σβήνεται
 * τίποτα· στο `{{memory}}` μένουν έξω πρώτα οι παλαιότερες νέες, και μόνο
 * μετά οι παλαιότερες εγκεκριμένες.
 */
class MemoryTest {

    private fun note(i: Int, len: Int = 60) = "0$i/09: " + "x".repeat(len - 7)

    @Test fun empty() {
        val c = Memory.compose(emptyList(), emptyList(), "", 600)
        assertEquals("Τίποτα ακόμα.", c.text)
        assertEquals(0, c.dropped)
    }

    @Test fun approvedFirstThenInbox() {
        val c = Memory.compose(listOf("α"), listOf("β"), "", 600)
        assertEquals("α\nβ", c.text)
    }

    @Test fun inboxIsCutFirstOldestFirst() {
        // 5 εγκεκριμένες + 6 νέες των 60 (61 με την αλλαγή γραμμής) = 671 > 600.
        val a = (1..5).map { note(it) }
        val i = (1..6).map { note(it) }
        val c = Memory.compose(a, i, "", 600)
        assertTrue(c.text.length <= 600)
        assertEquals(2, c.dropped)
        // Όλες οι εγκεκριμένες μέσα· από τις νέες έξω οι δύο παλαιότερες.
        assertTrue(a.all { it in c.text })
        assertEquals(i.drop(2).joinToString("\n"), c.text.lines().drop(5).joinToString("\n"))
    }

    @Test fun approvedAreCutOnlyWhenTheyAloneOverflow() {
        val a = (1..11).map { note(it) }   // 671 μόνες τους
        val c = Memory.compose(a, listOf("νέα"), "", 600)
        assertTrue(c.text.length <= 600)
        assertEquals(3, c.dropped)          // δύο εγκεκριμένες και η νέα
        assertTrue(a.last() in c.text)
        assertTrue("νέα" !in c.text)
    }

    @Test fun reconnectTailSharesTheBudget() {
        val tail = "Κόπηκε η γραμμή.\n" + "y".repeat(250)
        val c = Memory.compose((1..6).map { note(it) }, emptyList(), tail, 600)
        assertTrue(c.text.length <= 600)
        assertTrue(c.text.endsWith(tail))
    }

    @Test fun bodyIgnoresDateButKeepsFreeFormLines() {
        assertEquals("Του αρέσει ο καφές σκέτος.", Memory.body("29/09: Του αρέσει ο καφές σκέτος."))
        assertEquals("Κάτι.", Memory.body("29/09 14:05: Κάτι."))
        // Ο Claude μπορεί να γράψει χωρίς ημερομηνία· μένει ολόκληρη.
        assertEquals("Ο Γιάννης: δεύτερη γενιά.", Memory.body("Ο Γιάννης: δεύτερη γενιά."))
    }

    @Test fun linesToleratesWindowsAndBlankLines() {
        assertEquals(listOf("α", "β"), Memory.lines("α\r\n\r\n  β  \r\n"))
    }
}
