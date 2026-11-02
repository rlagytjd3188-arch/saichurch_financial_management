package org.church.steward.ui.view.report.composer;

import java.awt.Color;
import org.church.steward.finance.PledgeService;
import org.church.steward.ui.util.UIColors;
import org.church.steward.ui.view.report.layout.*;
import org.church.steward.ui.view.report.layout.ReportTable.Column;

final class PledgeSection {
    private static final Color ACTUAL = Color.decode("#A8202B"), TARGET = Color.decode("#627D98");

    static void render(ReportCanvas canvas, PledgeService.Summary summary) {
        if (summary == null || summary.target() == 0 && summary.actual() == 0)
            return;
        draw(canvas, summary, false);
        draw(canvas, summary, true);
        canvas.section("약정헌금 월별 현황 · 월 약정 기준", ReportFonts.SIZE_SECTION, canvas.getLeft());
        var table = new ReportTable(canvas.getStyle(), 8, new Column("월", 0, ReportAlign.LEFT),
                new Column("약정 목표(100%)", 0, ReportAlign.RIGHT), new Column("실제 납부", 0, ReportAlign.RIGHT),
                new Column("달성률", 0, ReportAlign.RIGHT));
        for (var m : summary.monthly())
            table.addRow(ReportTable.RowStyle.NORMAL, 0, m.label(), ReportFormats.amount(m.target()),
                    ReportFormats.amount(m.actual()), rate(m.percent()));
        table.addRow(ReportTable.RowStyle.TOTAL, 0, "합계", ReportFormats.amount(summary.target()),
                ReportFormats.amount(summary.actual()), rate(summary.percent()));
        table.render(canvas);
        canvas.moveDown(4);
        var font = canvas.getStyle().regular(7);
        canvas.text("집계: " + summary.from() + " ~ " + summary.to() + " · 주간도 월초부터 납부한 월 누계 / 목표는 월 약정 전액", font,
                ReportAlign.LEFT, canvas.getLeft(), canvas.getY() + ReportText.ascent(font));
        canvas.moveDown(ReportText.lineHeight(font) + 10);
    }

    private static String rate(Double rate) {
        return rate == null ? "약정 없음" : String.format("%.1f%%", rate);
    }

    private static void draw(ReportCanvas canvas, PledgeService.Summary s, boolean percent) {
        double height = 142;
        var font = canvas.getStyle().regular(7);
        double lh = ReportText.lineHeight(font);
        height = Math.max(height, lh * 10);
        if (!canvas.fits(canvas.sectionHeight(ReportFonts.SIZE_SECTION) + height + 10))
            canvas.newPage();
        canvas.section(percent ? "약정헌금 달성률 · 목표 100%와 실제(%)" : "약정헌금 목표·납부액 · 약정 100% 목표선", ReportFonts.SIZE_SECTION,
                canvas.getLeft());
        double top = canvas.getY(), left = canvas.getLeft() + 42, right = canvas.getRight(),
                bottom = top + height - lh - 6, plot = top + lh + 8;
        canvas.text(percent ? "목표 100% (점선) / 실제 달성률 (빨간색 실선)" : "약정 목표(100%, 점선) / 실제 납부액(빨간색 실선)", font,
                ReportAlign.LEFT, left, top + ReportText.ascent(font));
        double max = percent ? 120 : 1;
        for (var m : s.monthly())
            max = Math.max(max,
                    percent ? (m.percent() == null ? 0 : m.percent() * 1.1) : Math.max(m.target(), m.actual()));
        for (int tick = 0; tick <= 4; tick++) {
            double value = max * tick / 4, y = bottom - (bottom - plot) * tick / 4;
            canvas.line(left, y, right, y, .4, UIColors.REPORT_GRID);
            canvas.text(percent ? Math.round(value) + "%" : ReportFormats.compactAmount(Math.round(value)), font,
                    ReportAlign.RIGHT, left - 5, y + ReportText.ascent(font) / 2, UIColors.REPORT_TEXT_SUB);
        }
        double slot = (right - left) / s.monthly().size();
        for (int series = 0; series < 2; series++) {
            double px = 0, py = 0;
            boolean previous = false;
            for (int i = 0; i < s.monthly().size(); i++) {
                var m = s.monthly().get(i);
                Double value;
                if (percent) {
                    if (series == 0)
                        value = m.target() == 0 ? null : Double.valueOf(100);
                    else
                        value = m.percent();
                } else
                    value = (double) (series == 0 ? m.target() : m.actual());
                if (value == null) {
                    previous = false;
                    continue;
                }
                double x = left + slot * (i + .5), y = bottom - (bottom - plot) * value / max;
                if (s.monthly().size() == 1) {
                    if (series == 0)
                        for (double dx = left; dx < right; dx += 7)
                            canvas.line(dx, y, Math.min(right, dx + 4), y, 1, TARGET);
                    else
                        canvas.line(left, y, right, y, 1.5, ACTUAL);
                }
                if (previous) {
                    if (series == 0) {
                        int steps = Math.max(1, (int) (Math.abs(x - px) / 5));
                        for (int k = 0; k < steps; k += 2) {
                            double a = (double) k / steps, b = (double) Math.min(k + 1, steps) / steps;
                            canvas.line(px + (x - px) * a, py + (y - py) * a, px + (x - px) * b, py + (y - py) * b, 1,
                                    TARGET);
                        }
                    } else
                        canvas.line(px, py, x, y, 1.5, ACTUAL);
                }
                canvas.fillRect(x - 1.5, y - 1.5, 3, 3, series == 0 ? TARGET : ACTUAL);
                px = x;
                py = y;
                previous = true;
                if (series == 0 && (i % Math.max(1, (s.monthly().size() + 11) / 12) == 0))
                    canvas.text(m.label(), font, ReportAlign.CENTER, x, bottom + lh);
            }
        }
        canvas.moveDown(height + 10);
    }
}
