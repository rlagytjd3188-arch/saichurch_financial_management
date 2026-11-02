package org.church.steward.ui.view.report.composer;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import java.util.*;
import org.church.steward.domain.report.dto.PeriodTrendDto;
import org.church.steward.ui.view.report.layout.*;
import org.junit.jupiter.api.Test;

class PeriodTrendSectionTest {
    private List<String> texts(String period, List<PeriodTrendDto> rows) {
        var canvas = new ReportCanvas("검증", ReportStyle.DEFAULT);
        PeriodTrendSection.render(canvas, period, rows);
        var doc = canvas.finish();
        var out = new ArrayList<String>();
        for (int p = 0; p < doc.getPageCount(); p++)
            for (var e : doc.getElements(p))
                if (e instanceof ReportElement.Text t)
                    out.add(t.text());
        return out;
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
        assertThat(texts("월간", rows)).contains("50만원", "100만원", "150만원", "1,200,000원", "300,000원", "11/01~11/07");
    }

    @Test
    void weeklyBarsKeepDailyAxisAndOneHundredThousandTicks() {
        var out = texts("주간", List.of(new PeriodTrendDto("2026-11-01", 250000, 100000, 0)));
        assertThat(out).contains("10만원", "20만원", "30만원", "250,000원", "100,000원", "11-01");
        assertThat(out).noneMatch(t -> t.matches(".*\\d+\\.\\d+만원.*"));
        assertThat(texts("연간", List.of(new PeriodTrendDto("2026-11", 250000, 100000, 0)))).doesNotContain("250,000원",
                "100,000원");
    }
}
