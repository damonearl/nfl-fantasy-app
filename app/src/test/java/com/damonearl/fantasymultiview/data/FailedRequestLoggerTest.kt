package com.damonearl.fantasymultiview.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FailedRequestLoggerTest {
    @Test fun `redacts credential query parameters`() {
        assertEquals(
            "https://example.test/get.php?username=***&password=***&type=m3u_plus",
            FailedRequestLogger.redact("https://example.test/get.php?username=me&password=s3cret&type=m3u_plus".toHttpUrl()),
        )
        assertEquals(
            "https://api.example.test/guide?DeviceAuth=***&x=1",
            FailedRequestLogger.redact("https://api.example.test/guide?DeviceAuth=abc&x=1".toHttpUrl()),
        )
        assertEquals(
            "http://example.test:8080/live/***/***/123.ts",
            FailedRequestLogger.redact("http://example.test:8080/live/me/s3cret/123.ts".toHttpUrl()),
        )
    }
}
