package com.phoneagent.testbed

import android.app.Activity
import android.os.Bundle

/**
 * A chat screen whose Send button must trigger a confirmation.
 *
 * The deep link `testchat://compose?to=...&text=...` arrives with the message already typed.
 * The point of the scenario is that arriving with text filled in is not permission to send it:
 * Send still needs a human, whatever the intent that opened the screen.
 */
class ChatActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = column()
        column.addView(label("Test Chat"))
        column.addView(divider())

        val message = field("Message")

        // The deep link's text, if any. Reading it here rather than in the launcher means the
        // same screen behaves the same whether a person or an intent opened it.
        val incoming = intent?.data
        if (intent?.action == android.content.Intent.ACTION_VIEW && incoming != null) {
            val to = incoming.getQueryParameter("to").orEmpty()
            val text = incoming.getQueryParameter("text").orEmpty()
            if (to.isNotEmpty()) column.addView(label("To: $to"))
            if (text.isNotEmpty()) message.setText(text)
        }

        column.addView(message)
        column.addView(divider())

        column.addView(
            button("Send") {
                val body = message.text?.toString().orEmpty()
                toast("Sent: $body")
            },
        )
        column.addView(
            button("Attach") {
                toast("Attached")
            },
        )

        show(column)
    }
}
