package org.church.steward.finance;

import java.math.BigInteger;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

/** 원본 거래, 헌금, 원장, 표와 그래프가 공유하는 회계 서비스다. */
@Service
@RequiredArgsConstructor
@Transactional
public class FinanceEngine {
    private final JdbcTemplate jdbc;
    private final org.church.steward.infrastructure.security.UserSessionHolder sessions;

    public record Line(long accountId, long debit, long credit) {
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private long code(String code) {
        return jdbc.queryForObject("SELECT ID FROM ACCT_ACCOUNT WHERE CODE=?", Long.class, code);
    }

    public static long number(Object value) {
        return Long.parseLong(String.valueOf(value));
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        if (rows.isEmpty())
            throw new IllegalArgumentException("자료를 찾을 수 없습니다.");
        return rows.getFirst();
    }

    private void openYear(long year, LocalDate date) {
        Map<String, Object> y = one("SELECT * FROM FISCAL_YEAR WHERE ID=?", year);
        if (Boolean.TRUE.equals(y.get("CLOSED")))
            throw new IllegalArgumentException("마감된 회계 연도는 변경할 수 없습니다.");
        if (date.isBefore(((Date) y.get("START_DATE")).toLocalDate())
                || date.isAfter(((Date) y.get("END_DATE")).toLocalDate()))
            throw new IllegalArgumentException("회계 연도 기간을 확인하세요.");
    }

