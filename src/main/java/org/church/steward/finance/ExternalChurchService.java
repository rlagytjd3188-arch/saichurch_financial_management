package org.church.steward.finance;

import java.time.LocalDate;
import java.util.*;
import org.church.steward.domain.financialrecord.entity.*;
import org.church.steward.infrastructure.security.UserSessionHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

/** 외부 교회 명부와 원본 거래의 연결. 금액·예산·기존 비고는 변경하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExternalChurchService {
    public static final String MARKER = "외부교회헌금";
    public static final String COLOR = "#F28E2B";
    private final JdbcTemplate jdbc;
    private final UserSessionHolder sessions;

    public static String note(String raw, boolean external) {
        if (!external || raw != null && raw.contains(MARKER))
            return raw;
        return raw == null || raw.isBlank() ? MARKER : raw + " · " + MARKER;
    }

    public static ChartCategory category() {
        return ChartCategory.named("INCOME", MARKER);
    }

    public List<Map<String, Object>> list(LocalDate from, LocalDate to) {
        var rows = jdbc.queryForList(
                "SELECT C.*,COALESCE(SUM(CASE WHEN R.CANCELLED=FALSE AND R.TYPE='INCOME' AND R.DATE BETWEEN ? AND ? THEN R.AMOUNT ELSE 0 END),0) AMOUNT,COUNT(CASE WHEN R.CANCELLED=FALSE AND R.TYPE='INCOME' AND R.DATE BETWEEN ? AND ? THEN 1 END) RECORD_COUNT FROM EXTERNAL_CHURCH C LEFT JOIN FINANCIAL_RECORD R ON R.EXTERNAL_CHURCH_ID=C.ID GROUP BY C.ID ORDER BY C.NAME",
                from, to, from, to);
        for (var row : rows)
            row.put("AMOUNT", row.get("AMOUNT").toString());
        return rows;
    }

    private List<Map<String, Object>> churches() {
        return jdbc.queryForList("SELECT * FROM EXTERNAL_CHURCH ORDER BY ID");
    }

    private static List<String> names(Map<String, Object> c) {
        var out = new ArrayList<String>();
        out.add(c.get("NAME").toString());
        out.addAll(Arrays.stream(c.get("ALIASES").toString().split("[,\\n]")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList());
        return out;
    }

    private Long match(List<String> sources, Long existing) {
        Set<Long> found = new HashSet<>();
        for (var c : churches()) {
            long id = FinanceEngine.number(c.get("ID"));
            if (!Boolean.TRUE.equals(c.get("ACTIVE")) && !Objects.equals(existing, id))
                continue;
            for (String alias : names(c))
                for (String source : sources)
                    if (!ContactNameMatcher
                            .match(source, Map.of(id, alias), List.of("헌금", "정기헌금", "정기후원", "후원금", "지원금", "입금", MARKER))
                            .isEmpty())
                        found.add(id);
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }

    public Long resolve(FinancialRecord record) {
        if (record.getType() != FinancialRecordType.INCOME)
            return null;
        if (record.isExternalChurchManual())
            return record.getExternalChurchId();
        var sources = new ArrayList<String>();
        sources.add(record.getDescription());
        sources.add(record.getRemarks());
        for (var c : record.getContacts())
            sources.add(c.getContactName());
        return match(sources, record.getExternalChurchId());
    }

    @Transactional
    public void save(Map<String, Object> b) {
        Long id = b.get("id") == null ? null : FinanceEngine.number(b.get("id"));
        String name = Objects.toString(b.get("name"), "").trim(),
                aliases = Objects.toString(b.get("aliases"), "").trim(),
                notes = Objects.toString(b.get("notes"), "").trim();
        boolean active = !Boolean.FALSE.equals(b.get("active"));
        if (name.isBlank() || name.length() > 100 || aliases.length() > 500 || notes.length() > 500)
            throw new IllegalArgumentException("교회명과 입금자명·메모 길이를 확인하세요.");
        if (id != null && jdbc.queryForObject("SELECT COUNT(*) FROM EXTERNAL_CHURCH WHERE ID=?", Long.class, id) == 0)
            throw new IllegalArgumentException("외부 교회를 찾을 수 없습니다.");
        if (id != null) {
            String previous = jdbc.queryForObject("SELECT NAME FROM EXTERNAL_CHURCH WHERE ID=?", String.class, id);
            if (!previous.equals(name))
                aliases = aliases.isBlank() ? previous : aliases + "," + previous;
            if (aliases.length() > 500)
                throw new IllegalArgumentException("이전 교회명을 보존할 수 있도록 입금자명을 500자 이내로 정리하세요.");
        }
        var proposed = names(Map.of("NAME", name, "ALIASES", aliases));
        for (var old : churches())
            if (!Objects.equals(id, FinanceEngine.number(old.get("ID")))) {
                if (name.equals(old.get("NAME")))
                    throw new IllegalArgumentException("이미 등록된 교회명입니다.");
                if (active && Boolean.TRUE.equals(old.get("ACTIVE")))
                    for (String a : proposed)
                        for (String other : names(old))
                            if (!ContactNameMatcher.match(a, Map.of(1L, other), List.of()).isEmpty())
                                throw new IllegalArgumentException("다른 교회와 입금자명이 겹칩니다.");
            }
        if (id == null)
            jdbc.update("INSERT INTO EXTERNAL_CHURCH(NAME,ALIASES,NOTES,ACTIVE) VALUES(?,?,?,?)", name, aliases, notes,
                    active);
        else
            jdbc.update("UPDATE EXTERNAL_CHURCH SET NAME=?,ALIASES=?,NOTES=?,ACTIVE=? WHERE ID=?", name, aliases, notes,
                    active, id);
        reconcile();
        audit("외부 교회 등록·수정: " + name);
    }

    @Transactional
    public void reconcile() {
        var rows = jdbc.queryForList(
                "SELECT R.ID,R.DESCRIPTION,R.REMARKS,R.EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD R JOIN FISCAL_YEAR Y ON Y.ID=R.FISCAL_YEAR_ID WHERE R.TYPE='INCOME' AND R.CANCELLED=FALSE AND Y.CLOSED=FALSE AND R.EXTERNAL_CHURCH_MANUAL=FALSE");
        for (var r : rows) {
            var sources = new ArrayList<String>();
            sources.add((String) r.get("DESCRIPTION"));
            sources.add((String) r.get("REMARKS"));
            for (var c : jdbc.queryForList(
                    "SELECT CONTACT_NAME FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?", r.get("ID")))
                sources.add((String) c.get("CONTACT_NAME"));
            Long existing = r.get("EXTERNAL_CHURCH_ID") == null ? null
                    : FinanceEngine.number(r.get("EXTERNAL_CHURCH_ID"));
            jdbc.update("UPDATE FINANCIAL_RECORD SET EXTERNAL_CHURCH_ID=? WHERE ID=?", match(sources, existing),
                    r.get("ID"));
        }
    }

    @Transactional
    public void link(List<Long> ids, Long church) {
        if (church != null
                && jdbc.queryForObject("SELECT COUNT(*) FROM EXTERNAL_CHURCH WHERE ID=?", Long.class, church) == 0)
            throw new IllegalArgumentException("외부 교회를 찾을 수 없습니다.");
        for (long id : new LinkedHashSet<>(ids)) {
            if (jdbc.queryForObject(
                    "SELECT COUNT(*) FROM FINANCIAL_RECORD R JOIN FISCAL_YEAR Y ON Y.ID=R.FISCAL_YEAR_ID WHERE R.ID=? AND R.TYPE='INCOME' AND R.CANCELLED=FALSE AND Y.CLOSED=FALSE",
                    Long.class, id) == 0)
                throw new IllegalArgumentException("마감하지 않은 회계 연도의 수입을 선택하세요.");
            jdbc.update("UPDATE FINANCIAL_RECORD SET EXTERNAL_CHURCH_ID=?,EXTERNAL_CHURCH_MANUAL=TRUE WHERE ID=?",
                    church, id);
        }
        audit("외부 교회 거래 연결: " + ids);
    }

    private void audit(String text) {
        jdbc.update(
                "INSERT INTO ACTIVITY_LOG(USER_ID,ACTIVITY_TYPE,TARGET_NAME,DESCRIPTION,CREATED_AT) VALUES(?,'UPDATE','외부 교회',?,CURRENT_TIMESTAMP)",
                sessions.getLoggedInUser() == null ? "시스템" : sessions.getLoggedInUser().id(), text);
    }
}
