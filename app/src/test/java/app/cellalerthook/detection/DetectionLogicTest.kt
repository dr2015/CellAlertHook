package app.cellalerthook.detection

import app.cellalerthook.ARABIC_ALERT
import app.cellalerthook.domain.CaptureMethod
import app.cellalerthook.domain.OriginTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private data class FakeNode(
    override val viewId: String? = null,
    override val className: CharSequence? = "android.widget.TextView",
    override val text: CharSequence? = null,
    override val isPassword: Boolean = false,
    override val isEditable: Boolean = false,
    val children: List<UiNode> = emptyList(),
) : UiNode {
    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
}

private const val PKG = "com.google.android.cellbroadcastreceiver"

/** The tree captured from the real CellBroadcastAlertDialog on Android 14. */
private fun realAlertDialog(message: CharSequence = ARABIC_ALERT) = FakeNode(
    viewId = "android:id/content",
    className = "android.widget.FrameLayout",
    children = listOf(
        FakeNode(
            viewId = "$PKG:id/parentPanel",
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(viewId = "$PKG:id/alertTitle", text = "National alert"),
                FakeNode(viewId = "$PKG:id/message", text = message),
                FakeNode(viewId = "$PKG:id/dismissButton", className = "android.widget.Button", text = "OK"),
            ),
        ),
    ),
)

class AlertWindowExtractorTest {
    private val extractor = AlertWindowExtractor()

    @Test
    fun `reads title and message from the real dialog structure`() {
        val alert = extractor.extract(realAlertDialog(), requireKnownIds = true)!!
        assertEquals("National alert", alert.title)
        assertEquals(ARABIC_ALERT, alert.body)
        assertTrue(alert.usedKnownIds)
    }

    @Test
    fun `a password field anywhere stops the read entirely`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(viewId = "x:id/message", text = "secret page"),
                FakeNode(text = "hunter2", isPassword = true),
            ),
        )
        assertNull(extractor.extract(root, requireKnownIds = false))
    }

    @Test
    fun `an editable field means this is not an alert`() {
        val root = FakeNode(children = listOf(FakeNode(text = "draft message", isEditable = true)))
        assertNull(extractor.extract(root, requireKnownIds = false))
    }

    @Test
    fun `unknown class without alert ids yields nothing`() {
        val root = FakeNode(children = listOf(FakeNode(text = "Some ordinary page of text")))
        assertNull(extractor.extract(root, requireKnownIds = true))
    }

    @Test
    fun `an OEM dialog falls back to the longest text and skips buttons`() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "Alert"),
                FakeNode(text = ARABIC_ALERT),
                FakeNode(className = "android.widget.Button", text = "A very long dismiss button label here indeed for sure"),
            ),
        )
        val alert = extractor.extract(root, requireKnownIds = false)!!
        assertEquals(ARABIC_ALERT, alert.body)
        assertEquals("Alert", alert.title)
        assertFalse(alert.usedKnownIds)
    }

    @Test
    fun `an empty window yields nothing`() {
        assertNull(extractor.extract(FakeNode(), requireKnownIds = false))
    }

    @Test
    fun `an enormous tree is bounded`() {
        val wide = FakeNode(children = List(5000) { FakeNode(text = "row $it") })
        extractor.extract(wide, requireKnownIds = false)
    }
}

class AlertWindowClassifierTest {
    @Test
    fun `the real alert activity is captured`() {
        assertEquals(
            WindowDecision.CAPTURE,
            AlertWindowClassifier.classify("com.android.cellbroadcastreceiver.CellBroadcastAlertDialog"),
        )
    }

    @Test
    fun `settings and history screens of the alert app are ignored`() {
        listOf(
            "com.android.cellbroadcastreceiver.CellBroadcastSettings",
            "com.android.cellbroadcastreceiver.CellBroadcastListActivity",
            "com.android.cellbroadcastreceiver.CellBroadcastOptOutActivity",
        ).forEach { assertEquals(it, WindowDecision.IGNORE, AlertWindowClassifier.classify(it)) }
    }

    @Test
    fun `an unknown class needs the known ids`() {
        assertEquals(WindowDecision.CAPTURE_IF_KNOWN_IDS, AlertWindowClassifier.classify(null))
        assertEquals(WindowDecision.CAPTURE_IF_KNOWN_IDS, AlertWindowClassifier.classify("android.widget.FrameLayout"))
    }
}

class NotificationAlertClassifierTest {
    private val allowed = KnownAlertPackages.DEFAULT_ALLOWLIST
    private val self = "app.cellalerthook"

    private fun notification(
        packageName: String = PKG,
        channelId: String? = "broadcastMessagesHighPriority",
        tag: String? = null,
        text: String? = ARABIC_ALERT,
        bigText: String? = ARABIC_ALERT,
    ) = NotificationSnapshot(packageName, channelId, tag, "National alert", text, bigText, emptyList(), 0)

    private fun classify(snapshot: NotificationSnapshot) =
        NotificationAlertClassifier.classify(snapshot, allowed, self, NotificationAlertDetector.SIMULATION_TAG)

    @Test
    fun `the notification measured on Android 14 is accepted`() {
        assertEquals(NotificationDecision.ACCEPT, classify(notification()))
    }

    @Test
    fun `unrelated apps are ignored before anything else`() {
        assertEquals(NotificationDecision.IGNORE_PACKAGE, classify(notification(packageName = "com.whatsapp")))
    }

