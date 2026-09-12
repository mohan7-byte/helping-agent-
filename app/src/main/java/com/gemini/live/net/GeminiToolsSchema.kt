package com.gemini.live.net

import org.json.JSONArray
import org.json.JSONObject

object GeminiToolsSchema {

    fun getDeviceToolsJson(): JSONArray {
        val toolsArray = JSONArray()
        val declarations = JSONArray()

        // search_internet
        declarations.put(JSONObject().apply {
            put("name", "search_internet")
            put("description", "Silently fetch real-time live facts, sports scores, exam dates, or web answers in the background without opening the browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The live search query to look up on the web")
                    })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })

        // tap_element_id
        declarations.put(JSONObject().apply {
            put("name", "tap_element_id")
            put("description", "Directly tap a UI element by its candidate ID (#0, #1, #2...) obtained from read_screen_text. Has 100% pinpoint accuracy (0.0px error).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("element_id", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The numeric ID of the element to tap")
                    })
                })
                put("required", JSONArray().apply { put("element_id") })
            })
        })

        // long_press_element_id
        declarations.put(JSONObject().apply {
            put("name", "long_press_element_id")
            put("description", "Directly hold down/long-press on a candidate item/file by its ID (#0, #1, #2...) to select, delete, or trigger context menus (0.0px error).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("element_id", JSONObject().apply {
                        put("type", "INTEGER")
                        put("description", "The numeric ID of the element to hold")
                    })
                })
                put("required", JSONArray().apply { put("element_id") })
            })
        })

        // replace_text
        declarations.put(JSONObject().apply {
            put("name", "replace_text")
            put("description", "Erase existing text in the active editable field/contact and replace it cleanly with new text (zero appending errors).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The new text to replace the existing content with")
                    })
                })
                put("required", JSONArray().apply { put("text") })
            })
        })

        // clear_text
        declarations.put(JSONObject().apply {
            put("name", "clear_text")
            put("description", "Completely clear/delete all existing text in the active editable input field.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // get_device_info
        declarations.put(JSONObject().apply {
            put("name", "get_device_info")
            put("description", "Get real-time local time, date, battery level, network status, and device telemetry without opening the browser.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // save_app_rule
        declarations.put(JSONObject().apply {
            put("name", "save_app_rule")
            put("description", "Permanently save a newly learned UI button coordinate, playbook rule, or shortcut for an app so you remember it in all future sessions.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("app_package", JSONObject().apply { put("type", "STRING"); put("description", "Package name or identifier") })
                    put("action_name", JSONObject().apply { put("type", "STRING"); put("description", "Action identifier") })
                    put("x", JSONObject().apply { put("type", "INTEGER"); put("description", "Normalized X coordinate (0-1000)") })
                    put("y", JSONObject().apply { put("type", "INTEGER"); put("description", "Normalized Y coordinate (0-1000)") })
                    put("rule", JSONObject().apply { put("type", "STRING"); put("description", "Key lesson or tip to remember") })
                })
                put("required", JSONArray().apply { put("app_package"); put("action_name") })
            })
        })

        // create_note
        declarations.put(JSONObject().apply {
            put("name", "create_note")
            put("description", "Instantly create a note or document with the provided text body via Android intent (zero-failure).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "STRING"); put("description", "The note content") })
                })
                put("required", JSONArray().apply { put("text") })
            })
        })

        // type_text
        declarations.put(JSONObject().apply {
            put("name", "type_text")
            put("description", "Insert text directly into the active editable field or open note on screen (automatically handles soft keyboard and pastes at blinking cursor).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "STRING"); put("description", "The exact text to type or write") })
                })
                put("required", JSONArray().apply { put("text") })
            })
        })

        // tap_coordinates
        declarations.put(JSONObject().apply {
            put("name", "tap_coordinates")
            put("description", "Physically tap screen coordinates. Use normalized 0 to 1000 scale (where x=500, y=500 is the center of screen).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply { put("type", "INTEGER"); put("description", "X coordinate (0 to 1000)") })
                    put("y", JSONObject().apply { put("type", "INTEGER"); put("description", "Y coordinate (0 to 1000)") })
                })
                put("required", JSONArray().apply { put("x"); put("y") })
            })
        })

        // long_press
        declarations.put(JSONObject().apply {
            put("name", "long_press")
            put("description", "Hold down / long-press on coordinates (0 to 1000) for 700ms to select text, open context menus, or trigger special actions.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply { put("type", "INTEGER"); put("description", "X coordinate (0 to 1000)") })
                    put("y", JSONObject().apply { put("type", "INTEGER"); put("description", "Y coordinate (0 to 1000)") })
                })
                put("required", JSONArray().apply { put("x"); put("y") })
            })
        })

        // scroll
        declarations.put(JSONObject().apply {
            put("name", "scroll")
            put("description", "Scroll the screen up or down smoothly.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply { put("type", "STRING"); put("description", "down or up") })
                })
                put("required", JSONArray().apply { put("direction") })
            })
        })

        // swipe
        declarations.put(JSONObject().apply {
            put("name", "swipe")
            put("description", "Perform a directional swipe or drag from start to end coordinates (0 to 1000 scale).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("start_x", JSONObject().apply { put("type", "INTEGER") })
                    put("start_y", JSONObject().apply { put("type", "INTEGER") })
                    put("end_x", JSONObject().apply { put("type", "INTEGER") })
                    put("end_y", JSONObject().apply { put("type", "INTEGER") })
                    put("duration_ms", JSONObject().apply { put("type", "INTEGER") })
                })
                put("required", JSONArray().apply { put("start_x"); put("start_y"); put("end_x"); put("end_y") })
            })
        })

        // wait_seconds
        declarations.put(JSONObject().apply {
            put("name", "wait_seconds")
            put("description", "Pause execution for specified seconds (0.5 - 4.0s) to allow screens or apps to load.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("seconds", JSONObject().apply { put("type", "NUMBER") })
                })
                put("required", JSONArray().apply { put("seconds") })
            })
        })

        // read_screen_text
        declarations.put(JSONObject().apply {
            put("name", "read_screen_text")
            put("description", "Read and inspect all visible text, interactive buttons, inputs, and candidate IDs (#0, #1, #2...) currently on screen.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // capture_screen
        declarations.put(JSONObject().apply {
            put("name", "capture_screen")
            put("description", "Capture a real-time full display screenshot and inject it directly into your visual input feed.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        })

        // open_application
        declarations.put(JSONObject().apply {
            put("name", "open_application")
            put("description", "Launch any installed application by name (e.g. 'YouTube', 'Notes', 'WhatsApp', 'Chrome').")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("app_name", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("app_name") })
            })
        })

        // search_contacts
        declarations.put(JSONObject().apply {
            put("name", "search_contacts")
            put("description", "Search contacts by name to find phone numbers.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })

        // search_youtube
        declarations.put(JSONObject().apply {
            put("name", "search_youtube")
            put("description", "Search for a video on YouTube.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })

        // search_web
        declarations.put(JSONObject().apply {
            put("name", "search_web")
            put("description", "Open browser Google search.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("query") })
            })
        })

        // open_whatsapp
        declarations.put(JSONObject().apply {
            put("name", "open_whatsapp")
            put("description", "Open WhatsApp direct message with a phone number and prefilled text.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phone_number", JSONObject().apply { put("type", "STRING") })
                    put("message", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("phone_number") })
            })
        })

        // make_phone_call
        declarations.put(JSONObject().apply {
            put("name", "make_phone_call")
            put("description", "Directly dial and initiate a phone call.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phone_number", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("phone_number") })
            })
        })

        // send_sms
        declarations.put(JSONObject().apply {
            put("name", "send_sms")
            put("description", "Send an SMS message directly to a phone number.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("phone_number", JSONObject().apply { put("type", "STRING") })
                    put("message", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("phone_number"); put("message") })
            })
        })

        // toggle_flashlight
        declarations.put(JSONObject().apply {
            put("name", "toggle_flashlight")
            put("description", "Turn device flashlight on or off.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("state", JSONObject().apply { put("type", "BOOLEAN") })
                })
                put("required", JSONArray().apply { put("state") })
            })
        })

        // set_volume
        declarations.put(JSONObject().apply {
            put("name", "set_volume")
            put("description", "Set media playback volume percentage (0 to 100).")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("level_percent", JSONObject().apply { put("type", "INTEGER") })
                })
                put("required", JSONArray().apply { put("level_percent") })
            })
        })

        // navigate_system
        declarations.put(JSONObject().apply {
            put("name", "navigate_system")
            put("description", "Perform system navigation action: 'home', 'back', 'recents', 'notifications', 'quick_settings', 'lock'.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply { put("type", "STRING") })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        val container = JSONObject()
        container.put("functionDeclarations", declarations)
        toolsArray.put(container)
        return toolsArray
    }
}
