package de.gurkonier

import io.ktor.server.application.*
import kotlinx.coroutines.*
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import kotlin.experimental.and
import kotlin.math.sin


suspend fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureSockets()
    configureSerialization()
    configureRouting()
}