    @Test
    fun `a settings notice from the alert app is not an alert`() {
        assertEquals(NotificationDecision.IGNORE_CHANNEL, classify(notification(channelId = "settingsUpdates")))
    }

    @Test
    fun `the unread summary carries no alert text and is skipped`() {
        assertEquals(NotificationDecision.IGNORE_SUMMARY, classify(notification(text = "2 unread alerts", bigText = null)))
    }

    @Test
    fun `this app is accepted only with the simulation tag`() {
        assertEquals(NotificationDecision.IGNORE_PACKAGE, classify(notification(packageName = self, channelId = "receiver_status")))
        assertEquals(
            NotificationDecision.ACCEPT,
            classify(notification(packageName = self, channelId = "simulation", tag = NotificationAlertDetector.SIMULATION_TAG)),
        )
    }

    @Test
    fun `another app cannot pose as a simulation`() {
        assertEquals(
            NotificationDecision.IGNORE_PACKAGE,
            classify(notification(packageName = "com.evil", tag = NotificationAlertDetector.SIMULATION_TAG)),
        )
    }

    @Test
    fun `big text is preferred over the collapsed line`() {
        val snapshot = notification(text = "shortened…", bigText = ARABIC_ALERT)
        assertEquals(ARABIC_ALERT, NotificationAlertClassifier.bodyOf(snapshot))
    }

    @Test
    fun `Android's placeholder is recognised as hidden text, not as the alert`() {
        val snapshot = notification(text = PLACEHOLDER, bigText = PLACEHOLDER).copy(postTimeMs = 42)
        assertEquals(NotificationDecision.ACCEPT, classify(snapshot))
        assertEquals("hidden|$PKG|42", NotificationAlertClassifier.hiddenTextKey(snapshot, setOf(PLACEHOLDER)))
    }

    @Test
    fun `alert text is never mistaken for the placeholder`() {
        assertNull(NotificationAlertClassifier.hiddenTextKey(notification(), setOf(PLACEHOLDER)))
    }

    @Test
    fun `real big text still counts when only the collapsed line was replaced`() {
        val snapshot = notification(text = PLACEHOLDER, bigText = ARABIC_ALERT)
        assertNull(NotificationAlertClassifier.hiddenTextKey(snapshot, setOf(PLACEHOLDER)))
    }

    private companion object {
        const val PLACEHOLDER = "Sensitive notification content hidden"
    }
}

class CellBroadcastRowMapperTest {
    private class MapRow(private val values: Map<String, Any?>) : CellBroadcastRow {
        override fun string(column: String) = values[column]?.toString()
        override fun long(column: String) = (values[column] as? Number)?.toLong()
    }

    /** The row actually read from the provider during the Android 14 proof of concept. */
    private val measured = mapOf(
        "_id" to 1L, "sub_id" to 1L, "slot_index" to 0L, "geo_scope" to 3L, "plmn" to "310260",
        "lac" to 8514L, "cid" to 47108L, "serial_number" to 49808L, "service_category" to 4370L,
        "language" to "ar", "dcs" to 17L, "body" to ARABIC_ALERT, "format" to 1L, "priority" to 3L,
        "etws_warning_type" to null, "etws_is_primary" to 0L, "cmas_message_class" to 0L,
        "cmas_category" to -1L, "cmas_response_type" to -1L, "cmas_severity" to -1L,
        "cmas_urgency" to -1L, "cmas_certainty" to -1L, "received_time" to 1793559982789L,
        "location_check_time" to -1L, "message_broadcasted" to 1L, "message_displayed" to 1L,
        "geometries" to null, "maximum_wait_time" to 0L,
    )

    @Test
    fun `a real row maps to a provider observation`() {
        val observation = CellBroadcastRowMapper.toObservation(MapRow(measured), "com.google.android.cellbroadcastservice", 5, false)!!
        val metadata = observation.cellBroadcast!!
        assertEquals(CaptureMethod.CELL_BROADCAST_PROVIDER, observation.method)
        assertEquals(OriginTrust.SYSTEM_IMAGE, observation.originTrust)
        assertEquals(ARABIC_ALERT, observation.body)
        assertEquals("ar", observation.languageHint)
        assertEquals(4370, metadata.messageId)
        assertEquals(49808, metadata.serialNumber)
        assertEquals("310260", metadata.plmn)
        assertEquals(1793559982789L, metadata.receivedTimeMs)
        assertEquals(true, metadata.displayed)
        assertNull(metadata.etwsWarningType)
    }

    @Test
    fun `the row key survives restarts but not a database reset`() {
        assertEquals("1:1793559982789", CellBroadcastRowMapper.rowKey(MapRow(measured)))
        val reused = measured + ("received_time" to 1793559999999L)
        assertFalse(CellBroadcastRowMapper.rowKey(MapRow(measured)) == CellBroadcastRowMapper.rowKey(MapRow(reused)))
    }

    @Test
    fun `a row without its identifiers is refused`() {
        listOf("_id", "service_category", "serial_number", "received_time").forEach { column ->
            assertNull(column, CellBroadcastRowMapper.toObservation(MapRow(measured - column), null, 0, false))
        }
    }

    @Test
    fun `an unknown extra column from a future Android is ignored`() {
        val future = measured + ("new_column" to "x")
        assertEquals(4370, CellBroadcastRowMapper.toObservation(MapRow(future), null, 0, false)!!.cellBroadcast!!.messageId)
    }
}
