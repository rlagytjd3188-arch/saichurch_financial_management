package org.church.steward.ui.view.report.composer;

import java.util.List;
import org.church.steward.domain.report.dto.PeriodTrendDto;
import org.church.steward.ui.util.UIColors;
import org.church.steward.ui.view.report.layout.*;
import org.church.steward.ui.view.report.layout.ReportTable.Column;

/** 선택 기간의 발생액만 그린다. 누계 표와 같은 페이지 작업대·PDF 출력 경로를 사용한다. */
final class PeriodTrendSection {
    private PeriodTrendSection() {
    }

    static void render(ReportCanvas canvas, String period, List<PeriodTrendDto> rows) {
        if (rows == null || rows.isEmpty())
            return;
        if (period.equals("월간"))
            rows = weeklyRows(rows);
        long step = period.equals("월간") ? 500_000 : period.equals("주간") ? 100_000 : 0;
        draw(canvas, period + " 수입·지출 그래프 (해당 기간 발생액)", rows, false, step);
        draw(canvas, period + " 헌금 그래프 (해당 기간 발생액)", rows, true, step);
        canvas.section(period + " 기간별 금액 (누계 제외)", ReportFonts.SIZE_SECTION, canvas.getLeft());
        ReportTable table = new ReportTable(canvas.getStyle(), 8f, new Column("일자 / 월", 0, ReportAlign.LEFT),
                new Column("수입", 0, ReportAlign.RIGHT), new Column("지출", 0, ReportAlign.RIGHT),
                new Column("차액", 0, ReportAlign.RIGHT), new Column("헌금(수입 중)", 0, ReportAlign.RIGHT));
        long income = 0, expense = 0, offerings = 0;
        for (var r : rows) {
            table.addRow(ReportTable.RowStyle.NORMAL, 0, r.label(), ReportFormats.amount(r.income()),
                    ReportFormats.amount(r.expense()), ReportFormats.amount(r.income() - r.expense()),
                    ReportFormats.amount(r.offerings()));
            income = Math.addExact(income, r.income());
            expense = Math.addExact(expense, r.expense());
            offerings = Math.addExact(offerings, r.offerings());
        }
        table.addRow(ReportTable.RowStyle.TOTAL, 0, "기간 합계", ReportFormats.amount(income),
                ReportFormats.amount(expense), ReportFormats.amount(income - expense), ReportFormats.amount(offerings));
        table.render(canvas);
        canvas.moveDown(10);
    }

