package org.church.steward.finance;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.church.steward.domain.budget.entity.Budget;

/** 수입은 빨간 계열, 지출은 파란 기본색. 약정·주정·선교·감사헌금만 빨간색 안에서 구분한다. */
public record ChartCategory(String key, String label, String type, String color) {
    public static boolean offeringRoot(String name) {
        return Set.of("헌금", "헌금수입", "헌금수익").contains(name.replaceAll("\\s", ""));
    }

    public static ChartCategory budget(Budget budget, String type) {
        var path = new ArrayList<String>();
        for (Budget b = budget; b != null; b = b.getParent())
            path.addFirst(b.getName());
        return path(type, path);
    }

    public static ChartCategory offering(String name) {
        return path("INCOME", List.of("헌금수입", name));
    }

    public static ChartCategory path(String type, List<String> path) {
        String label = path.isEmpty() ? (type.equals("INCOME") ? "미분류 수입" : "미분류 지출") : path.getFirst();
        if (type.equals("INCOME") && offeringRoot(label))
            label = "헌금수입" + (path.size() > 1 ? " / " + path.get(1) : " / 미분류");
        else if (type.equals("INCOME") && DefaultBudgetCatalog.INCOME.contains(label) && !label.equals("기타수입"))
            label = "헌금수입 / " + label;
        return named(type, label);
    }

    public static ChartCategory named(String type, String label) {
        String identity = type + ":" + label;
        String leaf = label.contains(" / ") ? label.substring(label.lastIndexOf(" / ") + 3) : label;
        Map<String, String> offerings = Map.of("약정헌금", "#A8202B", "주정헌금", "#E04545", "선교헌금", "#F06B6B", "감사헌금",
                "#C84468");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            String color = type.equals("EXPENSE") ? "#2878B5"
                    : type.equals("INCOME") ? offerings.getOrDefault(leaf, "#D94747") : "#627D98";
            return new ChartCategory("cat_" + HexFormat.of().formatHex(hash, 0, 10), label, type, color);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Color awtColor() {
        return Color.decode(color);
    }

    public ChartCategory publicCategory() {
        if (label.startsWith("헌금수입 / ")) {
            String leaf = label.substring("헌금수입 / ".length());
            return offering(DefaultBudgetCatalog.INCOME.contains(leaf) && !leaf.equals("기타수입") ? leaf : "기타헌금");
        }
        var allowed = type.equals("INCOME") ? DefaultBudgetCatalog.INCOME : DefaultBudgetCatalog.EXPENSE;
        return named(type, allowed.contains(label) ? label : type.equals("INCOME") ? "기타수입" : "기타비용");
    }
}
