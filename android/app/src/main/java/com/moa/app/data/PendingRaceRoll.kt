package com.moa.app.data

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** Persist the request before sending it, so process death cannot turn a retry into a second roll. */
class PendingRaceRoll(context: Context) {
    private val preferences = context.getSharedPreferences("race_pending", Context.MODE_PRIVATE)
    private fun key(session: Session): String = MessageDigest.getInstance("SHA-256")
        .digest("${session.serverUrl}\n${session.member.id}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun exists(session: Session): Boolean = preferences.contains(key(session))
    fun getOrCreate(session: Session): String {
        val key = key(session)
        return preferences.getString(key, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(key, it).commit()) { "요청을 저장하지 못했어요. 저장 공간을 확인해 주세요." }
        }
    }
    fun clear(session: Session) {
        check(preferences.edit().remove(key(session)).commit()) { "결과는 저장됐어요. 다시 누르면 같은 결과를 확인할 수 있어요." }
    }
}
