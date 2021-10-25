/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.heckel.ntfy.add

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import io.heckel.ntfy.R
import com.google.android.material.textfield.TextInputEditText

const val TOPIC_URL = "url"

class AddTopicActivity : AppCompatActivity() {
    private lateinit var addTopicUrl: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add_topic_layout)

        findViewById<Button>(R.id.subscribe_button).setOnClickListener {
            addTopic()
        }
        addTopicUrl = findViewById(R.id.add_topic_url)
        addTopicUrl.setText("https://ntfy.sh/")
    }

    /* The onClick action for the done button. Closes the activity and returns the new topic name
    and description as part of the intent. If the name or description are missing, the result is set
    to cancelled. */

    private fun addTopic() {
        val resultIntent = Intent()

        if (addTopicUrl.text.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED, resultIntent)
        } else {
            val url = addTopicUrl.text.toString()
            resultIntent.putExtra(TOPIC_URL, url)
            setResult(Activity.RESULT_OK, resultIntent)
        }
        finish()
    }
}
