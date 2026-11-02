package app.cellalerthook.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetSealTest {
    /** Made by seal() in tools/build-preset-apk.js, so this proves the tool and the app agree. The links are fake. */
    private val sealed = """{"iterations":600000,"salt":"izcjbTk/7AwuyHEev1IkGw==","iv":"6nAgDWvMrloLxxmu","data":"789Ur5D323bhCPBqPUR8jjCqNEpMVhintM34HWISa1L2RWmZ/qNn97GfF5oAVCKGKAutQgsDaOCMth85iDIe8Ov6zKJnwF8SB8W563z1IDI05lxWXSooS3NAssLYmcFfnxsZjTH4y5Qv+LWEVuDBqiD7lbssWtvPnvJRSl0VCXuIDiQ7mXQI5bCbhTL+I/pxhBzc+wh/I8s5BKt5O7I+iWT1L2kE6Q=="}"""

    @Test
    fun `the right PIN opens what the tool sealed`() {
        val links = PresetSeal.open(sealed, "24681357")
        assertEquals("https://192.168.1.50/on?key=not-a-real-key", links?.activateUrl)
        assertEquals("https://192.168.1.50/off?key=not-a-real-key", links?.clearUrl)
        assertEquals("fake certificate", links?.certificate)
    }

    @Test
    fun `the same PIN in Arabic digits opens it too`() {
        assertEquals("https://192.168.1.50/on?key=not-a-real-key", PresetSeal.open(sealed, "٢٤٦٨١٣٥٧")?.activateUrl)
    }

    @Test
    fun `a wrong PIN opens nothing`() {
        assertNull(PresetSeal.open(sealed, "24681358"))
    }

    @Test
    fun `a damaged seal opens nothing`() {
        assertNull(PresetSeal.open(sealed.replace("789Ur5D3", "AAAAAAAA"), "24681357"))
        assertNull(PresetSeal.open("not json", "24681357"))
    }
}
