package org.church.steward.finance;

import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 월 약정 목표와 실제 약정헌금은 별도 집계한다. 미배분 공동헌금은 개인 실적으로 추정하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PledgeService {
    private final JdbcTemplate jdbc;
    private final org.church.steward.infrastructure.security.UserSessionHolder sessions;

    @Transactional
    public void save(Map<String, Object> body) {
        long contact = FinanceEngine.number(body.get("contact")), amount = FinanceEngine.number(body.get("amount"));
        Long id = body.get("id") == null ? null : FinanceEngine.number(body.get("id"));
        LocalDate start = YearMonth.parse(body.get("start").toString()).atDay(1);
        LocalDate end = body.get("end") == null || body.get("end").toString().isBlank() ? null
                : YearMonth.parse(body.get("end").toString()).atDay(1);
        if (amount <= 0 || (end != null && end.isBefore(start)))
            throw new IllegalArgumentException("월 약정액과 시작·종료월을 확인하세요.");
        var person = jdbc.queryForList("SELECT ID,TYPE FROM CONTACT WHERE ID=? FOR UPDATE", contact);
        if (person.isEmpty() || !"INDIVIDUAL".equals(person.getFirst().get("TYPE")))
            throw new IllegalArgumentException("개인 헌금자를 선택하세요.");
        if (id != null && jdbc.queryForObject(
                "SELECT COUNT(*) FROM PLEDGE_COMMITMENT WHERE ID=? AND CONTACT_ID=? AND CANCELLED=FALSE",
                Long.class, id, contact) == 0)
            throw new IllegalArgumentException("약정 내역을 찾을 수 없습니다.");
        long overlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PLEDGE_COMMITMENT WHERE CONTACT_ID=? AND START_MONTH<=? AND (END_MONTH IS NULL OR END_MONTH>=?) AND CANCELLED=FALSE AND ID<>?",
                Long.class,
                contact, end == null ? LocalDate.of(9999, 12, 1) : end, start, id == null ? -1L : id);
        if (overlaps > 0)
            throw new IllegalArgumentException("기존 약정과 기간이 겹칩니다. 기존 약정의 종료월을 먼저 지정하세요.");
        if (id == null)
            jdbc.update(
                    "INSERT INTO PLEDGE_COMMITMENT(CONTACT_ID,MONTHLY_AMOUNT,START_MONTH,END_MONTH) VALUES(?,?,?,?)",
                    contact, amount, start, end);
        else
            jdbc.update("UPDATE PLEDGE_COMMITMENT SET MONTHLY_AMOUNT=?,START_MONTH=?,END_MONTH=? WHERE ID=?", amount,
                    start, end, id);
        audit("월 약정 설정 · 헌금자 " + contact + " · " + amount + "원 · " + start + " ~ " + end);
    }

    @Transactional
    public void cancel(long id) {
        if (jdbc.update("UPDATE PLEDGE_COMMITMENT SET CANCELLED=TRUE WHERE ID=? AND CANCELLED=FALSE", id) != 1)
            throw new IllegalArgumentException("약정 내역을 찾을 수 없습니다.");
        audit("월 약정 입력 취소 · 약정 ID " + id);
    }

    private void audit(String description) {
        jdbc.update(
                "INSERT INTO ACTIVITY_LOG(USER_ID,ACTIVITY_TYPE,TARGET_NAME,DESCRIPTION,CREATED_AT) VALUES(?,'UPDATE','약정헌금',?,CURRENT_TIMESTAMP)",
                sessions.getLoggedInUser() == null ? "시스템" : sessions.getLoggedInUser().id(), description);
    }

    public List<Map<String, Object>> commitments() {
        return jdbc.queryForList(
                "SELECT P.*,C.NAME FROM PLEDGE_COMMITMENT P JOIN CONTACT C ON C.ID=P.CONTACT_ID WHERE P.CANCELLED=FALSE ORDER BY C.NAME,P.START_MONTH");
    }

    public record Month(String label,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long target,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long actual) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public Double percent() {
            return rate(actual, target);
        }
    }

    public record Person(long id, String name,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long target,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long actual) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public Double percent() {
            return rate(actual, target);
        }
    }

    public record Summary(LocalDate from, LocalDate to,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long target,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long actual,
            @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long allocated,
            List<Month> monthly, List<Person> people) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public Double percent() {
            return rate(actual, target);
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
        public long unallocated() {
            return Math.subtractExact(actual, allocated);
        }

        public Summary publicSummary() {
            return new Summary(from, to, target, actual, allocated, monthly, List.of());
        }
    }

    private static Double rate(long actual, long target) {
        return target == 0 ? null
                : BigDecimal.valueOf(actual).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(target), 2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 월 약정 전체를 목표로 하며, 첫 달은 월초부터 조회 종료일까지 납부액을 집계한다. 주간도 월 누계다. */
    public Summary summary(LocalDate from, LocalDate to) {
        if (from.isAfter(to))
            throw new IllegalArgumentException("약정 조회 기간을 확인하세요.");
        LocalDate first = from.withDayOfMonth(1);
        var commitments = commitments();
        var actuals = jdbc.queryForList(
                "SELECT R.ID,R.DATE,R.AMOUNT,A.CONTACT_ID,A.AMOUNT SHARE,C.NAME FROM FINANCIAL_RECORD R JOIN ACCT_LINK K ON K.RECORD_ID=R.ID AND K.STATUS='POSTED' JOIN OFFERING_TYPE T ON T.ID=K.OFFERING_TYPE_ID LEFT JOIN OFFERING_ALLOCATION A ON A.RECORD_ID=R.ID LEFT JOIN CONTACT C ON C.ID=A.CONTACT_ID WHERE R.TYPE='INCOME' AND R.CANCELLED=FALSE AND T.NAME='약정헌금' AND R.DATE BETWEEN ? AND ? ORDER BY R.DATE,R.ID",
                first, to);
        Map<Long, long[]> totals = new TreeMap<>();
        Map<Long, String> names = new HashMap<>();
        List<Month> monthly = new ArrayList<>();
        long target = 0, actual = 0, allocated = 0;
        for (YearMonth month = YearMonth.from(first); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            long expected = 0, paid = 0;
            for (var p : commitments) {
                LocalDate start = ((java.sql.Date) p.get("START_MONTH")).toLocalDate();
                LocalDate end = p.get("END_MONTH") == null ? null : ((java.sql.Date) p.get("END_MONTH")).toLocalDate();
                if (start.isAfter(month.atDay(1)) || end != null && end.isBefore(month.atDay(1)))
                    continue;
                long id = FinanceEngine.number(p.get("CONTACT_ID")),
                        amount = FinanceEngine.number(p.get("MONTHLY_AMOUNT"));
                expected = Math.addExact(expected, amount);
                totals.computeIfAbsent(id, _ -> new long[2])[0] = Math.addExact(totals.get(id)[0], amount);
                names.put(id, p.get("NAME").toString());
            }
            Set<Long> seen = new HashSet<>();
            for (var a : actuals) {
                if (!YearMonth.from(((java.sql.Date) a.get("DATE")).toLocalDate()).equals(month))
                    continue;
                if (seen.add(FinanceEngine.number(a.get("ID"))))
                    paid = Math.addExact(paid, FinanceEngine.number(a.get("AMOUNT")));
                if (a.get("CONTACT_ID") != null) {
                    long id = FinanceEngine.number(a.get("CONTACT_ID")), share = FinanceEngine.number(a.get("SHARE"));
                    totals.computeIfAbsent(id, _ -> new long[2])[1] = Math.addExact(totals.get(id)[1], share);
                    allocated = Math.addExact(allocated, share);
                    names.put(id, a.get("NAME").toString());
                }
            }
            monthly.add(new Month(month.toString(), expected, paid));
            target = Math.addExact(target, expected);
            actual = Math.addExact(actual, paid);
        }
        List<Person> people = totals.entrySet().stream()
                .map(e -> new Person(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1])).toList();
        return new Summary(first, to, target, actual, allocated, monthly, people);
    }
}
