package io.legado.app.model.localBook.epubcore.font

import io.legado.app.model.localBook.epubcore.archive.ZipEpubArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubFontDeobfuscatingArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `idpf and adobe obfuscated fonts are decoded for bytes and streams`() {
        val identifier = "urn:uuid:0123 4567-89ab-cdef-\n0123-456789abcdef"
        val idpfKey = hexBytes("79e26e1c4f54200ad4e45a8134d99937af3c7eee")
        val adobeKey = hexBytes("0123456789abcdef0123456789abcdef")
        val idpfPlain = ByteArray(1300) { index -> ((index * 31 + 7) and 0xff).toByte() }
        val adobePlain = ByteArray(1200) { index -> ((index * 17 + 11) and 0xff).toByte() }
        val untouched = byteArrayOf(9, 8, 7, 6)
        val entries = linkedMapOf(
            "META-INF/Encryption.XML" to """
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
                            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding" />
                    <enc:CipherData><enc:CipherReference URI="OEBPS/Fonts/IDPF%20Font.OTF" /></enc:CipherData>
                  </enc:EncryptedData>
                  <enc:EncryptedData>
                    <enc:EncryptionMethod Algorithm="http://ns.adobe.com/pdf/enc#RC" />
                    <enc:CipherData><enc:CipherReference URI="OEBPS/Fonts/Adobe.TTF" /></enc:CipherData>
                  </enc:EncryptedData>
                </encryption>
            """.trimIndent().toByteArray(),
            "OEBPS/Fonts/IDPF Font.OTF" to xorPrefix(idpfPlain, idpfKey, 1040),
            "OEBPS/Fonts/Adobe.TTF" to xorPrefix(adobePlain, adobeKey, 1024),
            "OEBPS/Fonts/Untouched.WOFF2" to untouched
        )
        val file = temporaryFolder.newFile("obfuscated-fonts.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }

        EpubFontDeobfuscatingArchive.wrap(ZipEpubArchive(file), identifier).use { archive ->
            assertArrayEquals(
                idpfPlain,
                archive.readBytes("oebps/fonts/idpf font.otf", 2048)
            )
            assertArrayEquals(
                adobePlain,
                archive.readBytes("OEBPS/Fonts/Adobe.TTF", 2048)
            )
            assertArrayEquals(
                untouched,
                archive.readBytes("OEBPS/Fonts/Untouched.WOFF2", 2048)
            )

            archive.openStream("OEBPS/Fonts/IDPF Font.OTF").use { stream ->
                assertEquals(1032L, stream.skip(1032L))
                val actual = ByteArray(32)
                assertEquals(actual.size, stream.read(actual))
                assertArrayEquals(idpfPlain.copyOfRange(1032, 1064), actual)
            }
        }
    }

    private fun xorPrefix(source: ByteArray, key: ByteArray, length: Int): ByteArray {
        return source.copyOf().also { output ->
            repeat(minOf(length, output.size)) { index ->
                output[index] = (output[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
        }
    }

    private fun hexBytes(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
