import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.google.gson.Gson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.skiko.MainUIDispatcher
import uitls.MorseTone
import java.net.http.HttpClient
import java.util.*
import kotlin.Char

const val wpm = 20
const val dit = (60f / (50f * wpm)) * 1000f
const val dahAndSpc = dit * 3
const val wrd = dit * 7

val morseCodeMap: Map<String, String> = mapOf(
    "A" to ".-", "B" to "-...", "C" to "-.-.", "D" to "-..", "E" to ".",
    "F" to "..-.", "G" to "--.", "H" to "....", "I" to "..", "J" to ".---",
    "K" to "-.-", "L" to ".-..", "M" to "--", "N" to "-.", "O" to "---",
    "P" to ".--.", "Q" to "--.-", "R" to ".-.", "S" to "...", "T" to "-",
    "U" to "..-", "V" to "...-", "W" to ".--", "X" to "-..-", "Y" to "-.--",
    "Z" to "--..",
    "0" to "-----", "1" to ".----", "2" to "..---", "3" to "...--", "4" to "....-",
    "5" to ".....", "6" to "-....", "7" to "--...", "8" to "---..", "9" to "----."
)

val reversedMorseCodeMap: Map<String, String> = morseCodeMap.entries.associate { (key, value) -> value to key }

@Composable
@Preview
fun App(uiState: MorseModel.UiState) {
    var text by remember { mutableStateOf("Hello, World!") }

    MaterialTheme {
        Column {
            Text(uiState.string)
            Text(uiState.chars)
            Button(onClick = {
                text = "Hello, Desktop!"
            }) {
                Text(text)
            }
        }
    }
}

fun main() = application {

    val morseModel = MorseModel()
    Window(onCloseRequest = ::exitApplication, onKeyEvent = {
        if (it.key == Key.Spacebar) {
            if (it.type == KeyEventType.KeyDown) {
                morseModel.down()
            } else {
                morseModel.up()
            }
        }
        true
    }) {
        val uiState by morseModel.uiState.collectAsState()
        App(uiState)
    }
}

class MorseModel {

    enum class ProcessingState(val value: String) {
        NOTHING(""),
        DIT("."),
        DAH("-"),
        DIT_LOCK("."),
        DAH_LOCK("-"),
        CHAR(" "),
        WORD(" / ");

        fun next() = when (this) {
            DIT -> DIT_LOCK
            DAH -> DAH_LOCK
            else -> this
        }

        fun locked() = this == DIT_LOCK || this == DAH_LOCK
    }

    private val messageResponseFlow = MutableSharedFlow<Message>(replay = 10)
    private val sharedFlow = messageResponseFlow.asSharedFlow()
    private val scope = CoroutineScope(MainUIDispatcher + SupervisorJob())
    var messageJob: Job? = null
    val tone = MorseTone(400,32767.0/4.0)
    val inTone = MorseTone(250,32767.0/4.0)

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            client.webSocket(
                method = HttpMethod.Get,
                host = "127.0.0.1",
                port = 8080,
                path = "ws"
            ) {
                launch(Dispatchers.Main) {
                    sharedFlow.collect { message ->
                            send(Gson().toJson(message))
                    }
                }
                while (isActive) {
                    val othersMessage = (incoming.receive() as? Frame.Text)?.readText() ?: continue
                    println("received: $othersMessage")
                    val message = Gson().fromJson(othersMessage, Message::class.java)
                    when(message.type) {
                        0 -> {
                            val morse = Gson().fromJson(message.message, Morse::class.java)
                            if (morse.on) inTone.start() else inTone.stop()
                        }
                    }
//                    val message = kotlin.runCatching { Gson().fromJson(othersMessage, Message::class.java) }.getOrNull()?: continue

                }
            }
        }
    }

    val down = MutableStateFlow(false)
    val lastDown = MutableStateFlow(System.currentTimeMillis())
    val morseBuffer = MutableStateFlow("")
    val charBuffer = MutableStateFlow("")
    val currentChar = MutableStateFlow("")

    val uiState = combine(
        morseBuffer,
        down,
        currentChar,
        charBuffer
    ) { morseBuffer, down, currentChar, charBuffer ->
        UiState(morseBuffer + currentChar, charBuffer, down)
    }.stateIn(GlobalScope, SharingStarted.WhileSubscribed(5000), UiState("", "", false))

    val timeoutJob: Job = CoroutineScope(Dispatchers.IO).launch {
        var processingState = ProcessingState.WORD
        var lastChange = System.currentTimeMillis()
        while (isActive) {
            val current = System.currentTimeMillis()
            val delta = current - lastChange
            if (down.value) {
                when (processingState) {
                    ProcessingState.WORD,
                    ProcessingState.CHAR -> {
                        println("down: nothing")
                        morseBuffer.update {
                            it + currentChar.value
                        }
                        processingState = ProcessingState.NOTHING
                        lastChange = current
                    }

                    ProcessingState.NOTHING -> {
                        println("down: dit")
                        processingState = ProcessingState.DIT
                        lastChange = current
                        currentChar.update { processingState.value }
                    }

                    ProcessingState.DIT -> {
                        if (delta >= dahAndSpc) {
                            println("down: dah")
                            processingState = ProcessingState.DAH
                            lastChange = current
                            currentChar.update { processingState.value }
                        }
                    }

                    ProcessingState.DIT_LOCK,
                    ProcessingState.DAH_LOCK -> {
                        println("down: dit or dah locked")
                        morseBuffer.update {
                            it + currentChar.value
                        }
                        processingState = ProcessingState.DIT
                        currentChar.update { processingState.value }
                        lastChange = current
                    }

                    else -> {}
                }
            } else {
                when (processingState) {
                    ProcessingState.DIT,
                    ProcessingState.DAH,
                    ProcessingState.DIT_LOCK,
                    ProcessingState.DAH_LOCK -> {
                        if (delta >= dahAndSpc && processingState.locked()) {
                            println("up: char")
                            morseBuffer.update {
                                it + currentChar.value
                            }
                            processingState = ProcessingState.CHAR
                            lastChange = current
                            addChar(
                                (reversedMorseCodeMap.getOrDefault(morseBuffer.value.split(" ").lastOrNull(), "")),
                                processingState.value
                            )
                        } else if (!processingState.locked()) {
                            lastChange = current
                            processingState = processingState.next()
                        }
                    }

                    ProcessingState.CHAR -> {
                        if (delta >= wrd) {
                            println("up: word")
                            processingState = ProcessingState.WORD
                            lastChange = current
                            addChar(processingState.value, " ")
                        }
                    }

                    else -> {}
                }
            }
            delay(10)
        }
    }

    fun addChar(char: String, newChar: String) {
        currentChar.update { newChar }
        charBuffer.update { it + char }
        messageResponseFlow.tryEmit(Message(1, Gson().toJson(Char(char))))

    }

    fun down() {
        if (!down.value) {
            tone.start()
            println("down")
            down.update { true }
            scope.launch {
                println(messageResponseFlow.tryEmit(Message(0, Gson().toJson(Morse(true)))))
            }
        }
    }

    fun up() {
        tone.stop()
        println(uiState.value.string)
        println("up")
        down.update { false }
        messageResponseFlow.tryEmit(Message(0, Gson().toJson(Morse(false))))
    }

    data class UiState(
        val string: String,
        val chars: String,
        val down: Boolean
    )
}

data class Message(val type: Int, val message: String)
data class Morse(val on: Boolean)
data class Char(val char: String)