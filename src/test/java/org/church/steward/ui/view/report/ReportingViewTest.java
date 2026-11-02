package org.church.steward.ui.view.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.Container;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.church.steward.domain.fiscalyear.dto.FiscalYearDto;
import org.church.steward.domain.report.controller.ReportController;
import org.church.steward.domain.report.dto.*;
import org.church.steward.domain.report.entity.*;
import org.church.steward.ui.view.main.FiscalYearSelector;
import org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.OutputPart;
import org.church.steward.ui.view.report.layout.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReportingViewTest {
    @Test
    void outputRangeChangesTheDisplayedDocumentAndPreservesAnnexesWithAutoFit() throws Exception {
        var controller = mock(ReportController.class);
        var selector = mock(FiscalYearSelector.class);
        when(selector.getSelectedFiscalYearId()).thenReturn(1L);
        when(selector.getSelectedFiscalYear()).thenReturn(FiscalYearDto.builder().id(1L).name("검증 연도")
                .startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31)).build());
        when(controller.getWeeklySettlementReport(anyLong(), any(), any())).thenReturn(WeeklySettlementReportDto
                .builder()
                .fiscalYearName("검증 연도").startDate(LocalDate.of(2026, 10, 4)).endDate(LocalDate.of(2026, 10, 10))
                .weekly(ReportSummaryDto.builder().income(1000).expense(500).build()).incomeStatus(List.of())
                .expenseStatus(List.of())
                .incomeRecords(List.of(group("수입 기록", 1000))).expenseRecords(List.of(group("지출 기록", 500))).build());
        when(controller.getReportSetting(any())).thenAnswer(a -> ReportSettingDto.builder().reportType(a.getArgument(0))
                .fontFamily(ReportSettingConstraints.DEFAULT_FONT_FAMILY).fontSizeStep(0).lineSpacing(130).autoFit(true)
                .viewMode(ReportSettingConstraints.DEFAULT_VIEW_MODE)
                .zoomMode(ReportSettingConstraints.DEFAULT_ZOOM_MODE).zoomPercent(100).build());
        AtomicReference<ReportingView> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var view = new ReportingView(controller, selector);
            ref.set(view);
            ((JComboBox<?>) ReflectionTestUtils.getField(view, "typeCombo"))
                    .setSelectedItem(ReportType.WEEKLY_SETTLEMENT);
            view.onMenuSelected();
        });
        var view = ref.get();
        var full = awaitDocument(view, ReportType.WEEKLY_SETTLEMENT, OutputPart.ALL);
        assertThat(full.getPageCount()).isEqualTo(3);
        assertThat(full.getStyle().fontSizeStep()).isZero();
        SwingUtilities.invokeAndWait(() -> {
            view.setSize(720, 600);
            layout(view);
            var combo = (JComboBox<?>) ReflectionTestUtils.getField(view, "partCombo");
            assertThat(combo.isVisible()).isTrue();
            assertThat(combo.getY() + combo.getHeight()).isLessThanOrEqualTo(combo.getParent().getHeight());
            combo.setSelectedItem(OutputPart.INCOME);
        });
        var income = awaitDocument(view, ReportType.WEEKLY_SETTLEMENT, OutputPart.INCOME);
        assertThat(income.getPageCount()).isEqualTo(1);
        assertThat(text(income)).contains("수입 기록").doesNotContain("지출 기록");
        SwingUtilities.invokeAndWait(() -> ((JComboBox<?>) ReflectionTestUtils.getField(view, "typeCombo"))
                .setSelectedItem(ReportType.MONTHLY_SETTLEMENT));
        assertThat(awaitDocument(view, ReportType.MONTHLY_SETTLEMENT, OutputPart.INCOME).getTitle()).contains("월간");
        SwingUtilities.invokeAndWait(() -> ((JComboBox<?>) ReflectionTestUtils.getField(view, "partCombo"))
                .setSelectedItem(OutputPart.EXPENSE));
        assertThat(text(awaitDocument(view, ReportType.MONTHLY_SETTLEMENT, OutputPart.EXPENSE))).contains("지출 기록")
                .doesNotContain("수입 기록");
    }

    private static ReportRecordGroupDto group(String text, long amount) {
        return ReportRecordGroupDto.builder().budgetName("예산").totalAmount(amount)
                .records(List.of(ReportRecordDto.builder()
                        .date(LocalDate.of(2026, 10, 4)).contactNames(List.of("검증 이름")).description(text).amount(amount)
                        .remarks("제외할 비고").build()))
                .build();
    }

    private static ReportDocument awaitDocument(ReportingView view, ReportType type, OutputPart part) throws Exception {
        AtomicReference<ReportDocument> ref = new AtomicReference<>();
        for (int i = 0; i < 500 && ref.get() == null; i++) {
            SwingUtilities.invokeAndWait(() -> {
                var loaded = ReflectionTestUtils.getField(view, "loadedReport");
                if (loaded != null && ReflectionTestUtils.getField(loaded, "type") == type
                        && ReflectionTestUtils.getField(loaded, "part") == part)
                    ref.set((ReportDocument) ReflectionTestUtils.getField(loaded, "document"));
            });
            if (ref.get() == null)
                Thread.sleep(20);
        }
        assertThat(ref.get()).isNotNull();
        return ref.get();
    }

    private static String text(ReportDocument doc) {
        var text = new StringBuilder();
        for (int p = 0; p < doc.getPageCount(); p++)
            for (var e : doc.getElements(p))
                if (e instanceof ReportElement.Text t)
                    text.append(t.text()).append('\n');
        return text.toString();
    }

    private static void layout(Container root) {
        root.doLayout();
        for (var child : root.getComponents())
            if (child instanceof Container container)
                layout(container);
    }
}
