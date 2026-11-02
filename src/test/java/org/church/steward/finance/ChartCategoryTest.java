package org.church.steward.finance;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartCategoryTest {
    @Test
    void majorOfferingsAreDistinctAndDefaultCategoriesCollapseToOneIdentity() {
        var offerings = List.of("약정헌금", "주정헌금", "선교헌금", "감사헌금").stream().map(ChartCategory::offering).toList();
        var colors = new java.util.ArrayList<>(offerings.stream().map(ChartCategory::color).toList());
        colors.add(ExternalChurchService.COLOR);
        colors.add(ChartCategory.INCOME_COLOR);
        colors.add(ChartCategory.EXPENSE_COLOR);
        assertThat(colors).doesNotHaveDuplicates();
        assertThat(offerings).extracting(ChartCategory::label).containsExactly("약정헌금", "주정헌금", "선교헌금", "감사헌금");
        var income = ChartCategory.named("INCOME", "기타수입");
        for (var name : List.of("임의 수입", "보이드이", "식사비용", "기타수입"))
            assertThat(ChartCategory.named("INCOME", name)).isEqualTo(income);
        assertThat(ChartCategory.offering("특별헌금")).isEqualTo(income);
        var expense = ChartCategory.named("EXPENSE", "지출");
        for (var name : List.of("관리비", "교육비", "물품/시설관리", "월세", "식사비용"))
            assertThat(ChartCategory.named("EXPENSE", name)).isEqualTo(expense);
        assertThat(ChartCategory.path("INCOME", List.of("헌금수입", "약정헌금", "세부 항목")))
                .isEqualTo(ChartCategory.offering("약정헌금"));
        assertThat(income.publicCategory()).isEqualTo(income);
        assertThat(expense.publicCategory()).isEqualTo(expense);
        assertThat(ExternalChurchService.category().publicCategory()).isEqualTo(ExternalChurchService.category());
    }
}
