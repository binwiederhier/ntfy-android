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

package io.heckel.ntfy.detail

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import io.heckel.ntfy.R
import io.heckel.ntfy.TOPIC_ID
import io.heckel.ntfy.SubscriptionViewModel
import io.heckel.ntfy.SubscriptionsViewModelFactory
import io.heckel.ntfy.data.topicShortUrl

class DetailActivity : AppCompatActivity() {
    private val subscriptionsViewModel by viewModels<SubscriptionViewModel> {
        SubscriptionsViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.topic_detail_activity)

        var subscriptionId: Long? = null

        /* Connect variables to UI elements. */
        val topicText: TextView = findViewById(R.id.topic_detail_url)
        val removeButton: Button = findViewById(R.id.remove_button)

        val bundle: Bundle? = intent.extras
        if (bundle != null) {
            subscriptionId = bundle.getLong(TOPIC_ID)
        }

        // TODO This should probably fail hard if topicId is null

        /* If currentTopicId is not null, get corresponding topic and set name, image and
        description */
        subscriptionId?.let {
            val subscription = subscriptionsViewModel.get(it)
            topicText.text = subscription?.let { s -> topicShortUrl(s) }

            removeButton.setOnClickListener {
                if (subscription != null) {
                    subscriptionsViewModel.remove(subscription)
                }
                finish()
            }
        }
    }
}
