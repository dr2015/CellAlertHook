package app.cellalerthook.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SealedLinks(val activateUrl: String, val clearUrl: String, val certificate: String)

/**
 * Opens the links sealed into a preset build by tools/build-preset-apk.js. Anyone holding
 * an APK can read what is in it, so the links are encrypted with the setup PIN, which the APK
 * does not hold. Everything else needed to open them travels with them.
 */
object PresetSeal {
    const val ASSET = "sealed_links.json"

    @Serializable
    private data class Sealed(val iterations: Int, val salt: String, val iv: String, val data: String)

    @Serializable
    private data class Links(val activateUrl: String, val clearUrl: String, val certificate: String = "")

    /** Null for a wrong PIN or a damaged seal, because GCM refuses both. Slow on purpose. */
    fun open(sealed: String, pin: String): SealedLinks? = runCatching {
        val seal = Json.decodeFromString<Sealed>(sealed)
        val key = PinHash.of(pin, decode(seal.salt), seal.iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decode(seal.iv)))
        val links = Json.decodeFromString<Links>(String(cipher.doFinal(decode(seal.data)), Charsets.UTF_8))
        SealedLinks(links.activateUrl, links.clearUrl, links.certificate)
    }.getOrNull()

    private fun decode(text: String) = Base64.getDecoder().decode(text)
}
