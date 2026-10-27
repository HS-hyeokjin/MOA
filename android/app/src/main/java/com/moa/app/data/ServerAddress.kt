package com.moa.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun normalizeServerAddress(value: String, allowLocalHttp: Boolean): String {
    val url = try { value.trim().toHttpUrl() } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("서버 주소를 확인해 주세요.")
    }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
        "주소는 https://가족서버주소 형식으로 입력해 주세요."
    }
    require(url.isHttps || (allowLocalHttp && url.host in listOf("127.0.0.1", "localhost", "10.0.2.2"))) {
        "안전한 HTTPS 서버 주소를 입력해 주세요."
    }
    return url.toString().trimEnd('/')
}
