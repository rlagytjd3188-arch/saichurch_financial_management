package org.church.steward.finance;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.church.steward.domain.budget.entity.Budget;

/** 금액과 무관한 고정 색상. 일반 예산은 1차, 헌금수입만 2차까지 구분한다. */
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
        Map<String, String> known = Map.ofEntries(
                Map.entry("주정헌금", "#2878B5"), Map.entry("약정헌금", "#8B5CB8"),
                Map.entry("감사헌금", "#269B83"), Map.entry("선교헌금", "#CE8A20"),
                Map.entry("절기헌금", "#D85E88"), Map.entry("특별헌금", "#4B9AB4"),
                Map.entry("기타헌금", "#7F8A56"), Map.entry("기타수입", "#627D98"),
                Map.entry("사역비", "#CF693F"), Map.entry("예배비", "#B18A28"),
                Map.entry("선교비", "#418A69"), Map.entry("교육비", "#6576B8"),
                Map.entry("관리비", "#A65C92"), Map.entry("인건비", "#A14F4F"), Map.entry("기타비용", "#7B7970"));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            String color = known.get(leaf);
            if (color == null) {
                float hue = (((hash[0] & 255) << 8) | (hash[1] & 255)) / 65536f;
                color = String.format("#%06X", Color.HSBtoRGB(hue, .65f, .72f) & 0xffffff);
            }
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
