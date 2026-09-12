package com.gemini.live.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import com.gemini.live.R
import com.gemini.live.databinding.DialogSettingsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

class SettingsDialog(
    private val onSaved: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val models = arrayOf(
        "models/gemini-2.0-flash-exp",
        "models/gemini-2.0-flash",
        "models/gemini-2.5-flash",
        "models/gemini-3.1-flash-live-preview"
    )

    private val voices = arrayOf(
        "Aoede", "Puck", "Charon", "Fenrir", "Kore"
    )

    override fun getTheme(): Int = R.style.Theme_Voice_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)

        // Spinners
        binding.modelSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            models
        )
        binding.voiceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            voices
        )

        // Load Preferences
        binding.apiKeyInput.setText(prefs.getString("api_key", ""))
        binding.systemPromptInput.setText(prefs.getString("system_prompt", ""))
        binding.autoDisconnectToggle.isChecked = prefs.getBoolean("auto_disconnect", true)
        binding.autoConnectToggle.isChecked = prefs.getBoolean("auto_connect", true)
        binding.echoGuardToggle.isChecked = prefs.getBoolean("echo_guard", true)
        binding.btPriorityToggle.isChecked = prefs.getBoolean("bt_priority", true)

        val savedModel = prefs.getString("model", models[0])
        val modelIdx = models.indexOf(savedModel)
        if (modelIdx >= 0) binding.modelSpinner.setSelection(modelIdx)

        val savedVoice = prefs.getString("voice", voices[0])
        val voiceIdx = voices.indexOf(savedVoice)
        if (voiceIdx >= 0) binding.voiceSpinner.setSelection(voiceIdx)

        // Load Rules JSON from Documents/Voice/rules.json (or prefs/fallback)
        val rulesPrefs = requireContext().getSharedPreferences("jarvis_rules", Context.MODE_PRIVATE)
        val docsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        val publicRulesFile = File(File(docsDir, "Voice"), "rules.json")
        val fallbackRulesFile = File(requireContext().getExternalFilesDir(null), "rules.json")

        var loadedRules = ""
        if (publicRulesFile.exists() && publicRulesFile.length() > 0) {
            try { loadedRules = publicRulesFile.readText() } catch (ignored: Exception) {}
        }
        if (loadedRules.isEmpty() && fallbackRulesFile.exists() && fallbackRulesFile.length() > 0) {
            try { loadedRules = fallbackRulesFile.readText() } catch (ignored: Exception) {}
        }
        if (loadedRules.isEmpty()) {
            loadedRules = rulesPrefs.getString("rules", "") ?: ""
        }
        binding.rulesEditor.setText(loadedRules)

        // Handlers
        binding.closeSheetBtn.setOnClickListener { dismiss() }

        binding.accessibilityCard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.saveRulesBtn.setOnClickListener {
            try {
                val text = binding.rulesEditor.text.toString().trim()
                org.json.JSONObject(text) // Validate JSON
                rulesPrefs.edit().putString("rules", text).apply()

                var savedPath = ""
                try {
                    publicRulesFile.parentFile?.mkdirs()
                    publicRulesFile.writeText(text)
                    savedPath = publicRulesFile.absolutePath
                } catch (e: Exception) {
                    fallbackRulesFile.parentFile?.mkdirs()
                    fallbackRulesFile.writeText(text)
                    savedPath = fallbackRulesFile.absolutePath
                }

                Toast.makeText(requireContext(), "Saved to Documents/Voice/rules.json", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Invalid JSON: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.openFileManagerBtn.setOnClickListener {
            try {
                val targetFile = if (publicRulesFile.exists()) publicRulesFile else fallbackRulesFile
                if (!targetFile.exists()) {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(binding.rulesEditor.text.toString().ifEmpty { "{}" })
                }

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    val uri = Uri.parse(targetFile.parentFile?.absolutePath ?: targetFile.absolutePath)
                    setDataAndType(Uri.fromFile(targetFile), "application/json")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: Open system file manager or show full path
                try {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {
                    Toast.makeText(requireContext(), "Location: Documents/Voice/rules.json", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        // Auto-save settings on dismiss
        val prefs = requireContext().getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)
        _binding?.let { b ->
            prefs.edit()
                .putString("api_key", b.apiKeyInput.text.toString().trim())
                .putString("system_prompt", b.systemPromptInput.text.toString().trim())
                .putBoolean("auto_disconnect", b.autoDisconnectToggle.isChecked)
                .putBoolean("auto_connect", b.autoConnectToggle.isChecked)
                .putBoolean("echo_guard", b.echoGuardToggle.isChecked)
                .putBoolean("bt_priority", b.btPriorityToggle.isChecked)
                .putString("model", models.getOrNull(b.modelSpinner.selectedItemPosition) ?: models[0])
                .putString("voice", voices.getOrNull(b.voiceSpinner.selectedItemPosition) ?: voices[0])
                .apply()
        }
        onSaved()
        _binding = null
        super.onDestroyView()
    }
}
