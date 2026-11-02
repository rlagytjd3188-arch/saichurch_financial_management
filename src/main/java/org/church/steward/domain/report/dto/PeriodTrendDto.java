package org.church.steward.domain.report.dto;

/** 하루 또는 한 달에 발생한 금액. 이월액·이전 기간 누계를 포함하지 않는다. */
public record PeriodTrendDto(String label, long income, long expense, long offerings,
        java.util.List<org.church.steward.finance.ChartPart> breakdown,
        java.util.List<org.church.steward.finance.ChartPart> offeringBreakdown) {
    public PeriodTrendDto(String label, long income, long expense, long offerings) {
        this(label, income, expense, offerings, java.util.List.of(), java.util.List.of());
    }
}
