package org.church.steward.finance;

import java.util.*;
import org.church.steward.domain.financialrecord.entity.FinancialRecord;

public record ChartPart(ChartCategory category, long amount) {
    public static List<ChartPart> records(List<FinancialRecord> records) {
        Map<ChartCategory, Long> totals = new LinkedHashMap<>();
        for (var r : records)
            totals.merge(ChartCategory.budget(r.getBudget(), r.getType().name()), r.getAmount(), Math::addExact);
        return totals.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ChartCategory::label)))
                .map(e -> new ChartPart(e.getKey(), e.getValue())).toList();
    }
}
