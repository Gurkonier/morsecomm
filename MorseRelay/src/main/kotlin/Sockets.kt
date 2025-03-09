package de.gurkonier

import com.google.gson.Gson
import de.gurkonier.Char
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val messageResponseFlow = MutableSharedFlow<SessionContextMessage>(replay = 10)
    val sharedFlow = messageResponseFlow.asSharedFlow()

    routing {
        webSocket("/ws") { // websocketSession
            val sessionId = UUID.randomUUID()
            val job = launch {
                sharedFlow.collect { message ->
                    ensureActive()
                    if (message.sessionId != sessionId) {
                        send(message.message)
                    }
                }
            }

            runCatching {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val received = frame.readText()
                        messageResponseFlow.tryEmit(SessionContextMessage(sessionId, received))
                    }
                }
            }.onFailure { exception ->
                println("WebSocket exception: ${exception.localizedMessage}")
            }.also {
                job.cancel()
            }
        }
    }
}


data class Message(val type: Int, val message: Any)
data class SessionContextMessage(val sessionId: UUID, val message: String)
data class Morse(val on: Boolean)
data class Char(val char: String)
