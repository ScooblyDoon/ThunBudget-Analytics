package ru.scooblydoon.thunbudget

import kotlin.math.ceil

data class ForecastCalibrationBand(
    val radius: Double?,
    val observations: Int,
    val empiricalCoveragePercent: Double?
)

data class ForecastCalibration(
    val byCategory: Map<String, ForecastCalibrationBand> = emptyMap()
) {
    fun forCategory(category: String): ForecastCalibrationBand? =
        byCategory[category.trim().lowercase()]
}

internal object ForecastIntervalCalibrator {
    const val TARGET_COVERAGE = 0.80
    const val MIN_OBSERVATIONS = 8

    fun build(samplesByCategory: Map<String, List<Double>>): ForecastCalibration {
        val bands = samplesByCategory.mapNotNull { (category, rawSamples) ->
            val samples = rawSamples.filter { it.isFinite() && it >= 0.0 }.sorted()
            if (samples.isEmpty()) return@mapNotNull null
            val radius = if (samples.size >= MIN_OBSERVATIONS) {
                val oneBasedRank = ceil((samples.size + 1) * TARGET_COVERAGE)
                    .toInt().coerceIn(1, samples.size)
                samples[oneBasedRank - 1]
            } else null
            val coverage = radius?.let { selected ->
                samples.count { it <= selected }.toDouble() / samples.size.toDouble() * 100.0
            }
            category.trim().lowercase() to ForecastCalibrationBand(
                radius = radius,
                observations = samples.size,
                empiricalCoveragePercent = coverage
            )
        }.toMap()
        return ForecastCalibration(bands)
    }
}
