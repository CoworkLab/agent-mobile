package com.phoneagent.testbed

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The launcher, which is a list of the scenarios the testbed can reproduce.
 *
 * Plain `View`s rather than Compose, deliberately: an accessibility agent sees the view tree,
 * and a hand-built hierarchy is the closest thing to what a normal app presents. Compose's
 * semantics tree adds its own layer, which would make the testbed easier in a way real apps
 * are not.
 */
class TestbedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rows = listOf(
            "Chat (Send needs confirmation)" to ChatActivity::class.java,
            "Form with Submit" to FormActivity::class.java,
            "Editor (custom drawn, no nodes)" to EditorActivity::class.java,
            "Popup and ad" to PopupActivity::class.java,
            "Permission dialog" to PermissionDemoActivity::class.java,
            "Keyboard covers the field" to KeyboardActivity::class.java,
            "Slow load" to SlowLoadActivity::class.java,
            "Button that does nothing" to StuckActivity::class.java,
            "Secure screen" to SecureActivity::class.java,
            "Bank (denylisted)" to BankActivity::class.java,
            "Prompt injection page" to InjectionActivity::class.java,
            "Hindi input" to HindiActivity::class.java,
            "Long list to scroll" to LongListActivity::class.java,
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        column.addView(
            TextView(this).apply {
                text = "PhoneAgent Testbed"
                textSize = 22f
            },
        )

        rows.forEach { (label, activity) ->
            column.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener {
                        startActivity(Intent(this@TestbedActivity, activity))
                    }
                },
            )
        }

        setContentView(ScrollView(this).apply { addView(column) })
    }
}

/** Shared helpers, so each activity stays about its own difficulty. */
internal fun Activity.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(48, 48, 48, 48)
}

internal fun Activity.show(body: View) {
    setContentView(ScrollView(this).apply { addView(body) })
}

internal fun Activity.button(label: String, onClick: () -> Unit = {}): Button =
    Button(this).apply {
        text = label
        // The platform uppercases button labels by default, and that reaches the accessibility
        // tree: a scenario looking for "Target item 47" saw "TARGET ITEM 47". A scenario that
        // names something should find that name, so the label keeps the case it was written in.
        isAllCaps = false
        setOnClickListener { onClick() }
    }

internal fun Activity.label(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 16f
}

internal fun Activity.field(hint: String): EditText = EditText(this).apply {
    this.hint = hint
    textSize = 16f
}

internal fun Activity.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** A thin divider, so a custom-drawn screen still has something to look at. */
internal fun Activity.divider(): View = View(this).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
    setBackgroundColor(Color.LTGRAY)
}

/** A paragraph of filler, for the scroll scenario. */
internal fun Activity.paragraph(index: Int): TextView = TextView(this).apply {
    text = "Item $index: a row the agent may need to scroll to reach."
    textSize = 15f
    gravity = Gravity.START
}
