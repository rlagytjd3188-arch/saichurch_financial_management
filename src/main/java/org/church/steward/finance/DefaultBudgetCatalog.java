package org.church.steward.finance;

import jakarta.persistence.EntityManager;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 실제 예산액을 추정하지 않고, 새 회계 연도에 양쪽 기본 예산을 준비한다. */
@Component
@RequiredArgsConstructor
public class DefaultBudgetCatalog {
    public record Created(long yearId) {
    }

    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    public static final List<String> INCOME = List.of("주정헌금", "약정헌금", "감사헌금", "선교헌금", "절기헌금", "특별헌금", "기타헌금", "기타수입");
    public static final List<String> EXPENSE = List.of("사역비", "예배비", "선교비", "교육비", "관리비", "인건비", "기타비용");

    @EventListener
    @Transactional
    public void created(Created event) {
        entities.flush();
        add(event.yearId(), "INCOME", INCOME);
        add(event.yearId(), "EXPENSE", EXPENSE);
    }

    private void add(long year, String type, List<String> names) {
        for (String name : names)
            jdbc.update(
                    "INSERT INTO BUDGET(FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER) SELECT ?,?,?,0,COALESCE((SELECT MAX(SORT_ORDER)+1 FROM BUDGET WHERE FISCAL_YEAR_ID=? AND TYPE=?),0) WHERE NOT EXISTS(SELECT 1 FROM BUDGET WHERE FISCAL_YEAR_ID=? AND TYPE=? AND NAME=?)",
                    year, type, name, year, type, year, type, name);
    }
}
