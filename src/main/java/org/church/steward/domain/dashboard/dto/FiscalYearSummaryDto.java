package org.church.steward.domain.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FiscalYearSummaryDto {
    private long totalIncome;
    private long totalExpense;
    private long openingBalance;
    private long carriedFunds;
    private long currentBalance; // 기초잔액·이월액 + 해당 연도 수입 - 지출
}
