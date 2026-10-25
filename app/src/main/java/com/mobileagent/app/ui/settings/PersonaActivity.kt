package com.mobileagent.app.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import com.mobileagent.app.AgentApp
import com.mobileagent.app.data.model.GeminiConstants
import com.mobileagent.app.databinding.ActivityPersonaBinding
import com.mobileagent.app.util.ThemeManager

class PersonaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonaBinding
    private val preferences by lazy { AgentApp.instance.preferences }

    private var currentSelectedPersona = GeminiConstants.PERSONALITY_ASSISTANT

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPersonaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        setupUI()
        loadInitialState()
        setupListeners()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadInitialState() {
        val current = preferences.personality
        val initialPersona = when (current) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> GeminiConstants.PERSONALITY_GIRLFRIEND
            GeminiConstants.PERSONALITY_BOYFRIEND -> GeminiConstants.PERSONALITY_BOYFRIEND
            GeminiConstants.PERSONALITY_PRO -> GeminiConstants.PERSONALITY_PRO
            else -> GeminiConstants.PERSONALITY_ASSISTANT
        }
        selectPersona(initialPersona, updateVoiceDefault = false)

        binding.etUserName.setText(preferences.userName.ifBlank { "Pro" })

        selectVoice(preferences.voice)
        updatePersonaPreview()
        binding.scrollPersona.post { binding.scrollPersona.scrollTo(0, 0) }
    }

    private fun setupListeners() {
        binding.etUserName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePersonaPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.cardPersonaAgent.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_ASSISTANT, updateVoiceDefault = true)
        }

        binding.cardPersonaGirlfriend.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_GIRLFRIEND, updateVoiceDefault = true)
        }

        binding.cardPersonaBoyfriend.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_BOYFRIEND, updateVoiceDefault = true)
        }

        binding.cardPersonaPro.setOnClickListener {
            selectPersona(GeminiConstants.PERSONALITY_PRO, updateVoiceDefault = true)
        }

        binding.btnActivatePersona.setOnClickListener {
            saveAndActivate()
        }
    }

    private fun selectPersona(personaKey: String, updateVoiceDefault: Boolean) {
        currentSelectedPersona = personaKey

        binding.rbSelectAgent.isChecked = (personaKey == GeminiConstants.PERSONALITY_ASSISTANT)
        binding.rbSelectGirlfriend.isChecked = (personaKey == GeminiConstants.PERSONALITY_GIRLFRIEND)
        binding.rbSelectBoyfriend.isChecked = (personaKey == GeminiConstants.PERSONALITY_BOYFRIEND)
        binding.rbSelectPro.isChecked = (personaKey == GeminiConstants.PERSONALITY_PRO)

        if (updateVoiceDefault) {
            when (personaKey) {
                GeminiConstants.PERSONALITY_GIRLFRIEND -> selectVoice("Aoede")
                GeminiConstants.PERSONALITY_BOYFRIEND -> selectVoice("Fenrir")
                GeminiConstants.PERSONALITY_PRO -> selectVoice("Puck")
                else -> selectVoice("Charon")
            }
        }
        if (::binding.isInitialized) updatePersonaPreview()
    }

    private fun updatePersonaPreview() {
        if (!::binding.isInitialized) return
        val persona = when (currentSelectedPersona) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> "Pari"
            GeminiConstants.PERSONALITY_BOYFRIEND -> "Kabir"
            GeminiConstants.PERSONALITY_PRO -> "Pro"
            else -> "Agent Pro"
        }
        val operator = binding.etUserName.text?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: "Pro"
        binding.tvPersonaPreview.text = "$persona · ${getSelectedVoice()}"
        binding.tvPersonaGreeting.text = "Ready to help, $operator."
    }

    private fun selectVoice(voiceName: String) {
        when (voiceName.lowercase()) {
            "aoede" -> binding.rbVoiceAoede.isChecked = true
            "kore" -> binding.rbVoiceKore.isChecked = true
            "fenrir" -> binding.rbVoiceFenrir.isChecked = true
            "puck" -> binding.rbVoicePuck.isChecked = true
            "charon" -> binding.rbVoiceCharon.isChecked = true
            else -> binding.rbVoicePuck.isChecked = true
        }
    }

    private fun getSelectedVoice(): String {
        return when {
            binding.rbVoiceAoede.isChecked -> "Aoede"
            binding.rbVoiceKore.isChecked -> "Kore"
            binding.rbVoiceFenrir.isChecked -> "Fenrir"
            binding.rbVoiceCharon.isChecked -> "Charon"
            else -> "Puck"
        }
    }

    private fun saveAndActivate() {
        val userName = binding.etUserName.text?.toString()?.trim()
        if (!userName.isNullOrBlank()) {
            preferences.userName = userName
        } else {
            preferences.userName = "Pro"
        }

        val voice = getSelectedVoice()
        preferences.voice = voice
        preferences.personality = currentSelectedPersona

        val personaDisplayName = when (currentSelectedPersona) {
            GeminiConstants.PERSONALITY_GIRLFRIEND -> "Pari (Girlfriend Mode)"
            GeminiConstants.PERSONALITY_BOYFRIEND -> "Kabir (Boyfriend Mode)"
            GeminiConstants.PERSONALITY_PRO -> "Pro Neural Core"
            else -> "Agent Pro"
        }

        Toast.makeText(
            this,
            "Activated: $personaDisplayName with $voice Voice",
            Toast.LENGTH_LONG
        ).show()

        finish()
    }
}
