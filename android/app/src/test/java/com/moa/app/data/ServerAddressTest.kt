package com.moa.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun permitsHttpsAndNormalizesTheAddress() {
        assertEquals("https://family.example.com", normalizeServerAddress(" https://family.example.com/ ", false))
    }
    @Test fun allowsOnlyExplicitLocalHttpInDebugBuilds() {
        assertEquals("http://10.0.2.2:8080", normalizeServerAddress("http://10.0.2.2:8080", true))
        for (url in listOf("http://family.example.com", "http://localhost.evil.example", "http://192.168.0.1")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeServerAddress(url, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerAddress("http://localhost:8080", false) }
    }
    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        for (url in listOf("https://token@family.example.com", "https://family.example.com/api", "https://family.example.com?token=secret", "https://family.example.com#secret", "not a URL")) {
            assertThrows(IllegalArgumentException::class.java) { normalizeServerAddress(url, false) }
        }
    }
}
