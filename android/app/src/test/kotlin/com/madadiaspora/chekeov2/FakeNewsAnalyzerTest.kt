package com.madadiaspora.chekeov2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeNewsAnalyzerTest {

    @Test
    fun parsesCompleteFakeAnalysis() {
        val raw = """
            {
              "verdict": "fake",
              "confidence": 87,
              "summary": "Le titre est trompeur et l'image semble générée par IA.",
              "reasons": ["Aucune source citée", "Artefacts visuels évidents"],
              "media": "video",
              "text": {
                "present": true,
                "source": "Post Facebook",
                "verdict": "fake",
                "claims": [
                  {"claim": "Le vaccin modifie l'ADN", "assessment": "Faux, aucune preuve."}
                ]
              },
              "image": {
                "verdict": "fake",
                "ai_generated": true,
                "manipulated": false,
                "watermark": {"present": true, "name": "Gemini", "location": "bas droite"},
                "signals": ["Mains déformées", "Éclairage incohérent"]
              }
            }
        """.trimIndent()

        val analysis = FakeNewsAnalyzer.parse(raw)
        assertNotNull(analysis)
        analysis!!
        assertEquals("fake", analysis.verdict)
        assertEquals(87, analysis.confidence)
        assertEquals(2, analysis.reasons.size)
        assertEquals("video", analysis.media)
        assertTrue(analysis.textPresent)
        assertEquals("Post Facebook", analysis.textSource)
        assertEquals("fake", analysis.textVerdict)
        assertEquals(1, analysis.claims.size)
        assertEquals("fake", analysis.imageVerdict)
        assertTrue(analysis.aiGenerated)
        assertFalse(analysis.manipulated)
        assertTrue(analysis.watermarkPresent)
        assertEquals("Gemini", analysis.watermarkName)
        assertEquals("bas droite", analysis.watermarkLocation)
        assertEquals(2, analysis.imageSignals.size)
    }

    @Test
    fun watermarkHintDetectsKnownTools() {
        assertEquals("gemini", FakeNewsAnalyzer.watermarkHint("Image générée par Gemini"))
        assertEquals("midjourney", FakeNewsAnalyzer.watermarkHint("MIDJOURNEY v6"))
        assertEquals("", FakeNewsAnalyzer.watermarkHint("Aucun outil mentionné"))
    }

    @Test
    fun normalizesFrenchVerdictAndClampsConfidence() {
        val raw = """
            {"verdict":"Faux","confidence":150,"summary":"x","reasons":[],"text":{"present":true,"verdict":"Réel"},"image":{"verdict":"incertain"}}
        """.trimIndent()

        val analysis = FakeNewsAnalyzer.parse(raw)
        assertNotNull(analysis)
        assertEquals("fake", analysis!!.verdict)
        assertEquals(100, analysis.confidence)
        assertEquals("real", analysis.textVerdict)
        assertEquals("uncertain", analysis.imageVerdict)
    }

    @Test
    fun acceptsCodeFenceAndStringConfidence() {
        val raw = """
            ```json
            {"verdict":"uncertain","confidence":"42%","summary":"Peu d'éléments","reasons":["Contexte absent"]}
            ```
        """.trimIndent()

        val analysis = FakeNewsAnalyzer.parse(raw)
        assertNotNull(analysis)
        assertEquals("uncertain", analysis!!.verdict)
        assertEquals(42, analysis.confidence)
        assertFalse(analysis.textPresent)
        assertTrue(analysis.claims.isEmpty())
        assertTrue(analysis.imageSignals.isEmpty())
    }

    @Test
    fun ignoresClaimsWhenTextAbsent() {
        val raw = """
            {"verdict":"real","confidence":70,"summary":"Photo simple","reasons":[],"text":{"present":false,"source":"","verdict":"uncertain","claims":[{"claim":"x","assessment":"y"}]},"image":{"verdict":"real","signals":[]}}
        """.trimIndent()

        val analysis = FakeNewsAnalyzer.parse(raw)
        assertNotNull(analysis)
        assertFalse(analysis!!.textPresent)
        assertTrue(analysis.claims.isEmpty())
    }

    @Test
    fun truncatesLongFields() {
        val raw = """
            {"verdict":"fake","confidence":90,"summary":"${"a".repeat(300)}","reasons":["1","2","3","4","5"],"text":{"present":true,"source":"${"s".repeat(60)}","verdict":"fake","claims":[{"claim":"c1","assessment":"a1"},{"claim":"c2","assessment":"a2"},{"claim":"c3","assessment":"a3"}]},"image":{"verdict":"fake","signals":["1","2","3","4"]}}
        """.trimIndent()

        val analysis = FakeNewsAnalyzer.parse(raw)
        assertNotNull(analysis)
        analysis!!
        assertTrue(analysis.summary.length <= 130)
        assertEquals(2, analysis.reasons.size)
        assertEquals(1, analysis.claims.size)
        assertEquals(2, analysis.imageSignals.size)
        assertTrue(analysis.textSource.length <= 40)
    }

    @Test
    fun normalizesOcrText() {
        val normalized = FakeNewsAnalyzer.normalizeOcr(
            "  Bonjour   le monde \n\n  Bonjour le monde\nDeuxième   ligne\t ici \n"
        )
        assertEquals("Bonjour le monde\nDeuxième ligne ici", normalized)
    }

    @Test
    fun extractsSourceDomainsFromMessage() {
        val message = JSONObject(
            """
                {
                  "annotations": [
                    {"type": "url_citation", "url_citation": {"url": "https://www.lemonde.fr/article", "title": "Le Monde"}}
                  ],
                  "server_tool_calls": [
                    {"index": 0, "calls": [
                      {"index": 0, "sources": [
                        {"type": "url", "url": "https://factcheck.org/verif", "site_name": "FactCheck"},
                        {"type": "url", "url": "https://lemonde.fr/autre", "site_name": "Le Monde"}
                      ]}
                    ]}
                  ]
                }
            """.trimIndent()
        )
        val domains = FakeNewsAnalyzer.extractSourceDomains(message)
        assertEquals(listOf("lemonde.fr", "factcheck.org"), domains)
    }

    @Test
    fun parseHasNoWebSourcesByDefault() {
        val analysis = FakeNewsAnalyzer.parse(
            """{"verdict":"real","confidence":60,"summary":"ok","reasons":[],"media":"image"}"""
        )
        assertNotNull(analysis)
        assertTrue(analysis!!.sources.isEmpty())
    }

    @Test
    fun rejectsInvalidOrUnknownOutput() {
        assertNull(FakeNewsAnalyzer.parse("pas du json"))
        assertNull(FakeNewsAnalyzer.parse("""{"verdict":"peut-être"}"""))
    }
}
