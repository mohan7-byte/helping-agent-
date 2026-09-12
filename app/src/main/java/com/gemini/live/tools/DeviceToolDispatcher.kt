package com.gemini.live.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.accessibilityservice.AccessibilityService
import com.gemini.live.ScreenCaptureService
import com.gemini.live.VolumeTriggerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Executes all 100% preserved autonomous device tools.
 * CRITICAL RULE #6: Silent web search via background Gemini REST API (no Chrome popups!).
 */
class DeviceToolDispatcher(
    private val context: Context,
    private val apiKeyProvider: () -> String,
    private val sendVisualFrame: (String) -> Unit
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Persistent Playbook Storage in Documents/Voice/rules.json (manual and AI editable)
    private val prefs = context.getSharedPreferences("jarvis_rules", Context.MODE_PRIVATE)

    fun getRulesFile(): File {
        return try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val voiceDir = File(docsDir, "Voice")
            if (!voiceDir.exists()) voiceDir.mkdirs()
            File(voiceDir, "rules.json")
        } catch (e: Exception) {
            val fallbackDir = File(context.getExternalFilesDir(null), "Voice")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            File(fallbackDir, "rules.json")
        }
    }

    fun getSavedRulesJson(): String {
        try {
            val file = getRulesFile()
            if (file.exists() && file.length() > 0) {
                val content = file.readText().trim()
                if (content.startsWith("{")) {
                    return content
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("DeviceToolDispatcher", "Reading rules.json failed: ${e.message}")
        }
        val defaultRules = prefs.getString("rules", getDefaultAppRulesJson()) ?: getDefaultAppRulesJson()
        // Ensure the file exists with default rules
        try {
            val file = getRulesFile()
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(defaultRules)
            }
        } catch (ignored: Exception) {}
        return defaultRules
    }

    fun saveRulesJson(json: String) {
        prefs.edit().putString("rules", json).apply()
        try {
            val file = getRulesFile()
            file.parentFile?.mkdirs()
            file.writeText(json)
        } catch (e: Exception) {
            android.util.Log.e("DeviceToolDispatcher", "Writing rules.json failed: ${e.message}")
            try {
                val fallbackFile = File(context.getExternalFilesDir(null), "rules.json")
                fallbackFile.writeText(json)
            } catch (ignored: Exception) {}
        }
    }

    private fun getDefaultAppRulesJson(): String {
        return JSONObject().apply {
            put("com.samsung.android.app.notes", JSONObject().apply {
                put("app_name", "Samsung Notes")
                put("actions", JSONObject().apply {
                    put("add_note_button", JSONObject().apply { put("x", 875); put("y", 935) })
                })
                put("rules", JSONArray().apply {
                    put("To create a new note, tap the circular pen button at (875, 935) or call tap_element_id.")
                    put("If the cursor is already blinking, do not tap; paste directly with type_text.")
                    put("To edit existing text or change titles, use replace_text or clear_text.")
                })
            })
            put("com.google.android.youtube", JSONObject().apply {
                put("app_name", "YouTube")
                put("rules", JSONArray().apply {
                    put("Use search_youtube for instant 1-shot searches without UI clicks.")
                })
            })
            put("com.whatsapp", JSONObject().apply {
                put("app_name", "WhatsApp")
                put("rules", JSONArray().apply {
                    put("Use open_whatsapp to jump directly into chat with prefilled message.")
                })
            })
        }.toString(2)
    }

    suspend fun executeTool(name: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                // Rule #6: Silent Grounded Web Search (Zero Chrome opening!)
                "search_internet" -> {
                    val query = args.optString("query")
                    val apiKey = apiKeyProvider()
                    if (apiKey.isEmpty()) return@withContext "Error: Gemini API Key required for web search."

                    try {
                        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${Uri.encode(apiKey)}"
                        val requestJson = JSONObject().apply {
                            put("contents", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("parts", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("text", "Answer factually, directly and accurately in 1 to 2 concise sentences based on live web search: $query")
                                        })
                                    })
                                })
                            })
                            put("tools", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("google_search", JSONObject())
                                })
                            })
                        }

                        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                        val req = Request.Builder().url(endpoint).post(body).build()
                        val res = okHttpClient.newCall(req).execute()
                        val resBody = res.body?.string() ?: ""
                        val resObj = JSONObject(resBody)
                        val text = resObj.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text")

                        return@withContext text ?: "Could not retrieve live search information."
                    } catch (e: Exception) {
                        return@withContext "Web search error: ${e.message}"
                    }
                }

                // Rule #6: Real-time Device Telemetry
                "get_device_info" -> {
                    val now = Date()
                    val timeFmt = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
                    val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())

                    var batteryPct = -1
                    try {
                        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                        if (bm != null) {
                            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        }
                    } catch (ignored: Exception) {}

                    return@withContext JSONObject().apply {
                        put("current_time", timeFmt.format(now))
                        put("current_date", dateFmt.format(now))
                        put("timezone", TimeZone.getDefault().id)
                        put("battery", if (batteryPct >= 0) "$batteryPct%" else "Unknown")
                    }.toString()
                }

                // Rule #3: Candidate ID Grounding
                "tap_element_id" -> {
                    val id = args.optInt("element_id", 0)
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    return@withContext service.executeTapElementId(id)
                }

                "long_press_element_id" -> {
                    val id = args.optInt("element_id", 0)
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    return@withContext service.executeLongPressElementId(id)
                }

                // Rule #5: Multi-stage Text Replacement
                "replace_text" -> {
                    val text = args.optString("text", "")
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    return@withContext service.replaceText(text)
                }

                "clear_text" -> {
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    return@withContext service.clearText()
                }

                "type_text" -> {
                    val text = args.optString("text", "")
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val ok = service.typeText(text)
                    return@withContext if (ok) "Typed: $text" else "Could not find an active editable field."
                }

                "tap_coordinates" -> {
                    val x = args.optInt("x", 500)
                    val y = args.optInt("y", 500)
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val ok = service.tapCoordinates(x, y)
                    return@withContext if (ok) "Tapped at ($x, $y)" else "Failed to tap coordinates."
                }

                "long_press" -> {
                    val x = args.optInt("x", 500)
                    val y = args.optInt("y", 500)
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val ok = service.longPress(x, y)
                    return@withContext if (ok) "Long-pressed at ($x, $y)" else "Failed to long-press."
                }

                "scroll" -> {
                    val dir = args.optString("direction", "down")
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val ok = service.scroll(dir)
                    return@withContext if (ok) "Scrolled $dir" else "Scroll failed."
                }

                "swipe" -> {
                    val sx = args.optInt("start_x", 500)
                    val sy = args.optInt("start_y", 800)
                    val ex = args.optInt("end_x", 500)
                    val ey = args.optInt("end_y", 300)
                    val dur = args.optInt("duration_ms", 300)
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val ok = service.swipe(sx, sy, ex, ey, dur)
                    return@withContext if (ok) "Swiped successfully." else "Swipe failed."
                }

                "wait_seconds" -> {
                    val sec = Math.min(Math.max(args.optDouble("seconds", 1.0), 0.5), 4.0)
                    kotlinx.coroutines.delay((sec * 1000).toLong())
                    return@withContext "Waited $sec seconds."
                }

                "read_screen_text" -> {
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service required."
                    val rawScreen = service.readScreenText()

                    // Contextual Rule Grounding: Load ONLY the rule for the active foreground app/package
                    var playbookTip = ""
                    try {
                        val fgPkg = service.currentPackageName
                        val rulesObj = JSONObject(getSavedRulesJson())
                        val keys = rulesObj.keys()
                        while (keys.hasNext()) {
                            val pkg = keys.next()
                            val data = rulesObj.optJSONObject(pkg) ?: continue
                            val appName = data.optString("app_name", "")

                            // Precise contextual match: active package or foreground screen name
                            val isMatch = (fgPkg.isNotEmpty() && fgPkg.equals(pkg, ignoreCase = true)) ||
                                          rawScreen.contains(pkg) ||
                                          (appName.isNotEmpty() && rawScreen.contains(appName, ignoreCase = true))

                            if (isMatch) {
                                val label = if (appName.isNotEmpty()) appName else pkg
                                playbookTip += "\n[SPECIFIC RULE FOR $label]:\n"
                                val actions = data.optJSONObject("actions")
                                if (actions != null) {
                                    val actKeys = actions.keys()
                                    while (actKeys.hasNext()) {
                                        val k = actKeys.next()
                                        val c = actions.optJSONObject(k)
                                        if (c != null) {
                                            playbookTip += "- Target \"$k\": (${c.optInt("x")}, ${c.optInt("y")})\n"
                                        }
                                    }
                                }
                                val rulesList = data.optJSONArray("rules")
                                if (rulesList != null) {
                                    for (i in 0 until rulesList.length()) {
                                        playbookTip += "- Rule: ${rulesList.getString(i)}\n"
                                    }
                                }
                                // Break early: contextual rule grounding loads the single exact matching rule!
                                break
                            }
                        }
                    } catch (ignored: Exception) {}

                    return@withContext if (playbookTip.isNotEmpty()) {
                        "=== ACTIVE APP PLAYBOOK & MEMORY ===$playbookTip\n\n$rawScreen"
                    } else {
                        rawScreen
                    }
                }

                // Rule #4: 1:1 Crisp Screen Capture Frame Injection
                "capture_screen" -> {
                    val captureService = ScreenCaptureService.instance
                    if (captureService == null) return@withContext "Screen capture service not initialized. Grant permission once via app."
                    val b64 = captureService.captureScreenBase64()
                    if (!b64.isNullOrEmpty()) {
                        sendVisualFrame(b64)
                        return@withContext "Screenshot captured and injected into your visual feed."
                    } else {
                        return@withContext "Failed to capture screenshot."
                    }
                }

                "save_app_rule" -> {
                    val pkg = args.optString("app_package", "").trim()
                    val act = args.optString("action_name", "").trim()
                    val rule = args.optString("rule", "").trim()
                    val x = args.optInt("x", -1)
                    val y = args.optInt("y", -1)

                    if (pkg.isEmpty()) return@withContext "Error: App package required."

                    try {
                        val current = JSONObject(getSavedRulesJson())
                        val appObj = current.optJSONObject(pkg) ?: JSONObject().apply {
                            put("app_name", pkg)
                            put("actions", JSONObject())
                            put("rules", JSONArray())
                        }

                        if (act.isNotEmpty() && x >= 0 && y >= 0) {
                            val acts = appObj.optJSONObject("actions") ?: JSONObject()
                            acts.put(act, JSONObject().apply { put("x", x); put("y", y) })
                            appObj.put("actions", acts)
                        }

                        if (rule.isNotEmpty()) {
                            val rules = appObj.optJSONArray("rules") ?: JSONArray()
                            rules.put(rule)
                            appObj.put("rules", rules)
                        }

                        current.put(pkg, appObj)
                        saveRulesJson(current.toString(2))
                        return@withContext "Saved rule for $pkg into persistent memory."
                    } catch (e: Exception) {
                        return@withContext "Failed to save rule: ${e.message}"
                    }
                }

                "create_note" -> {
                    val text = args.optString("text", "")
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Create Note").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return@withContext "Dispatched create note intent."
                }

                "open_application" -> {
                    val appName = args.optString("app_name", "").trim().lowercase()
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in apps) {
                        val label = pm.getApplicationLabel(app).toString().lowercase()
                        if (label.contains(appName)) {
                            val launch = pm.getLaunchIntentForPackage(app.packageName)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launch)
                                return@withContext "Opened $label"
                            }
                        }
                    }
                    return@withContext "Could not find an app named $appName"
                }

                "search_contacts" -> {
                    val query = args.optString("query", "").trim()
                    val cr = context.contentResolver
                    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                    val cursor = cr.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$query%"),
                        null
                    )
                    val results = StringBuilder()
                    cursor?.use {
                        var count = 0
                        while (it.moveToNext() && count < 5) {
                            val nameCol = it.getString(0)
                            val numCol = it.getString(1)
                            results.append("Name: $nameCol, Phone: $numCol\n")
                            count++
                        }
                    }
                    return@withContext if (results.isNotEmpty()) results.toString() else "No contacts found matching: $query"
                }

                "search_youtube" -> {
                    val query = args.optString("query", "")
                    try {
                        val intent = Intent(Intent.ACTION_SEARCH).apply {
                            setPackage("com.google.android.youtube")
                            putExtra("query", query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        return@withContext "Opened YouTube searching for $query"
                    } catch (e: Exception) {
                        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(webIntent)
                        return@withContext "Opened YouTube in browser for $query"
                    }
                }

                "search_web" -> {
                    val query = args.optString("query", "")
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    return@withContext "Opened Google Search for $query"
                }

                "open_whatsapp" -> {
                    val phone = args.optString("phone_number", "").replace("[^0-9+]".toRegex(), "")
                    val cleanPhone = if (phone.startsWith("+")) phone.substring(1) else phone
                    val msg = args.optString("message", "")
                    val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(msg)}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return@withContext "Opened WhatsApp chat for $cleanPhone"
                }

                "make_phone_call" -> {
                    val num = args.optString("phone_number", "").trim()
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return@withContext "Calling $num"
                }

                "send_sms" -> {
                    val num = args.optString("phone_number", "").trim()
                    val msg = args.optString("message", "")
                    @Suppress("DEPRECATION")
                    val sm = SmsManager.getDefault()
                    sm.sendTextMessage(num, null, msg, null, null)
                    return@withContext "SMS sent to $num"
                }

                "toggle_flashlight" -> {
                    val state = args.optBoolean("state", false)
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    if (cm != null && cm.cameraIdList.isNotEmpty()) {
                        cm.setTorchMode(cm.cameraIdList[0], state)
                        return@withContext "Flashlight set to ${if (state) "ON" else "OFF"}"
                    }
                    return@withContext "CameraManager unavailable."
                }

                "set_volume" -> {
                    val pct = args.optInt("level_percent", 50)
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null) {
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val target = Math.round(max * (Math.max(0, Math.min(100, pct)) / 100.0f))
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                        return@withContext "Volume set to $pct%"
                    }
                    return@withContext "AudioManager unavailable."
                }

                "navigate_system" -> {
                    val action = args.optString("action", "home").lowercase()
                    val service = VolumeTriggerService.instance ?: return@withContext "Accessibility Service not enabled."
                    val globalAction = when (action) {
                        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                        "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                        "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                        "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        else -> AccessibilityService.GLOBAL_ACTION_HOME
                    }
                    val ok = service.performGlobalAction(globalAction)
                    return@withContext if (ok) "Triggered $action" else "Action failed."
                }

                else -> return@withContext "Unknown tool: $name"
            }
        } catch (e: Exception) {
            return@withContext "Error executing tool: ${e.message}"
        }
    }
}
