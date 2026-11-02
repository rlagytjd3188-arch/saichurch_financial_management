package org.church.steward.finance;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartCategoryTest {
    @Test
    void onlyFourOfferingsHaveDistinctRedShadesAndExpensesUseDefaultBlue() {
        var offerings = List.of("약정헌금", "주정헌금", "선교헌금", "감사헌금").stream().map(ChartCategory::offering).toList();
        assertThat(offerings).extracting(ChartCategory::color).doesNotHaveDuplicates();
        for (var category : offerings) {
            var c = category.awtColor();
            assertThat(c.getRed()).isGreaterThan(c.getBlue()).isGreaterThan(c.getGreen());
        }
        String income = ChartCategory.named("INCOME", "기타수입").color();
        assertThat(ChartCategory.named("INCOME", "임의 수입").color()).isEqualTo(income);
        assertThat(ChartCategory.offering("특별헌금").color()).isEqualTo(income);
        var expense = ChartCategory.named("EXPENSE", "관리비");
        assertThat(ChartCategory.named("EXPENSE", "교육비").color()).isEqualTo(expense.color());
        assertThat(expense.awtColor().getBlue()).isGreaterThan(expense.awtColor().getRed())
                .isGreaterThan(expense.awtColor().getGreen());
    }
}
