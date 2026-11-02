package org.church.steward.domain.report.dto;

/** 하루 또는 한 달에 발생한 금액. 이월액·이전 기간 누계를 포함하지 않는다. */
public record PeriodTrendDto(String label, long income, long expense, long offerings) {
}
