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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/* Handles operations on topicsLiveData and holds details about it. */
class DataSource(resources: Resources) {
    private val topicsLiveData: MutableLiveData<List<Topic>> = MutableLiveData(mutableListOf())

    /* Adds topic to liveData and posts value. */
    fun add(topic: Topic) {
        val currentList = topicsLiveData.value
        if (currentList == null) {
            topicsLiveData.postValue(listOf(topic))
        } else {
            val updatedList = currentList.toMutableList()
            updatedList.add(0, topic)
            topicsLiveData.postValue(updatedList)
        }
    }

    /* Removes topic from liveData and posts value. */
    fun remove(topic: Topic) {
        val currentList = topicsLiveData.value
        if (currentList != null) {
            val updatedList = currentList.toMutableList()
            updatedList.remove(topic)
            topicsLiveData.postValue(updatedList)
        }
    }

    /* Returns topic given an ID. */
    fun get(id: Long): Topic? {
        topicsLiveData.value?.let { topics ->
            return topics.firstOrNull{ it.id == id}
        }
        return null
    }

    fun getTopicList(): LiveData<List<Topic>> {
        return topicsLiveData
    }

    companion object {
        private var instance: DataSource? = null

        fun getDataSource(resources: Resources): DataSource {
            return synchronized(DataSource::class) {
                val newInstance = instance ?: DataSource(resources)
                instance = newInstance
                newInstance
            }
        }
    }
}
