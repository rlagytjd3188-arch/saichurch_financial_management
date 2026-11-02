package org.church.steward.domain.report.dto;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주간 회계 결산 보고서의 데이터
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklySettlementReportDto {
    private org.church.steward.finance.PledgeService.Summary pledges;
    private String fiscalYearName;
    /** 선택 기간의 일별/월별 발생액. 누계와 별도로 그래프에 사용한다. */
    @Builder.Default
    private List<PeriodTrendDto> periodTrend = List.of();
    private LocalDate startDate;
    private LocalDate endDate;

    /** 시작일 전날까지의 기초잔액과 수입 - 지출. 이전 회계 연도 이월액도 포함한다. */
    private long previousBalance;
    /** 보고서 종료일까지 등록된 기초잔액·순자산 조정액 */
    private long openingBalance;
    /** 선택 기간에 입력된 기초잔액. 수입에는 포함하지 않는다. */
    private long periodOpeningBalance;
    /** 해당 주간의 수입과 지출 */
    private ReportSummaryDto weekly;

    /** 최상위 예산별 현황. 회계 연도의 내역 중 종료일까지를 집행액으로 합산한다. */
    private List<BudgetExecutionDto> incomeStatus;
    private List<BudgetExecutionDto> expenseStatus;

    /** 해당 주간의 내역. 예산 관리에서 정한 예산 순서를 따르며 내역이 있는 예산만 담는다. */
    private List<ReportRecordGroupDto> incomeRecords;
    private List<ReportRecordGroupDto> expenseRecords;

    /**
     * 현재 잔액(이월액 + 기간 중 기초잔액 + 기간 수입 - 기간 지출)
     */
    public long getCurrentBalance() {
        return Math.addExact(Math.addExact(previousBalance, periodOpeningBalance), weekly.getDifference());
    }
}