    public long journal(long year, LocalDate date, String memo, List<Line> lines, Long reverses) {
        openYear(year, date);
        if (lines.size() < 2)
            throw new IllegalArgumentException("분개는 두 행 이상이어야 합니다.");
        BigInteger debit = BigInteger.ZERO, credit = BigInteger.ZERO;
        for (Line l : lines) {
            if (l.debit() < 0 || l.credit() < 0 || (l.debit() == 0) == (l.credit() == 0))
                throw new IllegalArgumentException("각 행의 차변 또는 대변 한쪽에 양의 정수 금액을 입력하세요.");
            one(reverses == null ? "SELECT ID FROM ACCT_ACCOUNT WHERE ID=? AND ACTIVE=TRUE"
                    : "SELECT ID FROM ACCT_ACCOUNT WHERE ID=?", l.accountId());
            debit = debit.add(BigInteger.valueOf(l.debit()));
            credit = credit.add(BigInteger.valueOf(l.credit()));
        }
        if (!debit.equals(credit))
            throw new IllegalArgumentException("차변 합계와 대변 합계가 다릅니다.");
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            java.sql.PreparedStatement p = c.prepareStatement(
                    "INSERT INTO ACCT_ENTRY(ENTRY_DATE,FISCAL_YEAR_ID,MEMO,REVERSES_ID) VALUES(?,?,?,?)",
                    new String[] { "ID" });
            p.setObject(1, date);
            p.setLong(2, year);
            p.setString(3, memo);
            p.setObject(4, reverses);
            return p;
        }, key);
        long id = key.getKey().longValue();
        for (Line l : lines)
            jdbc.update("INSERT INTO ACCT_LINE(ENTRY_ID,ACCOUNT_ID,DEBIT,CREDIT) VALUES(?,?,?,?)", id, l.accountId(),
                    l.debit(), l.credit());
        log("분개 생성", String.valueOf(id));
        return id;
    }

    private void reverse(long entry) {
        Map<String, Object> e = one("SELECT * FROM ACCT_ENTRY WHERE ID=?", entry);
        if (!query("SELECT ID FROM ACCT_ENTRY WHERE REVERSES_ID=?", entry).isEmpty())
            return;
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> l : query("SELECT * FROM ACCT_LINE WHERE ENTRY_ID=?", entry))
            lines.add(new Line(number(l.get("ACCOUNT_ID")), number(l.get("CREDIT")), number(l.get("DEBIT"))));
        long reversal = journal(number(e.get("FISCAL_YEAR_ID")), ((Date) e.get("ENTRY_DATE")).toLocalDate(),
                "정정·취소 역분개", lines, entry);
        jdbc.update("UPDATE ACCT_ENTRY SET SOURCE_RECORD_ID=? WHERE ID=?", e.get("SOURCE_RECORD_ID"), reversal);
    }

    public void reverseManual(long id) {
        Map<String, Object> entry = one("SELECT * FROM ACCT_ENTRY WHERE ID=? FOR UPDATE", id);
        if (entry.get("SOURCE_RECORD_ID") != null || entry.get("REVERSES_ID") != null)
            throw new IllegalArgumentException("원본 거래에서 정정·취소하세요. 역분개 자체는 다시 취소할 수 없습니다.");
        if (!query("SELECT ID FROM ACCT_ENTRY WHERE REVERSES_ID=?", id).isEmpty())
            throw new IllegalArgumentException("이미 취소된 분개입니다.");
        reverse(id);
        log("수동 분개 취소", String.valueOf(id));
    }

    /** 기존 입력을 유지하며 같은 DB 트랜잭션 안에서 분개를 만든다. 과거 미확정 거래는 추정하지 않는다. */
    public void synchronize(long recordId, boolean created, boolean cancelled) {
        Map<String, Object> r = one("SELECT * FROM FINANCIAL_RECORD WHERE ID=?", recordId);
        List<Map<String, Object>> found = query("SELECT * FROM ACCT_LINK WHERE RECORD_ID=? FOR UPDATE", recordId);
        if (found.isEmpty()) {
            jdbc.update("INSERT INTO ACCT_LINK(RECORD_ID,STATUS) VALUES(?,'REVIEW')", recordId);
            found = query("SELECT * FROM ACCT_LINK WHERE RECORD_ID=?", recordId);
        }
        Map<String, Object> link = found.getFirst();
        if (link.get("ENTRY_ID") != null)
            reverse(number(link.get("ENTRY_ID")));
        if (cancelled) {
            jdbc.update("UPDATE ACCT_LINK SET STATUS='CANCELLED',ENTRY_ID=NULL WHERE RECORD_ID=?", recordId);
            return;
        }
        if (!created && !"POSTED".equals(link.get("STATUS")))
            return;
        long asset = link.get("ASSET_ID") == null ? code("1100") : number(link.get("ASSET_ID"));
        long counterpart = link.get("COUNTERPART_ID") == null ? code("INCOME".equals(r.get("TYPE")) ? "4900" : "5900")
                : number(link.get("COUNTERPART_ID"));
        Long offering = link.get("OFFERING_TYPE_ID") == null ? null : number(link.get("OFFERING_TYPE_ID"));
        if (created && offering == null && "INCOME".equals(r.get("TYPE"))) {
            var match = query(
                    "SELECT T.ID FROM BUDGET B JOIN OFFERING_TYPE T ON T.NAME=B.NAME AND T.ACTIVE=TRUE WHERE B.ID=?",
                    r.get("BUDGET_ID"));
            if (match.size() == 1) {
                offering = number(match.getFirst().get("ID"));
                counterpart = code("4000");
            }
        }
        writeRecord(r, asset, counterpart, offering);
    }

    private void writeRecord(Map<String, Object> r, long asset, long counterpart, Long offering) {
        String kind = String.valueOf(r.get("TYPE"));
        Map<String, Object> a = one("SELECT * FROM ACCT_ACCOUNT WHERE ID=? AND ACTIVE=TRUE", asset),
                b = one("SELECT * FROM ACCT_ACCOUNT WHERE ID=? AND ACTIVE=TRUE", counterpart);
        if (!"ASSET".equals(a.get("TYPE")) || !("INCOME".equals(kind) ? "INCOME" : "EXPENSE").equals(b.get("TYPE")))
            throw new IllegalArgumentException("자산 및 수익·비용 계정 유형을 확인하세요.");
        long amount = number(r.get("AMOUNT")), id = number(r.get("ID"));
        Long entry = null;
        if (amount > 0) {
            boolean income = "INCOME".equals(kind);
            entry = journal(number(r.get("FISCAL_YEAR_ID")), ((Date) r.get("DATE")).toLocalDate(),
                    String.valueOf(r.get("DESCRIPTION")),
                    List.of(new Line(asset, income ? amount : 0, income ? 0 : amount),
                            new Line(counterpart, income ? 0 : amount, income ? amount : 0)),
                    null);
            jdbc.update("UPDATE ACCT_ENTRY SET SOURCE_RECORD_ID=? WHERE ID=?", id, entry);
        }
        jdbc.update(
                "UPDATE ACCT_LINK SET ENTRY_ID=?,STATUS='POSTED',ASSET_ID=?,COUNTERPART_ID=?,OFFERING_TYPE_ID=? WHERE RECORD_ID=?",
                entry, asset, counterpart, offering, id);
        if (offering != null) {
            List<Map<String, Object>> contacts = query(
                    "SELECT CONTACT_ID FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?", id);
            Map<Long, Long> amounts = new HashMap<>();
            for (Map<String, Object> x : query("SELECT * FROM OFFERING_ALLOCATION WHERE RECORD_ID=?", id))
                amounts.put(number(x.get("CONTACT_ID")), number(x.get("AMOUNT")));
            long allocated = 0;
            for (long n : amounts.values())
                allocated = Math.addExact(allocated, n);
            if (contacts.size() == 1) {
                jdbc.update("DELETE FROM OFFERING_ALLOCATION WHERE RECORD_ID=?", id);
                jdbc.update("INSERT INTO OFFERING_ALLOCATION VALUES(?,?,?)", id, contacts.getFirst().get("CONTACT_ID"),
                        amount);
            } else if (allocated != amount
                    || !new HashSet<>(contacts.stream().map(x -> number(x.get("CONTACT_ID"))).toList())
                            .equals(amounts.keySet())) {
                jdbc.update("DELETE FROM OFFERING_ALLOCATION WHERE RECORD_ID=?", id);
            }
        }
    }

    public void classify(long id, long asset, long counterpart, Long offering, Map<Long, Long> allocation) {
        Map<String, Object> r = one("SELECT * FROM FINANCIAL_RECORD WHERE ID=? AND CANCELLED=FALSE", id);
        openYear(number(r.get("FISCAL_YEAR_ID")), ((Date) r.get("DATE")).toLocalDate());
        List<Map<String, Object>> links = query("SELECT * FROM ACCT_LINK WHERE RECORD_ID=? FOR UPDATE", id);
        if (offering != null) {
            if (!"INCOME".equals(r.get("TYPE")))
                throw new IllegalArgumentException("헌금은 수입 거래에만 연결할 수 있습니다.");
            boolean existingType = !links.isEmpty()
                    && Objects.equals(links.getFirst().get("OFFERING_TYPE_ID"), offering);
            one(existingType ? "SELECT ID FROM OFFERING_TYPE WHERE ID=?"
                    : "SELECT ID FROM OFFERING_TYPE WHERE ID=? AND ACTIVE=TRUE", offering);
            if (counterpart != code("4000")
                    && !"INCOME".equals(one("SELECT TYPE FROM ACCT_ACCOUNT WHERE ID=?", counterpart).get("TYPE")))
                throw new IllegalArgumentException("헌금 수익 계정을 선택하세요.");
        }
        if (links.isEmpty())
            jdbc.update("INSERT INTO ACCT_LINK(RECORD_ID,STATUS) VALUES(?,'REVIEW')", id);
        else if (links.getFirst().get("ENTRY_ID") != null)
            reverse(number(links.getFirst().get("ENTRY_ID")));
        if (allocation != null && !allocation.isEmpty()) {
            long total = 0;
            Set<Long> valid = new HashSet<>();
            for (Map<String, Object> c : query(
                    "SELECT CONTACT_ID FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?", id))
                valid.add(number(c.get("CONTACT_ID")));
            for (Map.Entry<Long, Long> x : allocation.entrySet()) {
                if (!valid.contains(x.getKey()) || x.getValue() < 0)
                    throw new IllegalArgumentException("헌금자 배분을 확인하세요.");
                total = Math.addExact(total, x.getValue());
            }
            if (total != number(r.get("AMOUNT")))
                throw new IllegalArgumentException("개인별 배분 합계가 거래 금액과 다릅니다.");
            jdbc.update("DELETE FROM OFFERING_ALLOCATION WHERE RECORD_ID=?", id);
            for (Map.Entry<Long, Long> x : allocation.entrySet())
                jdbc.update("INSERT INTO OFFERING_ALLOCATION VALUES(?,?,?)", id, x.getKey(), x.getValue());
        }
        writeRecord(r, asset, counterpart, offering);
        log("회계·헌금 연결", String.valueOf(id));
    }

    /** 사용자가 화면에서 확인한 기존 수입만 한 트랜잭션으로 헌금에 연결한다. */
    public void linkBudgetOfferings(List<Long> ids) {
        if (ids == null || ids.isEmpty())
            throw new IllegalArgumentException("연결할 거래를 선택하세요.");
        for (long id : new LinkedHashSet<>(ids)) {
            var r = one(
                    "SELECT R.*,K.OFFERING_TYPE_ID,K.ASSET_ID FROM FINANCIAL_RECORD R LEFT JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.ID=? AND R.CANCELLED=FALSE AND R.TYPE='INCOME' FOR UPDATE",
                    id);
            if (r.get("OFFERING_TYPE_ID") != null)
                throw new IllegalArgumentException("이미 헌금에 연결된 거래입니다. 다시 조회하세요.");
            var type = one(
                    "SELECT T.ID FROM BUDGET B JOIN OFFERING_TYPE T ON T.NAME=B.NAME AND T.ACTIVE=TRUE WHERE B.ID=?",
                    r.get("BUDGET_ID"));
            classify(id, r.get("ASSET_ID") == null ? code("1100") : number(r.get("ASSET_ID")), code("4000"),
                    number(type.get("ID")), null);
        }
    }

    public void settings(String operation, Map<String, Object> p) {
        if (operation.equals("account")) {
            String name = String.valueOf(p.get("name")), type = String.valueOf(p.get("type"));
            if (name.isBlank() || name.length() > 100
                    || !Set.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE").contains(type))
                throw new IllegalArgumentException("계정 이름과 유형을 확인하세요.");
            if (p.get("id") == null)
                jdbc.update("INSERT INTO ACCT_ACCOUNT(CODE,NAME,TYPE) VALUES(?,?,?)", p.get("code"), name, type);
            else {
                long id = number(p.get("id"));
                Map<String, Object> old = one("SELECT * FROM ACCT_ACCOUNT WHERE ID=?", id);
                if (!old.get("TYPE").equals(type)
                        && !query("SELECT ID FROM ACCT_LINE WHERE ACCOUNT_ID=?", id).isEmpty())
                    throw new IllegalArgumentException("사용된 계정의 유형은 변경할 수 없습니다.");
                jdbc.update("UPDATE ACCT_ACCOUNT SET NAME=?,TYPE=?,ACTIVE=? WHERE ID=?", name, type,
                        !Boolean.FALSE.equals(p.get("active")), id);
            }
        } else if (operation.equals("offeringType")) {
            String name = String.valueOf(p.get("name"));
            if (name.isBlank() || name.length() > 100)
                throw new IllegalArgumentException("헌금 종류 이름을 확인하세요.");
            if (p.get("id") == null)
                jdbc.update("INSERT INTO OFFERING_TYPE(NAME) VALUES(?)", name);
            else
                jdbc.update("UPDATE OFFERING_TYPE SET NAME=?,ACTIVE=? WHERE ID=?", name,
                        !Boolean.FALSE.equals(p.get("active")), number(p.get("id")));
        } else
            throw new IllegalArgumentException("지원하지 않는 설정입니다.");
        log("설정 변경", operation);
    }

    private void log(String action, String details) {
        jdbc.update(
                "INSERT INTO ACTIVITY_LOG(USER_ID,ACTIVITY_TYPE,TARGET_NAME,DESCRIPTION,CREATED_AT) VALUES(?,'UPDATE',?,?,CURRENT_TIMESTAMP)",
                sessions.getLoggedInUser() == null ? "시스템" : sessions.getLoggedInUser().id(), action, details);
    }

    public Map<String, Object> report(LocalDate from, LocalDate to) {
        if (from.isAfter(to))
            throw new IllegalArgumentException("조회 기간을 확인하세요.");
        List<Map<String, Object>> all = query(
                "SELECT A.ID,A.CODE,A.NAME,A.TYPE,COALESCE(SUM(CASE WHEN E.ENTRY_DATE<=? THEN L.DEBIT-L.CREDIT ELSE 0 END),0) BALANCE FROM ACCT_ACCOUNT A LEFT JOIN ACCT_LINE L ON L.ACCOUNT_ID=A.ID LEFT JOIN ACCT_ENTRY E ON E.ID=L.ENTRY_ID GROUP BY A.ID ORDER BY A.CODE",
                to);
        BigInteger asset = BigInteger.ZERO, liability = BigInteger.ZERO, equity = BigInteger.ZERO,
                result = BigInteger.ZERO;
        for (Map<String, Object> a : all) {
            BigInteger n = new BigInteger(a.get("BALANCE").toString());
            switch (a.get("TYPE").toString()) {
            case "ASSET" -> asset = asset.add(n);
            case "LIABILITY" -> liability = liability.subtract(n);
            case "EQUITY" -> equity = equity.subtract(n);
            case "INCOME", "EXPENSE" -> result = result.subtract(n);
            default -> throw new IllegalStateException();
            }
            a.put("BALANCE", n.toString());
        }
        List<Map<String, Object>> movements = query(
                "SELECT E.ENTRY_DATE,A.TYPE,A.NAME,A.CODE,L.DEBIT,L.CREDIT FROM ACCT_LINE L JOIN ACCT_ENTRY E ON E.ID=L.ENTRY_ID JOIN ACCT_ACCOUNT A ON A.ID=L.ACCOUNT_ID WHERE E.ENTRY_DATE BETWEEN ? AND ? ORDER BY E.ENTRY_DATE",
                from, to);
        TreeMap<String, BigInteger[]> months = new TreeMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1))
            months.put(m.toString(), new BigInteger[] { BigInteger.ZERO, BigInteger.ZERO });
        BigInteger income = BigInteger.ZERO, expense = BigInteger.ZERO;
        Map<String, BigInteger> categories = new TreeMap<>();
        for (Map<String, Object> m : movements) {
            String type = m.get("TYPE").toString(), month = m.get("ENTRY_DATE").toString().substring(0, 7);
            BigInteger n = new BigInteger(m.get("DEBIT").toString())
                    .subtract(new BigInteger(m.get("CREDIT").toString()));
            if (type.equals("INCOME")) {
                n = n.negate();
                income = income.add(n);
                months.get(month)[0] = months.get(month)[0].add(n);
            } else if (type.equals("EXPENSE")) {
                expense = expense.add(n);
                months.get(month)[1] = months.get(month)[1].add(n);
            } else
                continue;
            categories.merge(type + ":" + m.get("NAME"), n, BigInteger::add);
        }
        Map<String, String> monthlyOfferings = new HashMap<>();
        for (Map<String, Object> gift : query(
                "SELECT FORMATDATETIME(R.DATE,'yyyy-MM') LABEL,SUM(R.AMOUNT) AMOUNT FROM FINANCIAL_RECORD R JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.CANCELLED=FALSE AND K.STATUS='POSTED' AND K.OFFERING_TYPE_ID IS NOT NULL AND R.DATE BETWEEN ? AND ? GROUP BY FORMATDATETIME(R.DATE,'yyyy-MM')",
                from, to))
            monthlyOfferings.put(gift.get("LABEL").toString(), gift.get("AMOUNT").toString());
        List<Map<String, String>> monthly = new ArrayList<>();
        for (Map.Entry<String, BigInteger[]> m : months.entrySet())
            monthly.add(Map.of("label", m.getKey(), "income", m.getValue()[0].toString(), "expense",
                    m.getValue()[1].toString(), "surplus", m.getValue()[0].subtract(m.getValue()[1]).toString(),
                    "offerings", monthlyOfferings.getOrDefault(m.getKey(), "0")));
        long review = jdbc.queryForObject(
                "SELECT COUNT(*) FROM FINANCIAL_RECORD R LEFT JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.CANCELLED=FALSE AND R.DATE<=? AND (K.STATUS IS NULL OR K.STATUS='REVIEW')",
                Long.class, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("assets", asset.toString());
        out.put("liabilities", liability.toString());
        out.put("netAssets", equity.add(result).toString());
        out.put("income", income.toString());
        out.put("expense", expense.toString());
        out.put("surplus", income.subtract(expense).toString());
        out.put("cashBalance",
                all.stream().filter(a -> Set.of("1000", "1100", "1200").contains(a.get("CODE")))
                        .map(a -> new BigInteger(a.get("BALANCE").toString())).reduce(BigInteger.ZERO, BigInteger::add)
                        .toString());
        out.put("offeringsTotal", String.valueOf(jdbc.queryForObject(
                "SELECT COALESCE(SUM(R.AMOUNT),0) FROM FINANCIAL_RECORD R JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.CANCELLED=FALSE AND K.STATUS='POSTED' AND K.OFFERING_TYPE_ID IS NOT NULL AND R.DATE BETWEEN ? AND ?",
                java.math.BigDecimal.class, from, to)));
        out.put("difference", asset.subtract(liability).subtract(equity).subtract(result).toString());
        out.put("accounts", all);
        out.put("monthly", monthly);
        out.put("categories", categories.entrySet().stream()
                .map(e -> Map.of("label", e.getKey(), "amount", e.getValue().toString())).toList());
        out.put("reviewCount", review);
        out.put("summary", String.format("%s 기준 자산 %s원, 부채 %s원, 순자산 %s원입니다. 조회 기간 수입은 %s원, 비용은 %s원이며 운영결과는 %s원입니다.", to,
                asset, liability, equity.add(result), income, expense, income.subtract(expense)));
        return out;
    }

    public List<Map<String, Object>> donations(LocalDate from, LocalDate to) {
        List<Map<String, Object>> rows = query(
                "SELECT R.ID,R.DATE,R.AMOUNT,T.ID TYPE_ID,T.NAME TYPE_NAME,A.CONTACT_ID,C.NAME CONTACT_NAME,A.AMOUNT SHARE FROM FINANCIAL_RECORD R JOIN ACCT_LINK K ON K.RECORD_ID=R.ID AND K.STATUS='POSTED' JOIN OFFERING_TYPE T ON T.ID=K.OFFERING_TYPE_ID LEFT JOIN OFFERING_ALLOCATION A ON A.RECORD_ID=R.ID LEFT JOIN CONTACT C ON C.ID=A.CONTACT_ID WHERE R.CANCELLED=FALSE AND R.DATE BETWEEN ? AND ? ORDER BY R.DATE,R.ID",
                from, to);
        for (Map<String, Object> r : rows) {
            r.put("AMOUNT", String.valueOf(r.get("AMOUNT")));
            if (r.get("SHARE") != null)
                r.put("SHARE", String.valueOf(r.get("SHARE")));
        }
        return rows;
    }

    /** 공용 응답은 고정 집계 스키마만 사용한다. 원본 이름·ID·메모를 직렬화하지 않는다. */
    public Map<String, Object> publicReport(LocalDate from, LocalDate to) {
        Map<String, Object> internal = report(from, to), out = new LinkedHashMap<>();
        for (String k : List.of("from", "to", "assets", "liabilities", "netAssets", "income", "expense", "surplus",
                "difference", "monthly", "reviewCount", "cashBalance", "offeringsTotal"))
            out.put(k, internal.get(k));
        List<Map<String, Object>> totals = query(
                "SELECT T.NAME,SUM(R.AMOUNT) AMOUNT FROM FINANCIAL_RECORD R JOIN ACCT_LINK K ON K.RECORD_ID=R.ID AND K.STATUS='POSTED' JOIN OFFERING_TYPE T ON T.ID=K.OFFERING_TYPE_ID WHERE R.CANCELLED=FALSE AND R.DATE BETWEEN ? AND ? GROUP BY T.NAME",
                from, to);
        Map<String, BigInteger> safe = new TreeMap<>();
        Set<String> allowed = Set.of("주정헌금", "약정헌금", "감사헌금", "선교헌금", "절기헌금", "특별헌금", "기타헌금");
        for (Map<String, Object> r : totals)
            safe.merge(allowed.contains(r.get("NAME")) ? r.get("NAME").toString() : "기타헌금",
                    new BigInteger(r.get("AMOUNT").toString()), BigInteger::add);
        Map<String, String> labels = Map.ofEntries(Map.entry("1000", "현금"), Map.entry("1100", "보통예금"),
                Map.entry("1200", "기타예금"), Map.entry("1300", "미수금"), Map.entry("2000", "미지급금"),
                Map.entry("2100", "예수금"), Map.entry("2200", "차입금"));
        List<Map<String, String>> composition = new ArrayList<>();
        Map<String, BigInteger> groups = new TreeMap<>();
        for (Object item : (List<?>) internal.get("accounts")) {
            Map<?, ?> a = (Map<?, ?>) item;
            String type = a.get("TYPE").toString();
            if (!Set.of("ASSET", "LIABILITY").contains(type))
                continue;
            String name = labels.getOrDefault(a.get("CODE"), type.equals("ASSET") ? "기타자산" : "기타부채");
            BigInteger amount = new BigInteger(a.get("BALANCE").toString())
                    .multiply(type.equals("ASSET") ? BigInteger.ONE : BigInteger.ONE.negate());
            groups.merge(type + ":" + name, amount, BigInteger::add);
        }
        for (var x : groups.entrySet())
            composition.add(Map.of("label", x.getKey(), "amount", x.getValue().toString()));
        out.put("composition", composition);
        out.put("offerings", safe.entrySet().stream()
                .map(e -> Map.of("label", e.getKey(), "amount", e.getValue().toString())).toList());
        return out;
    }

    public Map<String, Object> data() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts", query("SELECT * FROM ACCT_ACCOUNT ORDER BY CODE"));
        out.put("types", query("SELECT * FROM OFFERING_TYPE ORDER BY ID"));
        out.put("contacts", query("SELECT * FROM CONTACT ORDER BY NAME"));
        out.put("years", query("SELECT * FROM FISCAL_YEAR ORDER BY START_DATE DESC"));
        out.put("budgets", query("SELECT * FROM BUDGET ORDER BY SORT_ORDER,ID"));
        out.put("records", query(
                "SELECT R.*,K.STATUS,K.ENTRY_ID,K.OFFERING_TYPE_ID,K.ASSET_ID,K.COUNTERPART_ID FROM FINANCIAL_RECORD R LEFT JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.CANCELLED=FALSE ORDER BY R.DATE DESC,R.ID DESC"));
        out.put("recordContacts", query("SELECT * FROM FINANCIAL_RECORD_CONTACT"));
        out.put("allocations", query("SELECT * FROM OFFERING_ALLOCATION"));
        out.put("entries", query(
                "SELECT E.*,EXISTS(SELECT 1 FROM ACCT_ENTRY R WHERE R.REVERSES_ID=E.ID) REVERSED FROM ACCT_ENTRY E ORDER BY E.ENTRY_DATE DESC,E.ID DESC"));
        out.put("lines", query(
                "SELECT L.*,A.NAME ACCOUNT_NAME,E.ENTRY_DATE,E.MEMO,E.REVERSES_ID FROM ACCT_LINE L JOIN ACCT_ACCOUNT A ON A.ID=L.ACCOUNT_ID JOIN ACCT_ENTRY E ON E.ID=L.ENTRY_ID ORDER BY E.ID DESC,L.ID"));
        out.put("sharing", query("SELECT KEY_NAME,VALUE_TEXT FROM FINANCE_PREFERENCE"));
        return out;
    }
}
