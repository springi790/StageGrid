package dev.stagegrid.guide

import dev.stagegrid.guide.GuidePackManager.CueKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangementGuideSequencePlannerTest {
    @Test
    fun select_keepsSectionCountAndDynamicCallsFromDestinationLeadBar() {
        val analysis = GuideCueAnalyzer.Result(
            cues = listOf(
                GuideCueAnalyzer.DetectedCue("verse", CueKind.SECTION, "en", 2_000, 0.99f),
                GuideCueAnalyzer.DetectedCue("chorus", CueKind.SECTION, "en", 6_000, 0.99f),
                GuideCueAnalyzer.DetectedCue("2", CueKind.COUNT, "en", 6_850, 0.98f),
                GuideCueAnalyzer.DetectedCue("3", CueKind.COUNT, "en", 7_350, 0.98f),
                GuideCueAnalyzer.DetectedCue("all_in", CueKind.DYNAMIC, "en", 7_700, 0.97f),
            ),
            dominantLanguage = "en",
            candidateCount = 5,
        )

        val cues = ArrangementGuideSequencePlanner.select(
            analysis = analysis,
            targetSectionStartMs = 8_000,
            targetSectionKey = "chorus",
            barDurationMs = 2_000.0,
        )

        assertEquals(listOf("chorus", "2", "3", "4", "all_in"), cues.map { it.key })
        assertEquals(
            listOf(CueKind.SECTION, CueKind.COUNT, CueKind.COUNT, CueKind.COUNT, CueKind.DYNAMIC),
            cues.map { it.kind },
        )
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L, 1_700L), cues.map { it.offsetMs })
        assertTrue(cues.last().offsetMs < 2_000L)
    }

    @Test
    fun select_completesTwoThreeFourWhenAnalysisOnlyDetectedTwo() {
        val analysis = GuideCueAnalyzer.Result(
            cues = listOf(
                GuideCueAnalyzer.DetectedCue("chorus", CueKind.SECTION, "es", 6_000, 0.99f),
                GuideCueAnalyzer.DetectedCue("2", CueKind.COUNT, "es", 6_900, 0.95f),
            ),
            dominantLanguage = "es",
            candidateCount = 2,
        )

        val cues = ArrangementGuideSequencePlanner.select(
            analysis = analysis,
            targetSectionStartMs = 8_000,
            targetSectionKey = "chorus",
            barDurationMs = 2_000.0,
        )

        val counts = cues.filter { it.kind == CueKind.COUNT }
        assertEquals(listOf("2", "3", "4"), counts.map { it.key })
        assertEquals(listOf(500L, 1_000L, 1_500L), counts.map { it.offsetMs })
        assertTrue(counts.all { it.language == "es" })
    }

    @Test
    fun select_doesNotInventCountingWhenTransitionHadNoCount() {
        val analysis = GuideCueAnalyzer.Result(
            cues = listOf(
                GuideCueAnalyzer.DetectedCue("bridge", CueKind.SECTION, "en", 6_000, 0.99f),
                GuideCueAnalyzer.DetectedCue("build", CueKind.DYNAMIC, "en", 7_200, 0.95f),
            ),
            dominantLanguage = "en",
            candidateCount = 2,
        )

        val cues = ArrangementGuideSequencePlanner.select(analysis, 8_000, "bridge", 2_000.0)

        assertTrue(cues.none { it.kind == CueKind.COUNT })
    }

    @Test
    fun select_synthesizesSectionCallWhenOldAnalysisHasOnlyDynamics() {
        val analysis = GuideCueAnalyzer.Result(
            cues = listOf(GuideCueAnalyzer.DetectedCue("build", CueKind.DYNAMIC, "es", 7_200, 0.95f)),
            dominantLanguage = "es",
            candidateCount = 1,
        )

        val cues = ArrangementGuideSequencePlanner.select(analysis, 8_000, "bridge", 2_000.0)

        assertTrue(cues.any { it.kind == CueKind.SECTION && it.key == "bridge" && it.offsetMs == 0L })
        assertTrue(cues.any { it.kind == CueKind.DYNAMIC && it.key == "build" })
    }
}
