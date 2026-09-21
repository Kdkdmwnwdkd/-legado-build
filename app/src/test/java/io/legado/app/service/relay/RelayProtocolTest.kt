package io.legado.app.service.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class RelayProtocolTest {
    @Test
    fun secureWebSocketUsesHttpsUpgradeUrl() {
        val config = RelayConfig(
            workerUrl = "https://relay.example/".toHttpUrl(),
            deviceName = "test",
            identity = RelayIdentity("ABEiM0RVZneImaq7zN3u_w", ByteArray(32)),
            deviceHandle = "ABEiM0RVZneImaq7zN3u_w.AAAAAAAAAAAAAAAAAAAAAA"
        )

        assertEquals("https", config.socketUrl.scheme)
        assertEquals("/v1/device/connect", config.socketUrl.encodedPath)
        assertEquals(config.deviceHandle, config.socketUrl.queryParameter("deviceHandle"))
    }

    @Test
    fun binaryFrameRoundTrips() {
        val frame = RelayProtocol.BinaryFrame(
            type = RelayProtocol.BinaryType.HttpResponseChunk,
            flags = 1,
            requestId = 918273645L,
            sequence = 7,
            payload = byteArrayOf(1, 2, 3, 4)
        )
        val decoded = RelayProtocol.decode(RelayProtocol.encode(frame))
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.flags, decoded.flags)
        assertEquals(frame.requestId, decoded.requestId)
        assertEquals(frame.sequence, decoded.sequence)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun rejectsDeclaredLengthMismatch() {
        val bytes = RelayProtocol.encode(
            RelayProtocol.BinaryFrame(
                RelayProtocol.BinaryType.HttpRequestChunk,
                0,
                1,
                0,
                byteArrayOf(9)
            )
        )
        bytes[23] = 2
        assertThrows(IllegalArgumentException::class.java) { RelayProtocol.decode(bytes) }
    }

    @Test
    fun rejectsOversizedChunkBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            RelayProtocol.encode(
                RelayProtocol.BinaryFrame(
                    RelayProtocol.BinaryType.HttpRequestChunk,
                    0,
                    1,
                    0,
                    ByteArray(RelayProtocol.MAX_CHUNK_BYTES + 1)
                )
            )
        }
    }
}
