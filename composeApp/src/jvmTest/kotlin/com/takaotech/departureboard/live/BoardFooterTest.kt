package com.takaotech.departureboard.live

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardFooterTest {

    /** La fascia inferiore deve restare uguale a quella calcolata prima con `java.time` e `Locale.ITALIAN`. */
    @Test
    fun `orologio e data come java time`() {
        val it = Locale.ITALIAN
        var date = LocalDate(2026, 1, 1)
        repeat(366) { i ->
            val now = LocalDateTime(date, LocalTime(i % 24, i % 60))
            val j = java.time.LocalDateTime.of(
                now.year,
                now.month.ordinal + 1,
                now.day,
                now.hour,
                now.minute
            )

            val footer = boardFooter(now, emptyList(), "")

            assertEquals(j.format(DateTimeFormatter.ofPattern("HH:mm")), footer.clock)
            assertEquals(
                listOf(
                    j.format(DateTimeFormatter.ofPattern("EEE", it)).take(3),
                    j.dayOfMonth.toString(),
                    j.format(DateTimeFormatter.ofPattern("MMM", it)).take(3),
                ),
                footer.date,
            )
            date = date.plus(DatePeriod(days = 1))
        }
    }
}
