package com.gemini.live.net

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gemini.live.tools.DeviceToolDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemini Live bidirectional WebSocket client.
 * Connects directly to Google Generative Language BidiGenerateContent WebSocket.
 * No Chromium engine needed! Pure lightweight OkHttp connection.
 */
class GeminiLiveClient(
    private val scope: CoroutineScope,
    private val toolDispatcher: DeviceToolDispatcher,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(errorMsg: String? = null)
        fun onAudioData(base64Pcm24k: String)
        fun onInterrupted()
        fun onTurnComplete()
        fun onStatusChanged(state: String, title: String, sub: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var autonomousStepCount = 0
    private val setupReady = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun connect(
        apiKey: String,
        model: String,
        voiceName: String,
        customPrompt: String
    ) {
        if (webSocket != null) return
        setupReady.set(false)

        val endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${Uri.encode(apiKey)}"
        val request = Request.Builder().url(endpoint).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // RULE #6: Inject Live Device Telemetry into setup prompt
                val now = Date()
                val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

                val telemetry = "\n\nLIVE DEVICE TELEMETRY:\n" +
                        "- Exact Local Time: ${timeFmt.format(now)}\n" +
                        "- Current Date: ${dateFmt.format(now)}\n" +
                        "- Timezone: ${TimeZone.getDefault().id}\n" +
                        "Answer all questions about the current time, weekday, and date immediately from this telemetry without calling any tool."

                val prompt = (customPrompt.ifEmpty { DEFAULT_SYSTEM_PROMPT }) + telemetry
                val formattedModel = if (model.startsWith("models/")) model else "models/$model"

                val setupPayload = JSONObject().apply {
                    put("setup", JSONObject().apply {
                        put("model", formattedModel)
                        put("generationConfig", JSONObject().apply {
                            put("responseModalities", JSONArray().apply { put("AUDIO") })
                            put("speechConfig", JSONObject().apply {
                                put("voiceConfig", JSONObject().apply {
                                    put("prebuiltVoiceConfig", JSONObject().apply {
                                        put("voiceName", voiceName)
                                    })
                                })
                            })
                        })
                        put("systemInstruction", JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                        put("tools", GeminiToolsSchema.getDeviceToolsJson())
                    })
                }

                ws.send(setupPayload.toString())
                android.util.Log.d("GeminiLiveClient", "Sent setup for model: $formattedModel, starting live capture immediately")
                mainHandler.post { listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                val msg = if (reason.isNotEmpty()) reason else "Code $code"
                android.util.Log.w("GeminiLiveClient", "WebSocket closing: $code / $reason")
                ws.close(1000, null)
                cleanUp(if (code != 1000) msg else null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val respBody = try { response?.body?.string() } catch (ignored: Exception) { null }
                val errorMsg = respBody ?: t.localizedMessage ?: "Connection failed"
                android.util.Log.e("GeminiLiveClient", "WebSocket failure: $errorMsg", t)
                cleanUp(errorMsg)
            }
        })
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Handle server error message
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: "Server error"
                android.util.Log.e("GeminiLiveClient", "Gemini error received: $errMsg")
                cleanUp(errMsg)
                return
            }

            // Handle setupComplete — Server is ready to receive audio and start talking
            if (json.has("setupComplete")) {
                android.util.Log.d("GeminiLiveClient", "✅ Server setupComplete received — live session active!")
                if (setupReady.compareAndSet(false, true)) {
                    listener.onConnected()
                }
                return
            }

            // Handle Tool Calls
            val toolCall = json.optJSONObject("toolCall")
            val functionCalls = toolCall?.optJSONArray("functionCalls")
            if (functionCalls != null) {
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.getJSONObject(i)
                    dispatchToolCall(
                        call.optString("name"),
                        call.optJSONObject("args") ?: JSONObject(),
                        call.optString("id", "call_1")
                    )
                }
            }

            // Handle Server Content (Interrupted, Turn Complete, Model Audio)
            val serverContent = json.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                listener.onInterrupted()
                return
            }

            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)

                    if (p.has("functionCall")) {
                        val fc = p.getJSONObject("functionCall")
                        dispatchToolCall(
                            fc.optString("name"),
                            fc.optJSONObject("args") ?: JSONObject(),
                            fc.optString("id", "call_1")
                        )
                    }

                    val inlineData = p.optJSONObject("inlineData")
                    val audioData = inlineData?.optString("data") ?: p.optString("data", "")
                    if (audioData.isNotEmpty()) {
                        listener.onAudioData(audioData)
                    } else if (p.has("text")) {
                        val txt = p.optString("text", "").trim()
                        if (txt.isNotEmpty()) {
                            android.util.Log.d("GeminiLiveClient", "Model text reply: $txt")
                            listener.onStatusChanged("speaking", "Jarvis", txt.take(30))
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                listener.onTurnComplete()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dispatchToolCall(name: String, args: JSONObject, callId: String) {
        autonomousStepCount++
        listener.onStatusChanged("working", "Working • Step $autonomousStepCount", "Executing $name...")

        if (autonomousStepCount > 10) {
            sendToolResponse(callId, "Safety limit reached. Pausing autonomous loop.")
            autonomousStepCount = 0
            return
        }

        scope.launch {
            val result = toolDispatcher.executeTool(name, args)
            sendToolResponse(callId, result)
        }
    }

    private fun sendToolResponse(callId: String, result: String) {
        val payload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", callId)
                        put("response", JSONObject().apply {
                            put("output", JSONObject().apply {
                                put("result", result)
                            })
                        })
                    })
                })
            })
        }
        webSocket?.send(payload.toString())
    }

    fun sendAudioPcm16k(base64: String) {
        val ws = webSocket ?: return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64)
                })
            })
        }
        ws.send(payload.toString())
    }

    fun sendVisualFrame(base64Jpeg: String) {
        val ws = webSocket ?: return
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("video", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Jpeg)
                })
            })
        }
        ws.send(payload.toString())
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "User disconnect")
        } catch (ignored: Exception) {}
        cleanUp(null)
    }

    private fun cleanUp(errorMsg: String? = null) {
        setupReady.set(false)
        webSocket = null
        autonomousStepCount = 0
        listener.onDisconnected(errorMsg)
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are Voice (Jarvis), an ultra-fast autonomous Android agent with direct device control, real-time web intelligence, and experiential memory.

CORE OPERATING DIRECTIVES:
1. REAL-TIME WEB FACTS & NEWS (SILENT WEB SEARCH):
   - Whenever asked for real-time facts, sports scores, exam dates, schedules, or news, ALWAYS call search_internet(query).
   - search_internet fetches live Google Search answers silently in the background. Speak the concise 1-2 sentence answer directly.
   - NEVER call search_web or open Chrome unless explicitly told to "open browser".
2. LIVE TELEMETRY:
   - You already know the exact time and date from your system telemetry. Speak it immediately without using any tool.
3. SPATIAL GROUNDING & CANDIDATE IDS:
   - Call read_screen_text first whenever you need to interact with an app. It returns an "INTERACTIVE ELEMENTS" catalog with numbered candidate IDs [#0, #1, #2...].
   - ALWAYS prefer calling tap_element_id(element_id) when an ID exists. It has 100% pinpoint precision (0.0px error).
   - To hold/select files or items by candidate ID, ALWAYS call long_press_element_id(element_id).
4. EDITING & TEXT REPLACEMENT:
   - When editing existing fields, changing contact names, or deleting text, ALWAYS call replace_text(text) or clear_text().
   - To insert text at the active cursor, call type_text(text).
5. SELF-LEARNING PLAYBOOKS:
   - When you discover a working button coordinate in an unfamiliar app, call save_app_rule so you remember it permanently!
6. CADENCE:
   - Execute actions silently in sequence. Speak a single, confident 1-sentence confirmation when finished."""
    }
}
