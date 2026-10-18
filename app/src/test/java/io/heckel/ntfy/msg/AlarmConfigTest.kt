package io.heckel.ntfy.msg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmConfigTest {
    @Test
    fun parse_noTriggerTag_returnsNull() {
        assertNull(parseAlarmConfig(null))
        assertNull(parseAlarmConfig(""))
        assertNull(parseAlarmConfig("warning,skull"))
        assertNull(parseAlarmConfig("sound=Cesium,timeout=60")) // options without trigger
        assertNull(parseAlarmConfig("Fullscreen")) // case-sensitive
    }

    @Test
    fun parse_triggerOnly_returnsDefaults() {
        val config = parseAlarmConfig("fullscreen")!!
        assertEquals(ALARM_SOUND_DEFAULT, config.sound)
        assertTrue(config.vibrate)
        assertNull(config.timeoutSeconds)
        assertEquals(ALARM_DEFAULT_SNOOZE_MINUTES, config.snoozeMinutes)
    }

    @Test
    fun parse_allOptions() {
        val config = parseAlarmConfig("fullscreen,sound=Cesium,vibrate=0,timeout=120,snooze=15,volume=80")!!
        assertEquals("Cesium", config.sound)
        assertFalse(config.vibrate)
        assertEquals(120, config.timeoutSeconds)
        assertEquals(15, config.snoozeMinutes)
        assertEquals(80, config.volumePercent)
    }

    @Test
    fun parse_volumeDefaultsToNull() {
        assertNull(parseAlarmConfig("fullscreen")!!.volumePercent)
    }

    @Test
    fun parse_volumeZeroIsValid() {
        // Symmetric with sound=none: the publisher may deliberately ring silently.
        assertEquals(0, parseAlarmConfig("fullscreen,volume=0")!!.volumePercent)
    }

    @Test
    fun parse_volumeOutOfRangeOrMalformedIgnored() {
        assertNull(parseAlarmConfig("fullscreen,volume=101")!!.volumePercent)
        assertNull(parseAlarmConfig("fullscreen,volume=-1")!!.volumePercent)
        assertNull(parseAlarmConfig("fullscreen,volume=loud")!!.volumePercent)
        assertNull(parseAlarmConfig("fullscreen,volume=")!!.volumePercent)
    }

    @Test
    fun parse_triggerAmongOtherTags() {
        val config = parseAlarmConfig("warning, fullscreen ,sound=none")!!
        assertEquals("none", config.sound)
    }

    @Test
    fun parse_vibrateVariants() {
        assertFalse(parseAlarmConfig("fullscreen,vibrate=false")!!.vibrate)
        assertFalse(parseAlarmConfig("fullscreen,vibrate=NO")!!.vibrate)
        assertTrue(parseAlarmConfig("fullscreen,vibrate=1")!!.vibrate)
        assertTrue(parseAlarmConfig("fullscreen,vibrate=banana")!!.vibrate) // malformed -> default
        assertTrue(parseAlarmConfig("fullscreen,vibrate=")!!.vibrate)
    }

    @Test
    fun parse_malformedNumbersIgnored() {
        val config = parseAlarmConfig("fullscreen,timeout=abc,snooze=-5")!!
        assertNull(config.timeoutSeconds)
        assertEquals(ALARM_DEFAULT_SNOOZE_MINUTES, config.snoozeMinutes)
    }

    @Test
    fun parse_outOfRangeNumbersIgnored() {
        val config = parseAlarmConfig("fullscreen,timeout=0,snooze=100000")!!
        assertNull(config.timeoutSeconds)
        assertEquals(ALARM_DEFAULT_SNOOZE_MINUTES, config.snoozeMinutes)
    }

    @Test
    fun parse_lastDuplicateWins() {
        val config = parseAlarmConfig("fullscreen,timeout=30,timeout=60")!!
        assertEquals(60, config.timeoutSeconds)
    }

    @Test
    fun parse_valueWithEqualsSign() {
        val config = parseAlarmConfig("fullscreen,sound=a=b")!!
        assertEquals("a=b", config.sound)
    }

    @Test
    fun parse_emptySoundFallsBackToDefault() {
        val config = parseAlarmConfig("fullscreen,sound=")!!
        assertEquals(ALARM_SOUND_DEFAULT, config.sound)
    }

    @Test
    fun visibleTags_hidesConfigTagsOnAlarmMessages() {
        val tags = listOf("warning", "fullscreen", "sound=Cesium", "vibrate=0", "timeout=60", "snooze=5")
        assertEquals(listOf("warning"), visibleTags(tags))
    }

    @Test
    fun visibleTags_keepsEverythingOnNormalMessages() {
        val tags = listOf("warning", "sound=Cesium") // no trigger -> "sound=" is just a tag
        assertEquals(tags, visibleTags(tags))
    }

    @Test
    fun isAlarmConfigTag_matchesVocabulary() {
        assertTrue(isAlarmConfigTag("fullscreen"))
        assertTrue(isAlarmConfigTag("sound=x"))
        assertTrue(isAlarmConfigTag("vibrate=0"))
        assertTrue(isAlarmConfigTag("timeout=60"))
        assertTrue(isAlarmConfigTag("snooze=5"))
        assertFalse(isAlarmConfigTag("warning"))
        assertFalse(isAlarmConfigTag("fullscreen2"))
        assertFalse(isAlarmConfigTag("soundcheck"))
    }
}
