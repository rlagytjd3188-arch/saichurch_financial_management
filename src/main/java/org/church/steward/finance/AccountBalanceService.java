package org.church.steward.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 데스크톱의 대시보드·결산 보고서·회계 연도 목록이 공유하는 기초잔액과 이월액 계산. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountBalanceService {
    private final JdbcTemplate jdbc;

    /** 원본 거래 분개를 중복 합산하지 않고, 취소 역분개까지 반영한 자산·순자산 조정액. */
    public long openingBalance(LocalDate from, LocalDate to) {
        String sql = "SELECT COALESCE(SUM(L.DEBIT-L.CREDIT),0) FROM ACCT_ENTRY E JOIN ACCT_LINE L ON L.ENTRY_ID=E.ID JOIN ACCT_ACCOUNT A ON A.ID=L.ACCOUNT_ID WHERE E.SOURCE_RECORD_ID IS NULL AND A.TYPE='ASSET' AND E.ENTRY_DATE<=? AND EXISTS(SELECT 1 FROM ACCT_LINE X JOIN ACCT_ACCOUNT XA ON XA.ID=X.ACCOUNT_ID WHERE X.ENTRY_ID=E.ID AND XA.TYPE='EQUITY')";
        BigDecimal amount = from == null ? jdbc.queryForObject(sql, BigDecimal.class, to)
                : jdbc.queryForObject(sql + " AND E.ENTRY_DATE>=?", BigDecimal.class, to, from);
        return amount == null ? 0 : amount.longValueExact();
    }

    public long otherYearCarry(long year, LocalDate before) {
        var amount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN TYPE='INCOME' THEN AMOUNT ELSE -AMOUNT END),0) FROM FINANCIAL_RECORD WHERE CANCELLED=FALSE AND FISCAL_YEAR_ID<>? AND DATE<?",
                BigDecimal.class, year, before);
        return amount == null ? 0 : amount.longValueExact();
    }

    /** 해당 연도의 수입·지출 외에 기말 잔액에 반영할 기초잔액과 과거 연도 이월액. */
    public long carriedFunds(long year, LocalDate start, LocalDate end) {
        return Math.addExact(otherYearCarry(year, start), openingBalance(null, end));
    }
}
