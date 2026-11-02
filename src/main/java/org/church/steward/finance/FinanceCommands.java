package org.church.steward.finance;

import java.util.*;
import java.time.LocalDate;
import org.church.steward.domain.financialrecord.service.FinancialRecordService;
import org.church.steward.domain.financialrecord.dto.FinancialRecordRequestDto;
import org.church.steward.domain.financialrecord.entity.FinancialRecordType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FinanceCommands {
    private final FinancialRecordService records;
    private final FinanceEngine engine;

    /** 헌금/거래 입력과 회계 연결 중 어느 단계가 실패해도 전체를 되돌린다. */
    @Transactional
    public long record(Map<String, Object> b) {
        String remarks = (String) b.get("remarks");
        if (!b.containsKey("remarks") && b.get("id") != null) {
            var original = engine.query("SELECT REMARKS FROM FINANCIAL_RECORD WHERE ID=? AND CANCELLED=FALSE",
                    FinanceEngine.number(b.get("id")));
            if (original.isEmpty())
                throw new IllegalArgumentException("원본 거래를 찾을 수 없습니다.");
            remarks = (String) original.getFirst().get("REMARKS");
        }
        FinancialRecordRequestDto dto = FinancialRecordRequestDto.builder()
                .type(FinancialRecordType.valueOf(b.get("kind").toString()))
                .fiscalYearId(FinanceEngine.number(b.get("year"))).date(LocalDate.parse(b.get("date").toString()))
                .budgetId(FinanceEngine.number(b.get("budget"))).amount(FinanceEngine.number(b.get("amount")))
                .description(String.valueOf(b.getOrDefault("memo", ""))).remarks(remarks)
                .contactIds(
                        b.get("contacts") instanceof List<?> list ? list.stream().map(FinanceEngine::number).toList()
                                : List.of(FinanceEngine.number(b.get("contact"))))
                .build();
        long id = b.get("id") == null ? records.createFinancialRecord(dto).getId()
                : records.updateFinancialRecord(FinanceEngine.number(b.get("id")), dto).getId();
        engine.classify(id, FinanceEngine.number(b.get("asset")), FinanceEngine.number(b.get("counterpart")),
                b.get("offering") == null ? null : FinanceEngine.number(b.get("offering")), null);
        return id;
    }
}
