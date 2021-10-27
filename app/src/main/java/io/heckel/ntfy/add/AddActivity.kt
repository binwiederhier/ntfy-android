package io.heckel.ntfy.add

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import io.heckel.ntfy.R
import io.heckel.ntfy.TOPIC_BASE_URL
import io.heckel.ntfy.TOPIC_NAME

class AddTopicActivity : AppCompatActivity() {
    private lateinit var topicName: TextInputEditText
    private lateinit var baseUrl: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_topic_layout)

        findViewById<Button>(R.id.subscribe_button).setOnClickListener {
            addTopic()
        }
        topicName = findViewById(R.id.add_topic_name)
        baseUrl = findViewById(R.id.add_topic_base_url)
        baseUrl.setText(R.string.topic_base_url_default_value)
    }

    /* The onClick action for the done button. Closes the activity and returns the new topic name
    and description as part of the intent. If the name or description are missing, the result is set
    to cancelled. */

    private fun addTopic() {
        val resultIntent = Intent()

        // TODO don't allow this

        if (baseUrl.text.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED, resultIntent)
        } else {
            resultIntent.putExtra(TOPIC_NAME, topicName.text.toString())
            resultIntent.putExtra(TOPIC_BASE_URL, baseUrl.text.toString())
            setResult(Activity.RESULT_OK, resultIntent)
        }
        finish()
    }
}
