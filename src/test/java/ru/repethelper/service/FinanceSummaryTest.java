package ru.repethelper.service;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceSummaryTest {
    @Test
    void expectedIncomeIsReceivedPlusRemainingAndUsesLongArithmetic() {
        var summary = new FinanceService.MonthSummary(
                YearMonth.of(2026, 8), 2_500_000_000L, 1_500_000_000L);

        assertThat(summary.received()).isEqualTo(2_500_000_000L);
        assertThat(summary.remaining()).isEqualTo(1_500_000_000L);
        assertThat(summary.expected()).isEqualTo(4_000_000_000L);
    }
}
