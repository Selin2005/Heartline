package com.heartline.phone

import com.heartline.phone.data.RecordEntity
import com.heartline.phone.data.StoredRecord
import com.heartline.phone.ui.model.MetricFormat
import com.heartline.phone.ui.model.RecordFormatter
import com.heartline.shared.model.RecordKind
import com.heartline.shared.model.RecordSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class MetricFormatTest {
    private val formatter = RecordFormatter("Today", "Yesterday", zone = ZoneOffset.UTC, locale = Locale.US) { LocalDate.of(1970, 1, 10) }

    private fun record(i: Int, summary: RecordSummary, kind: RecordKind) =
        StoredRecord(RecordEntity("r$i", kind, (9 - i) * 86_400_000L, 0, 0, 0, "", null, receivedAtMs = 0), summary)

    @Test
    fun skinTemperatureShowsDeviationFromBaseline() {
        val temps = listOf(33.8f, 33.0f, 33.2f, 33.4f).mapIndexed { i, t -> record(i, RecordSummary.SkinTemperature(t, 24f), RecordKind.SKIN_TEMPERATURE) }
        val readings = MetricFormat.readings(temps, formatter)
        assertEquals("+0.6", readings.first().value)
        assertEquals("Today", readings.first().date)
        assertEquals("33.0", readings[1].value) // not enough history: absolute value
    }

    @Test
    fun stressAndSpo2Values() {
        val stress = MetricFormat.readings(listOf(record(0, RecordSummary.Stress(72, 18.0, 6f), RecordKind.STRESS)), formatter).single()
        assertEquals("72", stress.value)
        assertEquals("High", stress.details.first().second)
        val spo2 = MetricFormat.readings(listOf(record(0, RecordSummary.Spo2(97, 63, false), RecordKind.SPO2)), formatter).single()
        assertEquals("97", spo2.value)
        assertEquals("%", spo2.unit)
    }
}
