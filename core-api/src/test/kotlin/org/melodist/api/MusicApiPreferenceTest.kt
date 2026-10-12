package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.MusicPreferenceItem
import java.io.File

class MusicApiPreferenceTest {
    @Test
    fun testParseUserMusicPreference() {
        val jsonFile = File("../.tmp/experiments/preferences_set/baseline_api_response.json")
        if (!jsonFile.exists()) return
        val jsonStr = jsonFile.readText()

        val pref = parseUserMusicPreference(jsonStr)
        assertTrue((pref?.updateTime ?: 0L) > 1700000000L)
        assertFalse(pref?.isExpired == true)
        assertEquals(50, pref?.familiarityScore)
        assertEquals(50, pref?.emotionScore)

        val covers = pref?.scores?.firstOrNull { it.profileKey == MusicPreferenceItem.KEY_COVERS }
        assertNotNull(covers)
        assertEquals("翻唱&现场", covers?.profileName)
        assertEquals(50, covers?.currentScore)

        val cantonese = pref?.scores?.firstOrNull { it.profileKey == MusicPreferenceItem.KEY_CANTONESE }
        assertNotNull(cantonese)
        assertEquals("粤语", cantonese?.profileName)
        assertEquals(50, cantonese?.currentScore)
    }
}
