package org.church.steward.domain.financialrecord.service.impl;

/** 기존 입력과 새 원장을 동일 트랜잭션에서 연결하는 변경 알림이다. */
public record RecordAccountingEvent(Long recordId, boolean created, boolean cancelled) {
}
