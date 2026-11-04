package de.tetnavigator.feature.logistik

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SunCalculatorTest {
    private fun minutesUtc(i: Instant) = (i.epochSecond % 86_400) / 60.0

    @Test fun osloMidsummer() {
        val s = SunCalculator.compute(LocalDate.of(2024, 6, 21), 59.91, 10.75) as SunTimes.Normal
        // Referenz NOAA: Sonnenaufgang 01:53 UTC, Untergang 20:44 UTC
        assertThat(minutesUtc(s.sunrise)).isWithin(5.0).of(1 * 60 + 53.0)
        assertThat(minutesUtc(s.sunset)).isWithin(5.0).of(20 * 60 + 44.0)
    }

    @Test fun berlinEquinox() {
        val s = SunCalculator.compute(LocalDate.of(2024, 3, 20), 52.52, 13.405) as SunTimes.Normal
        assertThat(minutesUtc(s.sunrise)).isWithin(5.0).of(5 * 60 + 3.0)
        assertThat(minutesUtc(s.sunset)).isWithin(5.0).of(17 * 60 + 16.0)
    }

    @Test fun tromsoMidnightSunAndPolarNight() {
        assertThat(SunCalculator.compute(LocalDate.of(2024, 6, 21), 69.65, 18.96)).isInstanceOf(SunTimes.PolarDay::class.java)
        assertThat(SunCalculator.compute(LocalDate.of(2024, 12, 21), 69.65, 18.96)).isInstanceOf(SunTimes.PolarNight::class.java)
        assertThat(SunCalculator.millisUntilDark(Instant.parse("2024-06-21T12:00:00Z"), 69.65, 18.96)).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun trailKmUntilDark() {
        val now = Instant.parse("2024-03-20T15:16:00Z") // 2 h vor Sonnenuntergang Berlin
        val d = DaylightCalculator.compute(now, 52.52, 13.405, 30.0, true)
        assertThat(d.millisUntilDark / 60_000.0).isWithin(6.0).of(120.0)
        assertThat(d.trailKmUntilDark).isWithin(4.0).of(60.0)
        assertThat(DaylightCalculator.check(50.0, 30.0, d.millisUntilDark)).isEqualTo(CheckLevel.OK)
        assertThat(DaylightCalculator.check(80.0, 30.0, d.millisUntilDark)).isEqualTo(CheckLevel.BAD)
    }

    @Test fun measuredAverageNeedsEnoughData() {
        assertThat(DaylightCalculator.averageKmh(5_000.0, 600_000, 25.0)).isEqualTo(25.0 to false)
        val (v, m) = DaylightCalculator.averageKmh(100_000.0, 4 * 3_600_000L, 25.0)
        assertThat(m).isTrue(); assertThat(v).isWithin(0.01).of(25.0)
    }
}

class FuelRangeTest {
    @Test fun rangeFromTankAndConsumption() {
        assertThat(FuelRange.rangeM(13.0, 4.5)).isWithin(1.0).of(288_888.9)
        assertThat(FuelRange.consumedLiters(100_000.0, 5.0)).isWithin(1e-9).of(5.0)
    }

    @Test fun warnsAtLastStationBeforeLongGravel() {
        val stations = listOf(StationOnTrack(20_000.0, "Statoil Røros", 300.0))
        val w = FuelRange.evaluate(0.0, 300_000.0, 250_000.0, 280_000.0, stations)
        assertThat(w).isInstanceOf(FuelWarning.LastStationBeforeGap::class.java)
        w as FuelWarning.LastStationBeforeGap
        assertThat(w.stationInM).isEqualTo(20_000.0)
        assertThat(w.gapM).isEqualTo(280_000.0)
    }

    @Test fun outOfRange() {
        val w = FuelRange.evaluate(0.0, 200_000.0, 50_000.0, 280_000.0, listOf(StationOnTrack(80_000.0, "X", 0.0)))
        assertThat(w).isInstanceOf(FuelWarning.OutOfRange::class.java)
    }

    @Test fun okWithStationsEveryHundredKm() {
        val st = (1..5).map { StationOnTrack(it * 100_000.0, "S$it", 0.0) }
        assertThat(FuelRange.evaluate(0.0, 550_000.0, 250_000.0, 280_000.0, st)).isEqualTo(FuelWarning.Ok)
        assertThat(FuelRange.check(550_000.0, 250_000.0, 280_000.0, st)).isEqualTo(CheckLevel.OK)
        assertThat(FuelRange.check(550_000.0, 250_000.0, 280_000.0, emptyList())).isEqualTo(CheckLevel.BAD)
    }
}

class StagePlannerTest {
    @Test fun suggestsDaysByHours() {
        val s = (1..6).map { StageSection(it.toLong(), "S$it", "NO", 60_000.0, 500.0, false) }
        val days = StagePlanner.suggest(s, avgKmh = 30.0, hoursPerDay = 5.0) // 2 h je Sektion
        assertThat(days.map { it.sections.size }).containsExactly(2, 2, 2).inOrder()
        assertThat(days[0].durationMs(30.0)).isEqualTo(4 * 3_600_000L)
    }
}
