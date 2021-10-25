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

package io.heckel.ntfy.data

import android.content.res.Resources
import com.heckel.ntfy.R

/* Returns initial list of topics. */
fun topicList(resources: Resources): List<Topic> {
    return listOf(
        Topic(
            id = 1,
            url = resources.getString(R.string.topic1_name),
            description = resources.getString(R.string.topic1_description)
        ),
        Topic(
            id = 2,
            url = resources.getString(R.string.topic2_name),
            description = resources.getString(R.string.topic2_description)
        ),
    )
}
