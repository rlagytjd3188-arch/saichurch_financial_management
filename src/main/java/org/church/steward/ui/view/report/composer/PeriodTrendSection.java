package org.church.steward.ui.view.report.composer;

import java.awt.Color;
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
        draw(canvas, period + " 수입·지출 그래프 (해당 기간 발생액)", rows, false);
        draw(canvas, period + " 헌금 그래프 (해당 기간 발생액)", rows, true);
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

    private static void draw(ReportCanvas canvas, String title, List<PeriodTrendDto> rows, boolean gift) {
        var font = canvas.getStyle().regular(7f);
        double lineHeight = ReportText.lineHeight(font), ascent = ReportText.ascent(font),
                height = Math.max(136, lineHeight * 9);
        if (!canvas.fits(canvas.sectionHeight(ReportFonts.SIZE_SECTION) + height + 14))
            canvas.newPage();
        canvas.section(title, ReportFonts.SIZE_SECTION, canvas.getLeft());
        long max = 0;
        for (var r : rows)
            max = Math.max(max, gift ? r.offerings() : Math.max(r.income(), r.expense()));
        double top = canvas.getY(), right = canvas.getRight(),
                left = canvas.getLeft() + Math.max(40, ReportText.width(ReportFormats.compactAmount(max), font) + 10),
                bottom = top + height - lineHeight - 7, plotTop = top + lineHeight + 8;
        Color[] colors = gift ? new Color[] { UIColors.REPORT_OFFERING }
                : new Color[] { UIColors.REPORT_BAR, UIColors.REPORT_EXPENSE };
        String[] labels = gift ? new String[] { "헌금" } : new String[] { "수입", "지출" };
        for (int k = 0; k < labels.length; k++) {
            double x = left + k * 75;
            canvas.fillRect(x, top + 2, 7, 7, colors[k]);
            canvas.text(labels[k], font, ReportAlign.LEFT, x + 11, top + ascent);
        }
        if (max == 0) {
            canvas.text(gift ? "선택 기간에 등록된 헌금이 없습니다." : "선택 기간에 수입·지출이 없습니다.", font, ReportAlign.CENTER,
                    (left + right) / 2, (plotTop + bottom) / 2, UIColors.REPORT_TEXT_SUB);
            canvas.moveDown(height + 12);
            return;
        }
        for (int tick = 0; tick <= 4; tick++) {
            double ratio = tick / 4.0, y = bottom - (bottom - plotTop) * ratio;
            canvas.line(left, y, right, y, .4, UIColors.REPORT_GRID);
            canvas.text(ReportFormats.compactAmount(Math.round(max * ratio)), font, ReportAlign.RIGHT, left - 5,
                    y + ascent / 2 - 1,
                    UIColors.REPORT_TEXT_SUB);
        }
        double slot = (right - left) / rows.size(), width = Math.min(15, slot / (colors.length + 1));
        double widest = 0;
        for (var r : rows)
            widest = Math.max(widest,
                    ReportText.width(r.label().length() == 10 ? r.label().substring(5) : r.label(), font));
        int stride = Math.max(Math.max(1, (int) Math.ceil(rows.size() / 12.0)), (int) Math.ceil((widest + 5) / slot));
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            double center = left + slot * (i + .5);
            for (int k = 0; k < colors.length; k++) {
                long value = gift ? r.offerings() : k == 0 ? r.income() : r.expense();
                double bar = (bottom - plotTop) * (double) value / max;
                if (value > 0)
                    canvas.fillRect(center - width * colors.length / 2 + k * width, bottom - bar, width * .85,
                            Math.max(1, bar), colors[k]);
            }
            if ((i % stride == 0 && (rows.size() - 1 - i >= stride || i == 0)) || i == rows.size() - 1)
                canvas.text(r.label().length() == 10 ? r.label().substring(5) : r.label(), font, ReportAlign.CENTER,
                        center, bottom + ascent + 4);
        }
        canvas.moveDown(height + 12);
    }
}
