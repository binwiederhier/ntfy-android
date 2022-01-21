package io.heckel.ntfy.util

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import io.heckel.ntfy.log.Log

// Open the in-app rate dialog, see https://developer.android.com/guide/playcore/in-app-review/kotlin-java
fun rateApp(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    val request = manager.requestReviewFlow()
    request.addOnCompleteListener { task ->
        if (task.isSuccessful) {
            manager.launchReviewFlow(activity, task.result)
        }
    }
}