    private static void draw(ReportCanvas canvas, String title, List<PeriodTrendDto> rows, boolean gift, long step) {
        var font = canvas.getStyle().regular(7f);
        double lineHeight = ReportText.lineHeight(font), ascent = ReportText.ascent(font);
        java.util.Map<String, org.church.steward.finance.ChartCategory> categories = new java.util.LinkedHashMap<>();
        for (var row : rows)
            for (var p : parts(row, gift))
                if (p.amount() != 0)
                    categories.put(p.category().key(), p.category());
        double lx = 0, legendHeight = lineHeight;
        for (var c : categories.values()) {
            double w = ReportText.width(c.label(), font) + 20;
            if (lx + w > canvas.getContentWidth()) {
                lx = 0;
                legendHeight += lineHeight;
            }
            lx += w;
        }
        long dataMax = 0;
        for (var r : rows)
            dataMax = Math.max(dataMax, gift ? r.offerings() : Math.max(r.income(), r.expense()));
        long max = step == 0 ? dataMax
                : Math.multiplyExact(Math.max(1, Math.floorDiv(Math.addExact(dataMax, step - 1), step)), step);
        long intervals = step == 0 ? 4 : max / step;
        double height = Math.max(146, Math.min(460, intervals * (lineHeight + 2) + 45)) + legendHeight;
        if (!canvas.fits(canvas.sectionHeight(ReportFonts.SIZE_SECTION) + height + 14))
            canvas.newPage();
        canvas.section(title + " · 분류별", ReportFonts.SIZE_SECTION, canvas.getLeft());
        double top = canvas.getY();
        lx = canvas.getLeft();
        double ly = top;
        for (var c : categories.values()) {
            double w = ReportText.width(c.label(), font) + 20;
            if (lx + w > canvas.getRight()) {
                lx = canvas.getLeft();
                ly += lineHeight;
            }
            canvas.fillRect(lx, ly + 2, 7, 7, c.awtColor());
            canvas.text(c.label(), font, ReportAlign.LEFT, lx + 10, ly + ascent);
            lx += w;
        }
        double right = canvas.getRight(),
                left = canvas.getLeft() + Math.max(40,
                        ReportText.width((step == 0 ? ReportFormats.compactAmount(max) : axis(max)), font) + 10),
                bottom = top + height - lineHeight - 7, plotTop = top + legendHeight + 8;
        if (dataMax == 0) {
            canvas.text(gift ? "선택 기간에 등록된 헌금이 없습니다." : "선택 기간에 수입·지출이 없습니다.", font, ReportAlign.CENTER,
                    (left + right) / 2, (plotTop + bottom) / 2, UIColors.REPORT_TEXT_SUB);
            canvas.moveDown(height + 12);
            return;
        }
        long labelStride = Math.max(1,
                (long) Math.ceil(intervals / Math.max(1, (bottom - plotTop) / (lineHeight + 2))));
        long gridStride = Math.max(1, (intervals + 1999) / 2000);
        for (long tick = 0; tick <= intervals; tick += gridStride) {
            long value = step == 0 ? Math.round(max * (tick / (double) intervals)) : Math.multiplyExact(tick, step);
            double y = bottom - (bottom - plotTop) * tick / intervals;
            canvas.line(left, y, right, y, .4, UIColors.REPORT_GRID);
            if (tick % labelStride == 0 || tick == intervals)
                canvas.text(step == 0 ? ReportFormats.compactAmount(value) : axis(value), font, ReportAlign.RIGHT,
                        left - 5, y + ascent / 2 - 1, UIColors.REPORT_TEXT_SUB);
        }
        int groups = gift ? 1 : 2;
        double slot = (right - left) / rows.size(), width = Math.min(15, slot / (groups + 1));
        double widest = 0;
        for (var r : rows)
            widest = Math.max(widest,
                    ReportText.width(axisLabel(r.label()), font));
        int stride = Math.max(Math.max(1, (int) Math.ceil(rows.size() / 12.0)), (int) Math.ceil((widest + 5) / slot));
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            double center = left + slot * (i + .5);
            for (int group = 0; group < groups; group++) {
                long accumulated = 0;
                for (var p : parts(r, gift)) {
                    if (!gift && !p.category().type().equals(group == 0 ? "INCOME" : "EXPENSE"))
                        continue;
                    long next = Math.addExact(accumulated, p.amount());
                    double y1 = bottom - (bottom - plotTop) * (double) accumulated / max,
                            y2 = bottom - (bottom - plotTop) * (double) next / max;
                    if (p.amount() > 0)
                        canvas.fillRect(center - width * groups / 2 + group * width, y2, width * .85, y1 - y2,
                                p.category().awtColor());
                    accumulated = next;
                }
                if (accumulated > 0 && step > 0) {
                    var valueFont = canvas.getStyle().regular(6f);
                    double valueY = bottom - (bottom - plotTop) * (double) accumulated / max - 4;
                    canvas.text(String.format("%,d원", accumulated), valueFont,
                            gift ? ReportAlign.CENTER : group == 0 ? ReportAlign.RIGHT : ReportAlign.LEFT,
                            gift ? center : group == 0 ? center - 2 : center + 2, valueY,
                            gift || group == 0 ? UIColors.INCOME : UIColors.EXPENSE);
                }
            }
            if ((i % stride == 0 && (rows.size() - 1 - i >= stride || i == 0)) || i == rows.size() - 1)
                canvas.text(axisLabel(r.label()), font, ReportAlign.CENTER,
                        center, bottom + ascent + 4);
        }
        canvas.moveDown(height + 12);
    }

    static String axis(long value) {
        return value == 0 ? "0원" : String.format("%,d만원", value / 10_000);
    }

    private static String axisLabel(String label) {
        if (label.contains(" ~ ")) {
            var dates = label.split(" ~ ");
            return dates[0].substring(5).replace('-', '/') + "~" + dates[1].substring(5).replace('-', '/');
        }
        return label.length() == 10 ? label.substring(5) : label;
    }

    static List<PeriodTrendDto> weeklyRows(List<PeriodTrendDto> rows) {
        if (rows.stream().anyMatch(r -> r.label().length() != 10))
            return rows;
        var weeks = new java.util.TreeMap<java.time.LocalDate, java.util.List<PeriodTrendDto>>();
        for (var r : rows) {
            var week = java.time.LocalDate.parse(r.label())
                    .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.SUNDAY));
            weeks.computeIfAbsent(week, _ -> new java.util.ArrayList<>()).add(r);
        }
        var result = new java.util.ArrayList<PeriodTrendDto>();
        for (var week : weeks.values()) {
            long income = 0, expense = 0, gifts = 0;
            var amounts = new java.util.LinkedHashMap<org.church.steward.finance.ChartCategory, Long>();
            var offerings = new java.util.LinkedHashMap<org.church.steward.finance.ChartCategory, Long>();
            for (var r : week) {
                income = Math.addExact(income, r.income());
                expense = Math.addExact(expense, r.expense());
                gifts = Math.addExact(gifts, r.offerings());
                for (var p : parts(r, false))
                    amounts.merge(p.category(), p.amount(), Math::addExact);
                for (var p : parts(r, true))
                    offerings.merge(p.category(), p.amount(), Math::addExact);
            }
            result.add(new PeriodTrendDto(week.getFirst().label() + " ~ " + week.getLast().label(), income, expense,
                    gifts,
                    amounts.entrySet().stream()
                            .map(e -> new org.church.steward.finance.ChartPart(e.getKey(), e.getValue())).toList(),
                    offerings.entrySet().stream()
                            .map(e -> new org.church.steward.finance.ChartPart(e.getKey(), e.getValue())).toList()));
        }
        return result;
    }

    private static List<org.church.steward.finance.ChartPart> parts(PeriodTrendDto row, boolean gift) {
        var parts = gift ? row.offeringBreakdown() : row.breakdown();
        if (parts != null && !parts.isEmpty())
            return parts;
        if (gift)
            return List.of(new org.church.steward.finance.ChartPart(
                    org.church.steward.finance.ChartCategory.named("INCOME", "헌금"), row.offerings()));
        return List.of(
                new org.church.steward.finance.ChartPart(org.church.steward.finance.ChartCategory.named("INCOME", "수입"),
                        row.income()),
                new org.church.steward.finance.ChartPart(
                        org.church.steward.finance.ChartCategory.named("EXPENSE", "지출"), row.expense()));
    }
}
