package app.cellalerthook.domain

import app.cellalerthook.config.ReceiverConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerRulesTest {
    private fun match(text: String) = TriggerRules.match(
        text,
        ReceiverConfig.DEFAULT_ACTIVATE_PHRASES,
        ReceiverConfig.DEFAULT_CLEAR_PHRASES,
    )

    @Test
    fun `the real warning text is a trigger in either language`() {
        assertEquals(TriggerMatch.ACTIVATE, match("تحذير \n‏تتعرض المنطقة لتهديد جوي معادي يرجى التصرف بهدوء"))
        assertEquals(TriggerMatch.ACTIVATE, match("Warning\nThe area is under a hostile aerial threat."))
        assertEquals(TriggerMatch.ACTIVATE, match("The city is under a HOSTILE  AERIAL\nTHREAT."))
    }

    @Test
    fun `the real all-clear text clears in either language`() {
        assertEquals(TriggerMatch.CLEAR, match("تنبيه\nزال الخطر، تجنب مواقع سقوط المقذوف"))
        assertEquals(TriggerMatch.CLEAR, match("Alert\nThe threat has passed, Stay away from the impact site"))
    }

    @Test
    fun `a broadcast mark inside the phrase cannot hide it`() {
        assertEquals(TriggerMatch.ACTIVATE, match("تتعرض المنطقة لتهديد‏ جوي معادي"))
    }

    @Test
    fun `other alerts do nothing`() {
        assertEquals(TriggerMatch.NO_RULE, match("Roads leading to the city centre are congested."))
        assertEquals(TriggerMatch.NO_RULE, match("Heavy rain is expected this evening."))
    }

    @Test
    fun `text matching both rules does nothing, because its meaning is unclear`() {
        assertEquals(TriggerMatch.BOTH_RULES, match("The hostile aerial threat has not ended, the threat has passed north"))
    }
}
