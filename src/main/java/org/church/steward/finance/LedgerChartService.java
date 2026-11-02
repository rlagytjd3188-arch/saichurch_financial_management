package org.church.steward.finance;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LedgerChartService {
    private final JdbcTemplate jdbc;

    public Map<Long, ChartCategory> budgetCategories() {
        Map<Long, Map<String, Object>> budgets = new HashMap<>();
        for (var b : jdbc.queryForList("SELECT ID,PARENT_ID,NAME,TYPE FROM BUDGET"))
            budgets.put(FinanceEngine.number(b.get("ID")), b);
        Map<Long, ChartCategory> out = new HashMap<>();
        for (var b : budgets.values()) {
            List<String> path = new ArrayList<>();
            Map<String, Object> current = b;
            Set<Long> seen = new HashSet<>();
            while (current != null && seen.add(FinanceEngine.number(current.get("ID")))) {
                path.addFirst(current.get("NAME").toString());
                current = current.get("PARENT_ID") == null ? null
                        : budgets.get(FinanceEngine.number(current.get("PARENT_ID")));
            }
            out.put(FinanceEngine.number(b.get("ID")), ChartCategory.path(b.get("TYPE").toString(), path));
        }
        return out;
    }

    public static Map<String, String> descriptor(ChartCategory c, String amount) {
        return Map.of("key", c.key(), "label", c.label(), "type", c.type(), "color", c.color(), "amount", amount);
    }

    /** 月별 임의 예산명·키를 공개 응답에 복사하지 않는다. 고정 공개 분류로 재집계한다. */
    public static void publicCharts(Map<String, Object> internal, Map<String, Object> out) {
        List<Map<String, String>> categories = (List<Map<String, String>>) internal.get("chartCategories");
        Map<String, ChartCategory> safe = new LinkedHashMap<>();
        Map<String, java.math.BigInteger> totals = new LinkedHashMap<>();
        for (var c : categories) {
            ChartCategory mapped = new ChartCategory(c.get("key"), c.get("label"), c.get("type"), c.get("color"))
                    .publicCategory();
            safe.put(c.get("key"), mapped);
            totals.merge(mapped.key(), new java.math.BigInteger(c.get("amount")), java.math.BigInteger::add);
        }
        out.put("chartCategories",
                safe.values().stream().distinct().map(c -> descriptor(c, totals.get(c.key()).toString())).toList());
        List<Map<String, String>> gifts = (List<Map<String, String>>) internal.get("offeringCategories");
        Map<String, ChartCategory> safeGifts = new LinkedHashMap<>();
        Map<String, java.math.BigInteger> giftTotals = new LinkedHashMap<>();
        for (var c : gifts) {
            var mapped = new ChartCategory(c.get("key"), c.get("label"), "INCOME", c.get("color")).publicCategory();
            safeGifts.put(c.get("key"), mapped);
            giftTotals.merge(mapped.key(), new java.math.BigInteger(c.get("amount")), java.math.BigInteger::add);
        }
        out.put("offeringCategories", safeGifts.values().stream().distinct().map(c -> {
            var d = new HashMap<>(descriptor(c, giftTotals.get(c.key()).toString()));
            d.put("key", "gift_" + c.key());
            return d;
        }).toList());
        var rows = new ArrayList<Map<String, String>>();
        for (var row : (List<Map<String, String>>) internal.get("monthly")) {
            Map<String, String> clean = new LinkedHashMap<>();
            for (String k : List.of("label", "income", "expense", "surplus", "offerings"))
                clean.put(k, row.get(k));
            safe.forEach((key, c) -> clean.merge(c.key(), row.getOrDefault(key, "0"),
                    (a, b) -> new java.math.BigInteger(a).add(new java.math.BigInteger(b)).toString()));
            safeGifts.forEach((key, c) -> clean.merge("gift_" + c.key(), row.getOrDefault(key, "0"),
                    (a, b) -> new java.math.BigInteger(a).add(new java.math.BigInteger(b)).toString()));
            rows.add(clean);
        }
        out.put("monthly", rows);
        out.put("pledges", internal.get("pledges"));
    }
}
