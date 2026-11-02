package org.church.steward.finance;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.church.steward.domain.budget.entity.Budget;

/** 그래프 전용 분류. 주요 헌금은 구별되는 색, 나머지는 기타수입·지출로 합친다. */
public record ChartCategory(String key, String label, String type, String color) {
    public static final String INCOME_COLOR = "#7A7F87", EXPENSE_COLOR = "#2878B5";
    private static final Map<String, String> OFFERING_COLORS = Map.of(
            "약정헌금", "#D73027", "주정헌금", "#00876C", "선교헌금", "#7B3294", "감사헌금", "#E6AB02");

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
        if (name.equals(ExternalChurchService.MARKER))
            return ExternalChurchService.category();
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
        String leaf = label.contains(" / ") ? label.substring(label.lastIndexOf(" / ") + 3) : label;
        if (type.equals("INCOME"))
            label = label.equals(ExternalChurchService.MARKER) ? label
                    : OFFERING_COLORS.containsKey(leaf) ? leaf : "기타수입";
        else if (type.equals("EXPENSE"))
            label = "지출";
        String identity = type + ":" + label;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            String color = label.equals(ExternalChurchService.MARKER) ? ExternalChurchService.COLOR
                    : type.equals("EXPENSE") ? EXPENSE_COLOR
                            : type.equals("INCOME") ? OFFERING_COLORS.getOrDefault(label, INCOME_COLOR) : "#627D98";
            return new ChartCategory("cat_" + HexFormat.of().formatHex(hash, 0, 10), label, type, color);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Color awtColor() {
        return Color.decode(color);
    }

    public ChartCategory publicCategory() {
        return named(type, label);
    }
}
