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

    @Transactional
    public int autoConnectDonors(List<Long> ids) {
        int count = 0;
        for (long id : new LinkedHashSet<>(ids)) {
            var found = engine.query(
                    "SELECT R.* FROM FINANCIAL_RECORD R JOIN FISCAL_YEAR Y ON Y.ID=R.FISCAL_YEAR_ID WHERE R.ID=? AND R.CANCELLED=FALSE AND R.TYPE='INCOME' AND Y.CLOSED=FALSE",
                    id);
            if (found.isEmpty())
                continue;
            var current = engine.query(
                    "SELECT C.NAME FROM FINANCIAL_RECORD_CONTACT RC JOIN CONTACT C ON C.ID=RC.CONTACT_ID WHERE RC.FINANCIAL_RECORD_ID=?",
                    id);
            if (current.isEmpty() || current.stream()
                    .anyMatch(c -> !org.church.steward.infrastructure.spreadsheet.BankStatementNormalizer.CONTACT
                            .equals(c.get("NAME"))))
                continue;
            var matches = engine.automaticContacts(String.valueOf(found.getFirst().get("DESCRIPTION")));
            if (matches.size() != 1)
                continue;
            records.updateFinancialRecordsContacts(List.of(id), matches);
            count++;
        }
        return count;
    }

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
        String kind = b.get("kind").toString();
        long budget = FinanceEngine.number(b.get("budget"));
        List<Long> people = b.get("contacts") instanceof List<?> list
                ? list.stream().map(FinanceEngine::number).toList()
                : b.get("contact") == null ? List.of() : List.of(FinanceEngine.number(b.get("contact")));
        if (people.isEmpty())
            people = engine.automaticContacts(String.valueOf(b.getOrDefault("donorName", "")).isBlank()
                    ? String.valueOf(b.getOrDefault("memo", ""))
                    : b.get("donorName").toString());
        if (people.isEmpty() && !String.valueOf(b.getOrDefault("donorName", "")).isBlank())
            throw new IllegalArgumentException("등록된 이름이 없거나 동명이인입니다. 헌금자를 등록하거나 ‘직접 지정’에서 선택하세요.");
        if (people.isEmpty() && b.get("id") != null)
            people = engine
                    .query("SELECT CONTACT_ID FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?",
                            FinanceEngine.number(b.get("id")))
                    .stream().map(c -> FinanceEngine.number(c.get("CONTACT_ID"))).toList();
        if (people.isEmpty())
            people = engine.unclassifiedContact();
        Long offering = b.containsKey("offering")
                ? (b.get("offering") == null ? null : FinanceEngine.number(b.get("offering")))
                : engine.offeringForBudget(budget, kind);
        long asset = b.get("asset") == null ? engine.defaultAsset() : FinanceEngine.number(b.get("asset"));
        long counterpart = b.get("counterpart") == null ? engine.automaticCounterpart(budget, kind, offering)
                : FinanceEngine.number(b.get("counterpart"));
        if (b.get("id") != null) {
            var old = engine.query(
                    "SELECT R.BUDGET_ID,K.ASSET_ID,K.COUNTERPART_ID,K.OFFERING_TYPE_ID FROM FINANCIAL_RECORD R LEFT JOIN ACCT_LINK K ON K.RECORD_ID=R.ID WHERE R.ID=?",
                    FinanceEngine.number(b.get("id"))).getFirst();
            if (b.get("asset") == null && old.get("ASSET_ID") != null)
                asset = FinanceEngine.number(old.get("ASSET_ID"));
            if (FinanceEngine.number(old.get("BUDGET_ID")) == budget) {
                if (!b.containsKey("offering"))
                    offering = old.get("OFFERING_TYPE_ID") == null ? null
                            : FinanceEngine.number(old.get("OFFERING_TYPE_ID"));
                if (b.get("counterpart") == null && Objects.equals(old.get("OFFERING_TYPE_ID"), offering)
                        && old.get("COUNTERPART_ID") != null)
                    counterpart = FinanceEngine.number(old.get("COUNTERPART_ID"));
            }
        }
        FinancialRecordRequestDto dto = FinancialRecordRequestDto.builder()
                .type(FinancialRecordType.valueOf(b.get("kind").toString()))
                .fiscalYearId(FinanceEngine.number(b.get("year"))).date(LocalDate.parse(b.get("date").toString()))
                .budgetId(FinanceEngine.number(b.get("budget"))).amount(FinanceEngine.number(b.get("amount")))
                .description(String.valueOf(b.getOrDefault("memo", ""))).remarks(remarks)
                .contactIds(people)
                .build();
        long id = b.get("id") == null ? records.createFinancialRecord(dto).getId()
                : records.updateFinancialRecord(FinanceEngine.number(b.get("id")), dto).getId();
        engine.classify(id, asset, counterpart, offering, null);
        return id;
    }
}
