package io.heckel.ntfy.up

/**
 * Constants as defined on the specs
 * https://github.com/UnifiedPush/UP-spec/blob/main/specifications.md
 */

const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"

const val FEATURE_BYTES_MESSAGE = "org.unifiedpush.android.distributor.feature.BYTES_MESSAGE"

const val EXTRA_APPLICATION = "application"
const val EXTRA_PI = "pi"
const val EXTRA_TOKEN = "token"
const val EXTRA_ENDPOINT = "endpoint"
const val EXTRA_FAILED_REASON = "reason"
const val EXTRA_BYTES_MESSAGE = "bytesMessage"
