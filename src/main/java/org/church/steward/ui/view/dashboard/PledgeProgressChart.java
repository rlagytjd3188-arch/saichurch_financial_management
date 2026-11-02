package org.church.steward.ui.view.dashboard;

import java.awt.*;
import javax.swing.*;
import org.church.steward.finance.PledgeService;
import org.church.steward.ui.util.UIColors;

/** 원 단위 목표선과 혼동하지 않도록 달성률(%)은 별도의 축과 그래프로 그린다. */
class PledgeProgressChart extends JComponent {
    private PledgeService.Summary summary;

    void setData(PledgeService.Summary summary) {
        this.summary = summary;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = DashboardGraphics.create(graphics);
        try {
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.setColor(UIColors.TEXT_SUB);
            if (summary == null || summary.target() == 0) {
                g.drawString("월 약정액을 등록하면 달성률이 표시됩니다.", 10, 35);
                return;
            }
            g.drawString(
                    String.format("약정 %,d원 · 납부 %,d원 · %.1f%%", summary.target(), summary.actual(), summary.percent()),
                    8, 16);
            g.drawString("점선: 약정 목표 100%   실선: 실제 달성률", 8, 32);
            double max = 120;
            for (var m : summary.monthly())
                if (m.percent() != null)
                    max = Math.max(max, m.percent() * 1.1);
            int left = 38, right = getWidth() - 12, top = 48, bottom = getHeight() - 24;
            if (bottom <= top)
                return;
            for (int tick = 0; tick <= 4; tick++) {
                double value = max * tick / 4;
                int y = bottom - (int) ((bottom - top) * value / max);
                g.setColor(UIColors.CHART_GRID);
                g.drawLine(left, y, right, y);
                g.setColor(UIColors.TEXT_SUB);
                g.drawString(Math.round(value) + "%", 0, y + 3);
            }
            int hundred = bottom - (int) ((bottom - top) * 100 / max);
            g.setColor(Color.GRAY);
            g.setStroke(new BasicStroke(1, 0, 0, 1, new float[] { 5, 3 }, 0));
            g.drawLine(left, hundred, right, hundred);
            g.setStroke(new BasicStroke(2));
            g.setColor(org.church.steward.finance.ChartCategory.offering("약정헌금").awtColor());
            int px = -1, py = 0, n = summary.monthly().size();
            for (int i = 0; i < n; i++) {
                var m = summary.monthly().get(i);
                int x = left + (int) ((right - left) * (i + .5) / n);
                if (i % Math.max(1, (n + 5) / 6) == 0)
                    g.drawString(m.label().substring(5), x - 7, bottom + 15);
                if (m.percent() == null) {
                    px = -1;
                    continue;
                }
                int y = bottom - (int) ((bottom - top) * m.percent() / max);
                if (px >= 0)
                    g.drawLine(px, py, x, y);
                g.fillOval(x - 3, y - 3, 6, 6);
                px = x;
                py = y;
            }
        } finally {
            g.dispose();
        }
    }
}
