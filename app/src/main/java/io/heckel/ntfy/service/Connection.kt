package io.heckel.ntfy.service

interface Connection {
    fun start()
    fun close()
    fun since(): Long
    fun matches(otherSubscriptionIds: Collection<Long>): Boolean
}
