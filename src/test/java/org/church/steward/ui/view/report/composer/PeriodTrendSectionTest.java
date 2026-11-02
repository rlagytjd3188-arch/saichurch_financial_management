package org.church.steward.ui.view.report.composer;

import static org.assertj.core.api.Assertions.*;
import org.church.steward.finance.ChartCategory;
import org.church.steward.finance.ChartPart;
import java.time.LocalDate;
import java.util.*;
import org.church.steward.domain.report.dto.PeriodTrendDto;
import org.church.steward.ui.view.report.layout.*;
import org.junit.jupiter.api.Test;

class PeriodTrendSectionTest {
    private List<ReportElement> elements(String period, List<PeriodTrendDto> rows) {
        var canvas = new ReportCanvas("검증", ReportStyle.DEFAULT);
        PeriodTrendSection.render(canvas, period, rows);
        var doc = canvas.finish();
        var out = new ArrayList<ReportElement>();
        for (int p = 0; p < doc.getPageCount(); p++)
            out.addAll(doc.getElements(p));
        return out;
    }

    private List<String> texts(String period, List<PeriodTrendDto> rows) {
        return elements(period, rows).stream().filter(ReportElement.Text.class::isInstance)
                .map(ReportElement.Text.class::cast).map(ReportElement.Text::text).toList();
    }

    @Test
    void monthlyBarsUseCalendarWeeksHalfMillionTicksAndExactValues() {
        var rows = new ArrayList<PeriodTrendDto>();
        for (int d = 1; d <= 30; d++)
            rows.add(new PeriodTrendDto(LocalDate.of(2026, 11, d).toString(),
                    d == 1 || d == 7 ? 600000 : d == 8 ? 100000 : 0, d == 1 ? 300000 : 0, 0));
        var weeks = PeriodTrendSection.weeklyRows(rows);
        assertThat(weeks).hasSize(5);
        assertThat(weeks.getFirst().income()).isEqualTo(1200000);
        assertThat(weeks.getFirst().label()).isEqualTo("2026-11-01 ~ 2026-11-07");
        assertThat(weeks.getLast().label()).isEqualTo("2026-11-29 ~ 2026-11-30");
        assertThat(weeks.stream().mapToLong(PeriodTrendDto::income).sum()).isEqualTo(1300000);
        assertThat(texts("월간", rows)).contains("50만원", "100만원", "150만원", "1,200,000원", "1,300,000원", "300,000원",
                "11/01~11/07", "월간 합계");
    }

    @Test
    void weeklyBarsKeepDailyAxisAndOneHundredThousandTicks() {
        var out = texts("주간", List.of(new PeriodTrendDto("2026-11-01", 250000, 100000, 0)));
        assertThat(out).contains("10만원", "20만원", "30만원", "250,000원", "100,000원", "11-01");
        assertThat(out).noneMatch(t -> t.matches(".*\\d+\\.\\d+만원.*"));
        var annual = texts("연간", List.of(new PeriodTrendDto("2026-11", 250000, 100000, 0)));
        assertThat(annual).contains("250,000원", "100,000원", "연간 합계");
        assertThat(annual.stream().filter(t -> t.equals("250,000원")).count()).isEqualTo(1);
    }

    @Test
    void periodTotalsAreRightmostKeepCategoryColorsAndDoNotDoubleTheTable() {
        var pledge = ChartCategory.offering("약정헌금");
        var regular = ChartCategory.offering("주정헌금");
        var expense = ChartCategory.named("EXPENSE", "지출");
        var first = List.of(new ChartPart(pledge, 200000), new ChartPart(regular, 50000),
                new ChartPart(expense, 100000));
        var second = List.of(new ChartPart(pledge, 100000), new ChartPart(regular, 50000),
                new ChartPart(expense, 50000));
        var rows = List.of(new PeriodTrendDto("2026-11-01", 250000, 100000, 250000, first, first.subList(0, 2)),
                new PeriodTrendDto("2026-11-02", 150000, 50000, 150000, second, second.subList(0, 2)));
        var all = elements("주간", rows);
        int nextGraph = java.util.stream.IntStream.range(0, all.size())
                .filter(i -> all.get(i) instanceof ReportElement.Text t && t.text().startsWith("주간 헌금 그래프")).findFirst()
                .orElseThrow();
        var graph = all.subList(0, nextGraph);
        var labels = graph.stream().filter(ReportElement.Text.class::isInstance).map(ReportElement.Text.class::cast)
                .toList();
        var totalLabel = labels.stream().filter(t -> t.text().equals("주간 합계")).findFirst().orElseThrow();
        assertThat(ReportFonts.resourceOf(totalLabel.font())).endsWith("-Bold.ttf");
        assertThat(totalLabel.x())
                .isGreaterThan(labels.stream().filter(t -> t.text().equals("11-02")).findFirst().orElseThrow().x());
        assertThat(labels).extracting(ReportElement.Text::text).contains("40만원", "400,000원", "150,000원");
        for (var category : List.of(pledge, regular, expense)) {
            var bars = graph.stream().filter(ReportElement.Rect.class::isInstance).map(ReportElement.Rect.class::cast)
                    .filter(r -> category.awtColor().equals(r.fill()) && r.width() > 7)
                    .sorted(Comparator.comparingDouble(ReportElement.Rect::x)).toList();
            assertThat(bars).hasSize(3);
            assertThat(bars.getLast().height()).isCloseTo(bars.get(0).height() + bars.get(1).height(), within(.0001));
        }
        var text = all.stream().filter(ReportElement.Text.class::isInstance).map(ReportElement.Text.class::cast)
                .map(ReportElement.Text::text).toList();
        assertThat(text.stream().filter(t -> t.equals("주간 합계")).count()).isEqualTo(2);
        assertThat(text.stream().filter(t -> t.equals("기간 합계")).count()).isEqualTo(1);
        assertThat(text).contains("400,000", "150,000").doesNotContain("800,000", "800,000원");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().mapToLong(PeriodTrendDto::income).sum()).isEqualTo(400000);
    }

    @Test
    void emptyPeriodsStillRenderWithoutInventingTransactions() {
        assertThat(texts("월간", List.of(new PeriodTrendDto("2026-11-01", 0, 0, 0))))
                .contains("선택 기간에 수입·지출이 없습니다.", "선택 기간에 등록된 헌금이 없습니다.", "기간 합계");
    }

    @Test
    void annualTotalsReserveSpaceForLargeIncomeAndExpenseLabels() {
        var rows = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(m -> new PeriodTrendDto(String.format("2026-%02d", m), 20000000, 30000000, 0)).toList();
        var canvas = new ReportCanvas("검증", ReportStyle.DEFAULT);
        var annotations = elements("연간", rows).stream().filter(ReportElement.Text.class::isInstance)
                .map(ReportElement.Text.class::cast).filter(t -> t.text().matches("[0-9,]+원")).toList();
        assertThat(annotations).extracting(ReportElement.Text::text).contains("240,000,000원", "360,000,000원");
        for (var annotation : annotations) {
            assertThat(annotation.x()).isGreaterThanOrEqualTo(canvas.getLeft());
            assertThat(annotation.x() + ReportText.width(annotation.text(), annotation.font()))
                    .isLessThanOrEqualTo(canvas.getRight());
        }
    }

}
