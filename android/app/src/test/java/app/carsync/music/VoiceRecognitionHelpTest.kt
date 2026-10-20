package app.carsync.music

import org.junit.Assert.*
import org.junit.Test

class VoiceRecognitionHelpTest {
    @Test fun defaultsToFullEnglishLocaleInsteadOfLanguageOnlyTag() {
        assertEquals("en-US", VoiceLanguage.ENGLISH_US.tag)
        assertEquals("en-GB", VoiceLanguage.ENGLISH_UK.tag)
        assertNull(VoiceLanguage.DEVICE_DEFAULT.tag)
        assertEquals(VoiceLanguage.ENGLISH_UK, VoiceLanguage.ENGLISH_US.next())
        assertEquals(VoiceLanguage.DEVICE_DEFAULT, VoiceLanguage.ENGLISH_UK.next())
        assertEquals(VoiceLanguage.ENGLISH_US, VoiceLanguage.DEVICE_DEFAULT.next())
    }

    @Test fun code12OffersActionableLanguageAlternativesAndExplicitProviderConsent() {
        val msg=VoiceRecognitionHelp.describe(12, true, VoiceLanguage.ENGLISH_US)
        assertTrue(msg.contains("Language not supported"))
        assertTrue(msg.contains("English (UK)"))
        assertTrue(msg.contains("Device default"))
        assertTrue(msg.contains("speech provider"))
        assertFalse(msg.contains("failed (code 12)"))
    }

    @Test fun code13ExplainsMissingDownloadedModelInsteadOfGenericFailure() {
        assertTrue(VoiceRecognitionHelp.describe(13, true, VoiceLanguage.ENGLISH_US).contains("language pack"))
    }

    @Test fun errorsAreDistinguishable() {
        assertTrue(VoiceRecognitionHelp.describe(9, false, VoiceLanguage.DEVICE_DEFAULT).contains("permission"))
        assertTrue(VoiceRecognitionHelp.describe(8, true, VoiceLanguage.ENGLISH_US).contains("busy"))
        assertTrue(VoiceRecognitionHelp.describe(2, false, VoiceLanguage.ENGLISH_US).contains("Network"))
    }
}
