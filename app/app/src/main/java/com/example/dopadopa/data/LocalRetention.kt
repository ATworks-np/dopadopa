package com.example.dopadopa.data

import android.content.SharedPreferences

/** Call under PrivacyGate.lock on a worker; metadata contains no observed text. */
object LocalRetention {
    fun prune(dao: GuardDao, queue: SharedPreferences, now: Long = System.currentTimeMillis()) {
        val before = now - 7L * 24 * 60 * 60 * 1000
        dao.expireSessions(before)
        dao.expireAnalyses(before)
        dao.expireFeedback(before)
        val sessions = dao.sessionIds().toSet()
        val editor = queue.edit()
        for (key in queue.all.keys) {
            if (key.substringBefore(':') in setOf("sent", "retry", "attempt") &&
                key.substringAfterLast(':') !in sessions) editor.remove(key)
        }
        editor.commit()
    }
}
