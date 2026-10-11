package com.example.dopadopa.data

import java.util.concurrent.Executors

object PrivacyGate {
    val lock = Any()
    val io = Executors.newSingleThreadExecutor()
}
