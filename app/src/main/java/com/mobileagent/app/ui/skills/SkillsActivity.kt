package com.mobileagent.app.ui.skills

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mobileagent.app.AgentApp
import com.mobileagent.app.R
import com.mobileagent.app.data.memory.SkillEntity
import com.mobileagent.app.data.skills.stepList
import com.mobileagent.app.data.skills.triggerList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists every skill (bundled pre-trained patterns + learned ones). Tap toggles enabled,
 * long-press deletes a learned skill. Read-only for built-ins (they refresh from the APK).
 */
class SkillsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mobileagent.app.util.ThemeManager.applyTheme(this)
        setContentView(R.layout.activity_skills)
        container = findViewById(R.id.skillsContainer)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val app = application as AgentApp
        lifecycleScope.launch(Dispatchers.IO) {
            val skills = try {
                app.memoryDatabase.skillDao().getAll()
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) { render(skills) }
        }
    }

    private fun render(skills: List<SkillEntity>) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        if (skills.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No skills yet. The agent learns patterns you teach it (\"aage se aise karna...\")."
                setPadding((16 * density).toInt(), (32 * density).toInt(), (16 * density).toInt(), 0)
                setTextColor(ContextCompat.getColor(this@SkillsActivity, R.color.accent_sapphire))
            }
            container.addView(empty)
            return
        }
        for (skill in skills) {
            container.addView(rowFor(skill, density))
            container.addView(divider(density))
        }
    }

    private fun rowFor(skill: SkillEntity, density: Float): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            isClickable = true
            isFocusable = true
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = "${skill.name}  •  ${skill.source.uppercase()}"
            setTextColor(
                if (skill.enabled) ContextCompat.getColor(context, R.color.accent_sapphire)
                else Color.GRAY
            )
            textSize = 15f
            fontFamily = "sans-serif-medium"
        }
        texts.addView(title)

        val triggers = skill.triggerList().take(3).joinToString(", ")
        val detail = TextView(this).apply {
            text = "${skill.stepList().size} steps${if (triggers.isNotBlank()) " • \"$triggers\"" else ""}" +
                (if (skill.enabled) "" else " • DISABLED")
            setTextColor(Color.GRAY)
            textSize = 12f
        }
        texts.addView(detail)

        row.addView(texts)

        val toggle = ImageView(this).apply {
            setImageResource(
                if (skill.enabled) android.R.drawable.checkbox_on_background
                else android.R.drawable.checkbox_off_background
            )
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(), (24 * density).toInt()
            ).apply { marginStart = (12 * density).toInt() }
            contentDescription = if (skill.enabled) "Disable skill" else "Enable skill"
        }
        row.addView(toggle)

        val app = application as AgentApp
        row.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                app.memoryDatabase.skillDao().setEnabled(skill.id, !skill.enabled)
                withContext(Dispatchers.Main) { reload() }
            }
        }
        if (skill.source == SkillEntity.SOURCE_LEARNED) {
            row.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Delete skill?")
                    .setMessage("Delete learned skill '${skill.name}' permanently?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            app.memoryDatabase.skillDao().deleteById(skill.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@SkillsActivity, "Skill deleted", Toast.LENGTH_SHORT).show()
                                reload()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        return row
    }

    private fun divider(density: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (1 * density).toInt())
        ).apply { setMargins(0, 0, 0, 0) }
        setBackgroundColor(0x22888888)
    }
}
