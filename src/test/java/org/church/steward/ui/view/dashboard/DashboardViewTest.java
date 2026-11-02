package org.church.steward.ui.view.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.church.steward.domain.dashboard.controller.DashboardController;
import org.church.steward.domain.dashboard.dto.FiscalYearSummaryDto;
import org.church.steward.domain.fiscalyear.dto.FiscalYearDto;
import org.church.steward.ui.view.main.FiscalYearSelector;
import org.junit.jupiter.api.Test;

class DashboardViewTest {
    @Test
    void displaysOpeningAndClosingBalanceAndRefreshesAtMinimumWidth() throws Exception {
        var controller = mock(DashboardController.class);
        var selector = mock(FiscalYearSelector.class);
        when(selector.getSelectedFiscalYear()).thenReturn(FiscalYearDto.builder().id(1L).name("검증 연도")
                .startDate(LocalDate.of(2044, 1, 1)).endDate(LocalDate.of(2044, 12, 31)).build());
        when(controller.getFiscalYearSummary(1L)).thenReturn(summary(100000, 5070000));
        AtomicReference<DashboardView> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            ref.set(new DashboardView(controller, selector));
        });
        DashboardView view = ref.get();
        load(view, view::onMenuSelected);
        SwingUtilities.invokeAndWait(() -> {
            view.setSize(720, 600);
            layout(view);
            List<JLabel> labels = components(view).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .toList();
            assertThat(labels.stream().map(JLabel::getText)).contains("기초잔액·이월액", "기말 잔액", "5,000,000원", "5,070,000원",
                    "100,000원", "30,000원");
            for (JLabel label : labels) {
                if ("기초잔액·이월액".equals(label.getText()) || "기말 잔액".equals(label.getText()))
                    assertThat(label.getFontMetrics(label.getFont()).stringWidth(label.getText()))
                            .isLessThanOrEqualTo(label.getWidth());
            }
            String output = System.getenv("SAI_DASHBOARD_SAMPLE");
            if (output != null) {
                try {
                    var path = Path.of(output);
                    Files.createDirectories(path.toAbsolutePath().getParent());
                    var image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    view.printAll(graphics);
                    graphics.dispose();
                    ImageIO.write(image, "png", path.toFile());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        when(controller.getFiscalYearSummary(1L)).thenReturn(summary(150000, 5120000));
        load(view, () -> components(view).stream().filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(b -> "새로고침".equals(b.getToolTipText())).findFirst().orElseThrow().doClick());
        SwingUtilities.invokeAndWait(() -> assertThat(components(view).stream().filter(JLabel.class::isInstance)
                .map(JLabel.class::cast).map(JLabel::getText)).contains("5,120,000원", "150,000원"));
    }

    private static FiscalYearSummaryDto summary(long income, long balance) {
        return FiscalYearSummaryDto.builder().openingBalance(5000000).carriedFunds(5000000)
                .totalIncome(income).totalExpense(30000).currentBalance(balance).build();
    }

    private static void load(DashboardView view, Runnable action) throws Exception {
        var done = new CountDownLatch(1);
        java.beans.PropertyChangeListener listener = e -> {
            if (Boolean.TRUE.equals(e.getNewValue()))
                done.countDown();
        };
        SwingUtilities.invokeAndWait(() -> {
            view.addPropertyChangeListener("enabled", listener);
            action.run();
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> view.removePropertyChangeListener("enabled", listener));
    }

    private static List<Component> components(Container root) {
        var result = new ArrayList<Component>();
        for (Component c : root.getComponents()) {
            result.add(c);
            if (c instanceof Container child)
                result.addAll(components(child));
        }
        return result;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents())
            if (child instanceof Container container)
                layout(container);
    }
}
