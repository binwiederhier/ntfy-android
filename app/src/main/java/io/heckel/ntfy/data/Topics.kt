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

/* Returns initial list of flowers. */
fun flowerList(resources: Resources): List<Topic> {
    return listOf(
        Topic(
            id = 1,
            name = resources.getString(R.string.flower1_name),
            image = R.drawable.rose,
            description = resources.getString(R.string.flower1_description)
        ),
        Topic(
            id = 2,
            name = resources.getString(R.string.flower2_name),
            image = R.drawable.freesia,
            description = resources.getString(R.string.flower2_description)
        ),
        Topic(
            id = 3,
            name = resources.getString(R.string.flower3_name),
            image = R.drawable.lily,
            description = resources.getString(R.string.flower3_description)
        ),
        Topic(
            id = 4,
            name = resources.getString(R.string.flower4_name),
            image = R.drawable.sunflower,
            description = resources.getString(R.string.flower4_description)
        ),
        Topic(
            id = 5,
            name = resources.getString(R.string.flower5_name),
            image = R.drawable.peony,
            description = resources.getString(R.string.flower5_description)
        ),
        Topic(
            id = 6,
            name = resources.getString(R.string.flower6_name),
            image = R.drawable.daisy,
            description = resources.getString(R.string.flower6_description)
        ),
        Topic(
            id = 7,
            name = resources.getString(R.string.flower7_name),
            image = R.drawable.lilac,
            description = resources.getString(R.string.flower7_description)
        ),
        Topic(
            id = 8,
            name = resources.getString(R.string.flower8_name),
            image = R.drawable.marigold,
            description = resources.getString(R.string.flower8_description)
        ),
        Topic(
            id = 9,
            name = resources.getString(R.string.flower9_name),
            image = R.drawable.poppy,
            description = resources.getString(R.string.flower9_description)
        ),
        Topic(
            id = 10,
            name = resources.getString(R.string.flower10_name),
            image = R.drawable.daffodil,
            description = resources.getString(R.string.flower10_description)
        ),
        Topic(
            id = 11,
            name = resources.getString(R.string.flower11_name),
            image = R.drawable.dahlia,
            description = resources.getString(R.string.flower11_description)
        )
    )
}
