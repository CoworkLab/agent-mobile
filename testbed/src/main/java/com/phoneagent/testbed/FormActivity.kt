package com.phoneagent.testbed

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/**
 * A three-field form with a Submit button.
 *
 * Two scenarios hang off this screen: filling all three fields in one `batch_actions` call, and
 * the abort case where tapping the second field pops a dialog mid-batch. The dialog appears
 * only on the second field, so a batch that starts there will be interrupted part-way — which
 * is exactly what the abort test needs.
 */
class FormActivity : Activity() {

    private var interferenceEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = column()
        column.addView(label("Sign-up form"))

        val name = field("Full name")
        val email = field("Email")
        val phone = field("Phone")

        column.addView(name)
        column.addView(email)
        column.addView(phone)

        // A checkbox rather than another text field, so the screen has more than one kind of
        // control for the agent to reason about.
        column.addView(
            android.widget.CheckBox(this).apply {
                text = "Send me offers"
            },
        )

        column.addView(
            button("Submit") {
                toast("Submitted ${name.text} / ${email.text} / ${phone.text}")
            },
        )

        show(column)
    }

    /** Kept for the mid-batch interference scenario; the launcher opens the plain form. */
    fun enableInterference(enabled: Boolean) {
        interferenceEnabled = enabled
    }
}

/**
 * A form variant where a layout change interrupts a batch.
 *
 * Separate from [FormActivity] so the plain form stays stable: a test that filled the stable
 * form while the interfering one also existed would be ambiguous about which it exercised.
 */
class InterferingFormActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = column()
        column.addView(label("Form that changes under you"))

        val first = field("First field")
        val second = field("Second field")
        val third = field("Third field")

        // Tapping the second field inserts a banner above the third, which moves every control
        // below it. A batch that resolved its targets up front will now be aiming at the wrong
        // places, and the whole batch must stop rather than type into whatever moved there.
        second.setOnFocusChangeListener { _, focused ->
            if (focused && column.findViewById<android.widget.TextView>(BANNER_ID) == null) {
                column.addView(
                    label("Special offer! Sign up now.").apply { id = BANNER_ID },
                    2,
                )
            }
        }

        column.addView(first)
        column.addView(second)
        column.addView(third)
        column.addView(button("Submit") { toast("Submitted") })

        show(column)
    }

    private companion object {
        const val BANNER_ID = 0x7f0f0001
    }
}
