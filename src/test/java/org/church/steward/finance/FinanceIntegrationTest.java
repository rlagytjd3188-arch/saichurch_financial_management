package org.church.steward.finance;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.church.steward.domain.financialrecord.dto.FinancialRecordRequestDto;
import org.church.steward.domain.financialrecord.entity.FinancialRecordType;
import org.church.steward.domain.financialrecord.service.FinancialRecordService;
import org.church.steward.infrastructure.security.*;
import org.church.steward.domain.user.entity.UserRole;
import org.church.steward.ui.view.main.MainView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.flywaydb.core.Flyway;

@SpringBootTest(properties = "version=test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class FinanceIntegrationTest {
    @MockitoBean
    MainView mainView;
    @Autowired
    FinanceEngine engine;
    @Autowired
    FinanceCommands commands;
    @Autowired
    PledgeService pledges;
    @Autowired
    ExternalChurchService externalChurches;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FinancialRecordService records;
    @Autowired
    UserSessionHolder sessions;
    @Autowired
    org.church.steward.domain.report.service.ReportService reportService;
    @Autowired
    org.church.steward.domain.dashboard.service.DashboardService dashboardService;
    @Autowired
    org.church.steward.domain.fiscalyear.service.FiscalYearService fiscalYears;
    @Autowired
    DefaultBudgetCatalog defaultBudgets;
    @Autowired
    org.church.steward.domain.datatransfer.service.DataExportService exports;

    @Test
    @DisplayName("월간 보고서는 윤년 월말과 전월 이월액을 반영하고 월간 제목으로 PDF를 저장한다")
    void monthlyReportAndPdf(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        record(true, 100000);
        long feb = record(true, 50000);
        records.updateFinancialRecord(feb, FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(9001L)
                .date(LocalDate.of(2044, 2, 29)).amount(50000).contactIds(List.of(9003L)).build());
        long march = record(true, 25000);
        records.updateFinancialRecord(march, FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(9001L)
                .date(LocalDate.of(2044, 3, 1)).amount(25000).contactIds(List.of(9003L)).build());
        var monthly = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 2, 1),
                LocalDate.of(2044, 2, 29));
        assertThat(monthly.getPreviousBalance()).isEqualTo(100000);
        assertThat(monthly.getWeekly().getIncome()).isEqualTo(50000);
        var doc = org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(monthly,
                org.church.steward.ui.view.report.layout.ReportStyle.DEFAULT, true);
        var output = temp.resolve("monthly.pdf");
        org.church.steward.ui.view.report.output.ReportPdfExporter.export(doc, output);
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text).contains("월간 회계 결산 보고서", "월간 수입", "전월 이월액", "50,000").doesNotContain("주간 수입", "25,000");
        }
    }

    @Test
    @DisplayName("수입·지출 기본 예산을 중복 없이 생성하고 기존 금액을 유지한다")
    void bothBudgetsKeepExistingAmounts() {
        defaultBudgets.created(new DefaultBudgetCatalog.Created(9000));
        jdbc.update("UPDATE BUDGET SET AMOUNT=123456 WHERE FISCAL_YEAR_ID=9000 AND TYPE='INCOME' AND NAME='주정헌금'");
        defaultBudgets.created(new DefaultBudgetCatalog.Created(9000));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM BUDGET WHERE FISCAL_YEAR_ID=9000 AND TYPE='INCOME' AND NAME='주정헌금'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT AMOUNT FROM BUDGET WHERE FISCAL_YEAR_ID=9000 AND TYPE='INCOME' AND NAME='주정헌금'", Long.class))
                .isEqualTo(123456);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BUDGET WHERE FISCAL_YEAR_ID=9000 AND TYPE='EXPENSE'",
                Integer.class)).isEqualTo(8);
        fiscalYears.createFiscalYear("새 기본예산 검증", LocalDate.of(2045, 1, 1), LocalDate.of(2045, 12, 31));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM BUDGET B JOIN FISCAL_YEAR Y ON Y.ID=B.FISCAL_YEAR_ID WHERE Y.NAME='새 기본예산 검증'",
                Integer.class)).isEqualTo(15);
    }

    @Test
    @DisplayName("모든 XLSX·CSV 양식에 머리글과 두 작성 예시가 실제 파일로 저장된다")
    void exampleTemplatesAreRealFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var reader = new org.church.steward.infrastructure.spreadsheet.SpreadsheetReader();
        for (var format : org.church.steward.infrastructure.spreadsheet.SpreadsheetFormat.values()) {
            var budget = temp.resolve("budget." + format.getExtension());
            var records = temp.resolve("records." + format.getExtension());
            var contacts = temp.resolve("contacts." + format.getExtension());
            exports.exportBudgetTemplate(budget, format);
            exports.exportFinancialRecordTemplate(records, format);
            exports.exportContactTemplate(contacts, format);
            for (var file : List.of(budget, records, contacts)) {
                var sheet = reader.read(file, null, null, java.nio.charset.StandardCharsets.UTF_8);
                assertThat(sheet.rows()).hasSize(3);
                if (file.equals(budget))
                    assertThat(sheet.toString()).contains("수입", "지출", "주정헌금", "선교비");
                else
                    assertThat(sheet.toString()).contains("예시");
                assertThat(java.nio.file.Files.size(file)).isGreaterThan(30);
            }
        }
    }

    @Autowired
    FinanceWeb web;
    @Autowired
    org.church.steward.domain.user.repository.UserRepository users;
    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;

    @Test
    @DisplayName("카카오뱅크 안내문·음수 출금·거래일시를 자동 인식해 중복 없이 가져온다")
    void kakaoBankImportPreservesTransactions() {
        jdbc.update("UPDATE BUDGET SET NAME='기타수입' WHERE ID=9001");
        jdbc.update("UPDATE BUDGET SET NAME='기타비용' WHERE ID=9002");
        jdbc.update("INSERT INTO CONTACT(ID,TYPE,NAME,IS_HIDDEN) VALUES(9006,'OTHER','통장거래(미분류)',FALSE)");
        List<List<Object>> raw = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            raw.add(List.of("안내문"));
        raw.add(List.of("", "거래일시", "구분", "거래금액", "거래 후 잔액", "거래구분", "내용", "메모"));
        raw.add(List.of("", "2044.01.05 09:00:00", "입금", "1,000", "11,000", "이체", "검증 개인 이름", "메모"));
        raw.add(List.of("", "2044.01.05 10:00:00", "출금", "-500", "10,500", "이체", "합성 출금", ""));
        raw.add(List.of("", "2044.01.05 11:00:00", "입금", "2,000", "12,500", "이체", "합성 입금", ""));
        var session = new org.church.steward.ui.view.datamanagement.recordimport.RecordImportSession();
        session.setData(java.nio.file.Path.of("bank.xlsx"), null, true, List.of("내역"), "내역", null,
                new org.church.steward.infrastructure.spreadsheet.SpreadsheetData(raw, null));
        session.guessMappingIfNeeded();
        assertThat(session.isBankStatement()).isTrue();
        assertThat(session.validateMapping()).isNull();
        var request = session.toRequest();
        var analysis = recordImporter.analyze(9000L, request);
        assertThat(analysis.getItems()).hasSize(3)
                .allMatch(org.church.steward.domain.datatransfer.dto.RecordImportItemDto::isSelectable);
        var keys = analysis.getItems().stream()
                .map(org.church.steward.domain.datatransfer.dto.RecordImportItemDto::getKey)
                .collect(java.util.stream.Collectors.toSet());
        recordImporter.importRecords(9000L, request, keys, analysis.getSignature(), activity.getLatestLogId(),
                "bank.xlsx");
        assertThat(engine.report(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 1, 31)).get("income")).isEqualTo("3000");
        assertThat(engine.report(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 1, 31)).get("expense")).isEqualTo("500");
        assertThat(engine.donations(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 1, 31))).isEmpty();
        assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD_CONTACT WHERE CONTACT_ID=9003", Long.class))
                .isEqualTo(1L);
        jdbc.update("UPDATE CONTACT SET IS_HIDDEN=TRUE WHERE ID=9003");
        assertThat(recordImporter.analyze(9000L, request).getItems())
                .allMatch(org.church.steward.domain.datatransfer.dto.RecordImportItemDto::isDuplicate);
        balanced();
    }

    private final LocalDate date = LocalDate.of(2044, 1, 5);

    @Test
    void externalChurchRegistrationLinksOldAndNewIncomeWithoutChangingOriginalMemoOrTotals() {
        long old = commands.record(Map.of("kind", "INCOME", "year", 9000, "budget", 9001, "date", date.toString(),
                "amount", 300000, "contact", 9003, "memo", "12:34:00 · 은행표시명", "remarks", "기존 비고"));
        externalChurches.save(Map.of("name", "검증 외부 교회", "aliases", "은행표시명", "notes", "정기 후원"));
        long church = FinanceEngine.number(externalChurches.list(date, date).getFirst().get("ID"));
        assertThat(jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, old))
                .isEqualTo(church);
        assertThat(jdbc.queryForObject("SELECT REMARKS FROM FINANCIAL_RECORD WHERE ID=?", String.class, old))
                .isEqualTo("기존 비고");
        var list = records
                .listFinancialRecords(org.church.steward.domain.financialrecord.dto.FinancialRecordSearchRequestDto
                        .builder().fiscalYearId(9000L).build());
        assertThat(list.getFirst().getRemarks()).isEqualTo("기존 비고 · 외부교회헌금");
        assertThat(list.getFirst().getRawRemarks()).isEqualTo("기존 비고");
        long next = commands.record(Map.of("kind", "INCOME", "year", 9000, "budget", 9001, "date", date.toString(),
                "amount", 100000, "contact", 9003, "memo", "검증 외부 교회"));
        assertThat(jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, next))
                .isEqualTo(church);
        var report = engine.report(date, date);
        assertThat(report).containsEntry("income", "400000").containsEntry("cashBalance", "400000")
                .containsEntry("offeringsTotal", "400000");
        assertThat((List<Map<String, String>>) report.get("chartCategories")).anySatisfy(c -> {
            assertThat(c.get("label")).isEqualTo("외부교회헌금");
            assertThat(c.get("color")).isEqualTo(ExternalChurchService.COLOR);
            assertThat(c.get("amount")).isEqualTo("400000");
        });
        assertThat(dashboardService.getMonthlyTrend(9000L).getFirst().getBreakdown()).anySatisfy(p -> {
            assertThat(p.category().label()).isEqualTo("외부교회헌금");
            assertThat(p.amount()).isEqualTo(400000);
        });
        assertThat(reportService.getWeeklySettlementReport(9000L, date, date).getPeriodTrend().getFirst()
                .offeringBreakdown())
                .anySatisfy(p -> assertThat(p.category().color()).isEqualTo(ExternalChurchService.COLOR));
        assertThat(externalChurches.list(date, date).getFirst().get("AMOUNT")).isEqualTo("400000");
        externalChurches.save(Map.of("id", church, "name", "변경된 외부 교회", "aliases", "은행표시명"));
        assertThat(externalChurches.list(date, date).getFirst().get("AMOUNT")).isEqualTo("400000");
        pledge(9003, 100000, "2044-01", null);
        assertThat(pledges.summary(date, date).actual()).isZero();
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(engine.publicReport(date, date));
        assertThat(json).contains("외부교회헌금").doesNotContain("검증 외부 교회", "변경된 외부 교회", "은행표시명", "기존 비고");
        records.deleteFinancialRecords(List.of(old));
        assertThat(externalChurches.list(date, date).getFirst().get("AMOUNT")).isEqualTo("100000");
        balanced();
    }

    @Test
    void externalChurchManualLinksAndExpenseExclusionAreStable() {
        externalChurches.save(Map.of("name", "유일 교회", "aliases", "유일입금"));
        long church = FinanceEngine.number(externalChurches.list(date, date).getFirst().get("ID"));
        assertThatThrownBy(() -> externalChurches.save(Map.of("name", "다른 교회", "aliases", "유일입금")))
                .isInstanceOf(IllegalArgumentException.class);
        long expense = commands.record(Map.of("kind", "EXPENSE", "year", 9000, "budget", 9002, "date", date.toString(),
                "amount", 10000, "contact", 9004, "memo", "유일 교회"));
        assertThat(
                jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, expense))
                .isNull();
        long income = record(true, 50000);
        assertThatThrownBy(() -> externalChurches.link(List.of(income, expense), church))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, income))
                .isNull();
        externalChurches.link(List.of(income), church);
        records.updateFinancialRecord(income, FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(9001L)
                .date(date).amount(50000).description("별도 수동 연결").contactIds(List.of(9003L)).build());
        assertThat(
                jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, income))
                .isEqualTo(church);
        externalChurches.link(List.of(income), null);
        externalChurches.reconcile();
        assertThat(
                jdbc.queryForObject("SELECT EXTERNAL_CHURCH_ID FROM FINANCIAL_RECORD WHERE ID=?", Long.class, income))
                .isNull();
    }

    private void pledge(long contact, long amount, String start, String end) {
        var body = new HashMap<String, Object>();
        body.put("contact", contact);
        body.put("amount", amount);
        body.put("start", start);
        body.put("end", end);
        pledges.save(body);
    }

    @Test
    void monthlyPledgesUseActualOfferingAllocationsWithoutDoubleCounting() {
        pledge(9003, 100000, "2044-01", "2044-03");
        pledge(9005, 200000, "2044-02", null);
        long paid = record(true, 80000);
        engine.classify(paid, account("1100"), account("4000"), giftType(), Map.of(9003L, 80000L));
        long feb = datedRecord(true, 150000, LocalDate.of(2044, 2, 5));
        records.updateFinancialRecordsContacts(List.of(feb), List.of(9003L, 9005L));
        engine.classify(feb, account("1100"), account("4000"), giftType(), Map.of(9003L, 100000L, 9005L, 50000L));
        long unallocated = datedRecord(true, 20000, LocalDate.of(2044, 2, 7));
        records.updateFinancialRecordsContacts(List.of(unallocated), List.of(9003L, 9005L));
        engine.classify(unallocated, account("1100"), account("4000"), giftType(), null);
        var summary = pledges.summary(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 2, 29));
        assertThat(summary.target()).isEqualTo(400000);
        assertThat(summary.actual()).isEqualTo(250000);
        assertThat(summary.percent()).isEqualTo(62.5);
        assertThat(summary.allocated()).isEqualTo(230000);
        assertThat(summary.unallocated()).isEqualTo(20000);
        assertThat(summary.monthly().getFirst().percent()).isEqualTo(80);
        assertThat(summary.people()).anySatisfy(p -> {
            assertThat(p.id()).isEqualTo(9003);
            assertThat(p.target()).isEqualTo(200000);
            assertThat(p.actual()).isEqualTo(180000);
            assertThat(p.percent()).isEqualTo(90);
        });
        assertThat(summary.people()).anySatisfy(p -> {
            assertThat(p.id()).isEqualTo(9005);
            assertThat(p.percent()).isEqualTo(25);
        });
        records.deleteFinancialRecords(List.of(paid));
        assertThat(pledges.summary(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 2, 29)).actual()).isEqualTo(170000);
        assertThat(pledges.summary(LocalDate.of(2044, 2, 6), LocalDate.of(2044, 2, 8)).actual()).isEqualTo(170000);
    }

    @Test
    void pledgeHistoryDoesNotOverlapAndPreservesOldMonthlyTargets() {
        pledge(9003, 100000, "2044-01", "2044-03");
        assertThatThrownBy(() -> pledge(9003, 200000, "2044-03", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("겹칩니다");
        pledge(9003, 200000, "2044-04", null);
        var summary = pledges.summary(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 12, 31));
        assertThat(summary.target()).isEqualTo(2100000);
        assertThat(summary.monthly().get(2).target()).isEqualTo(100000);
        assertThat(summary.monthly().get(3).target()).isEqualTo(200000);
        var crossMonth = reportService
                .getWeeklySettlementReport(9000L, LocalDate.of(2044, 3, 29), LocalDate.of(2044, 4, 4)).getPledges();
        assertThat(crossMonth.from()).isEqualTo(LocalDate.of(2044, 4, 1));
        assertThat(crossMonth.target()).isEqualTo(200000);
        assertThatThrownBy(() -> pledge(9003, 0, "2045-01", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pledge(9004, 10000, "2044-01", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pledge(9003, 10000, "2045-03", "2045-02"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.report(date, date).get("income")).isEqualTo("0");
        assertThat(dashboardService.getFiscalYearSummary(9000L).getPledges().target()).isEqualTo(2100000);
        assertThat(reportService.getAnnualSettlementReport(9000L).getPledges().target()).isEqualTo(2100000);
        long id = FinanceEngine.number(pledges.commitments().getLast().get("ID"));
        pledges.cancel(id);
        assertThat(pledges.summary(LocalDate.of(2044, 1, 1), LocalDate.of(2044, 12, 31)).target()).isEqualTo(300000);
        assertThat(jdbc.queryForObject("SELECT CANCELLED FROM PLEDGE_COMMITMENT WHERE ID=?", Boolean.class, id))
                .isTrue();
    }

    @Test
    void pledgeRatesAllowOverpaymentAndNoPromiseWithoutInventingPercent() {
        long gift = record(true, 150000);
        engine.classify(gift, account("1100"), account("4000"), giftType(), null);
        assertThat(pledges.summary(date, date).percent()).isNull();
        pledge(9003, 100000, "2044-01", "2044-01");
        assertThat(pledges.summary(date, date).percent()).isEqualTo(150);
        assertThat(pledges.summary(LocalDate.of(2044, 2, 1), LocalDate.of(2044, 2, 29)).percent()).isNull();
        var publicSummary = (PledgeService.Summary) engine.publicReport(date, date).get("pledges");
        assertThat(publicSummary.people()).isEmpty();
        assertThat(publicSummary.target()).isEqualTo(100000);
        var json = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build()
                .writeValueAsString(publicSummary);
        assertThat(json).contains("\"target\":\"100000\"", "\"percent\":150.0").doesNotContain("검증 개인 이름", "9003");
    }

    @Test
    void defaultChartGroupsPreserveMonthlyAmountsAndAccountingDetails(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        jdbc.update("UPDATE BUDGET SET NAME='헌금수입' WHERE ID=9001");
        var names = List.of("약정헌금", "주정헌금", "선교헌금", "감사헌금", "합성기타헌금1", "합성기타헌금2");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            jdbc.update(
                    "INSERT INTO OFFERING_TYPE(NAME) SELECT ? WHERE NOT EXISTS(SELECT 1 FROM OFFERING_TYPE WHERE NAME=?)",
                    name, name);
            jdbc.update(
                    "INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER,PARENT_ID) VALUES(?,9000,'INCOME',?,1000000,?,9001)",
                    9100 + i, name, i);
            jdbc.update(
                    "INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER,PARENT_ID) VALUES(?,9000,'INCOME','하위 항목',1000000,0,?)",
                    9200 + i, 9100 + i);
            commands.record(Map.of("kind", "INCOME", "year", 9000, "budget", 9200 + i, "date", date.toString(),
                    "amount", (i + 1) * 10000, "contact", 9003));
        }
        commands.record(Map.of("kind", "EXPENSE", "year", 9000, "budget", 9002, "date", date.toString(), "amount",
                30000, "contact", 9004));
        jdbc.update(
                "INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER) VALUES(9300,9000,'EXPENSE','물품/시설관리',1000000,1)");
        commands.record(Map.of("kind", "EXPENSE", "year", 9000, "budget", 9300, "date", date.toString(), "amount",
                40000, "contact", 9004));
        var web = engine.report(date, date);
        var categories = (List<Map<String, String>>) web.get("chartCategories");
        assertThat(categories).extracting(c -> c.get("label")).containsExactlyInAnyOrder("약정헌금", "주정헌금", "선교헌금", "감사헌금",
                "기타수입", "지출");
        assertThat(categories).extracting(c -> c.get("color")).doesNotHaveDuplicates();
        assertThat(categories).anySatisfy(c -> {
            assertThat(c.get("label")).isEqualTo("기타수입");
            assertThat(c.get("amount")).isEqualTo("110000");
        });
        assertThat(categories).anySatisfy(c -> {
            assertThat(c.get("label")).isEqualTo("지출");
            assertThat(c.get("amount")).isEqualTo("70000");
        });
        var others = ChartCategory.named("INCOME", "기타수입");
        for (var report : List.of(web, engine.publicReport(date, date))) {
            assertThat(report.get("income")).isEqualTo("210000");
            assertThat(report.get("expense")).isEqualTo("70000");
            var month = ((List<Map<String, String>>) report.get("monthly")).getFirst();
            assertThat(month.get(others.key())).isEqualTo("110000");
            assertThat(month.get("gift_" + others.key())).isEqualTo("110000");
        }
        var nativeMonth = dashboardService.getMonthlyTrend(9000L).getFirst();
        assertThat(nativeMonth.getBreakdown()).hasSize(6);
        assertThat(nativeMonth.getBreakdown().stream().mapToLong(ChartPart::amount).sum()).isEqualTo(280000);
        var report = reportService.getWeeklySettlementReport(9000L, date, date);
        assertThat(report.getPeriodTrend().getFirst().offeringBreakdown()).anySatisfy(p -> {
            assertThat(p.category()).isEqualTo(others);
            assertThat(p.amount()).isEqualTo(110000);
        });
        assertThat(report.getExpenseRecords()).extracting(x -> x.getBudgetName()).contains("물품/시설관리");
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        assertThat(engine.offeringForBudget(9205, "INCOME")).isEqualTo(giftType());
        assertThat(engine.offeringForBudget(9205, "EXPENSE")).isNull();
        var doc = org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(report,
                org.church.steward.ui.view.report.layout.ReportStyle.DEFAULT);
        var file = temp.resolve("chart-colors.pdf");
        org.church.steward.ui.view.report.output.ReportPdfExporter.export(doc, file);
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf))
                    .contains("약정헌금", "주정헌금", "선교헌금", "감사헌금", "기타수입", "지출", "물품/시설관리");
            String output = System.getenv("SAI_PLEDGE_SAMPLES");
            if (output != null) {
                var out = java.nio.file.Path.of(output);
                java.nio.file.Files.createDirectories(out);
                javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 110),
                        "png", out.resolve("chart-colors.png").toFile());
            }
        }
        balanced();
    }

    @Test
    void chartsGroupFirstLevelAndOfferingSecondLevelAcrossWebNativeAndPdf(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        jdbc.update("UPDATE BUDGET SET NAME='헌금수입' WHERE ID=9001");
        jdbc.update(
                "INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER,PARENT_ID) VALUES(9010,9000,'INCOME','약정헌금',1000000,1,9001),(9011,9000,'INCOME','감사헌금',1000000,2,9001),(9012,9000,'INCOME','세부 항목',1000000,1,9010),(9013,9000,'EXPENSE','관리비',1000000,2,NULL),(9014,9000,'EXPENSE','전기료',1000000,1,9013)");
        commands.record(Map.of("kind", "INCOME", "year", 9000, "budget", 9012, "date", date.toString(), "amount", 80000,
                "contact", 9003));
        commands.record(Map.of("kind", "INCOME", "year", 9000, "budget", 9011, "date", date.toString(), "amount", 20000,
                "contact", 9003));
        commands.record(Map.of("kind", "EXPENSE", "year", 9000, "budget", 9014, "date", date.toString(), "amount",
                30000, "contact", 9004));
        pledge(9003, 100000, "2044-01", "2044-12");
        var web = engine.report(date, date);
        var categories = (List<Map<String, String>>) web.get("chartCategories");
        assertThat(categories).extracting(c -> c.get("label")).containsExactlyInAnyOrder("약정헌금", "감사헌금",
                "지출");
        assertThat(categories).extracting(c -> c.get("color")).doesNotHaveDuplicates();
        var nativeMonth = dashboardService.getMonthlyTrend(9000L).getFirst();
        assertThat(nativeMonth.getBreakdown().stream().mapToLong(ChartPart::amount).sum()).isEqualTo(130000);
        for (var part : nativeMonth.getBreakdown())
            assertThat(categories).anySatisfy(c -> {
                assertThat(c.get("key")).isEqualTo(part.category().key());
                assertThat(c.get("color")).isEqualTo(part.category().color());
                assertThat(c.get("amount")).isEqualTo(String.valueOf(part.amount()));
            });
        var report = reportService.getWeeklySettlementReport(9000L, date, date);
        assertThat(report.getPeriodTrend().getFirst().breakdown()).hasSize(3);
        assertThat(report.getIncomeStatus()).extracting(x -> x.getName()).contains("약정헌금", "감사헌금")
                .doesNotContain("헌금수입");
        var doc = org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(report,
                org.church.steward.ui.view.report.layout.ReportStyle.DEFAULT);
        var file = temp.resolve("분류-약정.pdf");
        org.church.steward.ui.view.report.output.ReportPdfExporter.export(doc, file);
        try (var pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("약정 목표(100%)", "실제(%)",
                    "80.0%", "약정헌금", "감사헌금");
            String output = System.getenv("SAI_PLEDGE_SAMPLES");
            if (output != null) {
                var out = java.nio.file.Path.of(output);
                java.nio.file.Files.createDirectories(out);
                java.nio.file.Files.copy(file, out.resolve(file.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                for (int i = 0; i < pdf.getNumberOfPages(); i++)
                    javax.imageio.ImageIO.write(
                            new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(i, 110), "png",
                            out.resolve("report-" + i + ".png").toFile());
            }
        }
    }

    @Test
    @DisplayName("기초잔액만 있어도 메인 대시보드·연도 목록·보고서·공개 잔액이 같고 수입 그래프는 0이다")
    void openingOnlyAgreesAcrossBalanceScreens() {
        engine.openingBalance(9000, date, 5000000, account("1100"));
        var summary = dashboardService.getFiscalYearSummary(9000L);
        assertThat(summary.getOpeningBalance()).isEqualTo(5000000);
        assertThat(summary.getCarriedFunds()).isEqualTo(5000000);
        assertThat(summary.getCurrentBalance()).isEqualTo(5000000);
        assertThat(summary.getTotalIncome()).isZero();
        assertThat(summary.getTotalExpense()).isZero();
        var year = fiscalYears.listFiscalYears().stream().filter(y -> y.getId() == 9000L).findFirst().orElseThrow();
        assertThat(year.getCarriedFunds()).isEqualTo(5000000);
        assertThat(year.getCurrentBalance()).isEqualTo(5000000);
        assertThat(year.getBalance()).isNull();
        assertThat(dashboardService.getMonthlyTrend(9000L)).allSatisfy(m -> {
            assertThat(m.getIncome()).isZero();
            assertThat(m.getExpense()).isZero();
        });
        assertThat(dashboardService.getBudgetProgress(9000L)).allSatisfy(b -> assertThat(b.getActualAmount()).isZero());
        assertThat(dashboardService.getIncomeBreakdown(9000L)).isEmpty();
        assertThat(dashboardService.getRecentTransactions(9000L)).isEmpty();
        assertThat(reportService.getAnnualSettlementReport(9000L).getCurrentBalance()).isEqualTo(5000000);
        for (var report : List.of(engine.report(date, date), engine.publicReport(date, date))) {
            assertThat(report).containsEntry("assets", "5000000").containsEntry("netAssets", "5000000")
                    .containsEntry("cashBalance", "5000000").containsEntry("income", "0")
                    .containsEntry("offeringsTotal", "0").containsEntry("difference", "0");
        }
    }

    @Test
    @DisplayName("대시보드와 마감 연도 목록에서 기초잔액은 잔액에만 반영하고 마감 차액은 유지한다")
    void dashboardAndClosedYearKeepIncomeSeparateFromOpening() {
        long opening = engine.openingBalance(9000, date, 5000000, account("1100"));
        long gift = record(true, 100000);
        engine.classify(gift, account("1100"), account("4000"), giftType(), null);
        record(false, 30000);
        var summary = dashboardService.getFiscalYearSummary(9000L);
        assertThat(summary.getCurrentBalance()).isEqualTo(5070000);
        assertThat(summary.getTotalIncome()).isEqualTo(100000);
        assertThat(summary.getTotalExpense()).isEqualTo(30000);
        fiscalYears.closeFiscalYear(9000L);
        var year = fiscalYears.listFiscalYears().getFirst();
        assertThat(year.getBalance()).isEqualTo(70000);
        assertThat(year.getCurrentBalance()).isEqualTo(5070000);
        assertThat(year.getCarriedFunds()).isEqualTo(5000000);
        fiscalYears.reopenFiscalYear(9000L);
        engine.reverseManual(opening);
        assertThat(dashboardService.getFiscalYearSummary(9000L).getCurrentBalance()).isEqualTo(70000);
        assertThat(fiscalYears.listFiscalYears().getFirst().getCurrentBalance()).isEqualTo(70000);
        assertThat(engine.report(date, date).get("cashBalance")).isEqualTo("70000");
    }

    @Test
    @DisplayName("다음 연도 대시보드와 연도 목록은 잔액을 이월하며 이후 연도 기초잔액은 이전 연도에 반영하지 않는다")
    void dashboardCarryAndFutureOpeningRespectFiscalYearBoundary() {
        engine.openingBalance(9000, date, 5000000, account("1100"));
        record(true, 100000);
        record(false, 30000);
        fiscalYears.createFiscalYear("대시보드 이월", LocalDate.of(2045, 1, 1), LocalDate.of(2045, 12, 31));
        long nextYear = jdbc.queryForObject("SELECT ID FROM FISCAL_YEAR WHERE NAME='대시보드 이월'", Long.class);
        engine.openingBalance(nextYear, LocalDate.of(2045, 1, 1), 200000, account("1100"));
        assertThat(dashboardService.getFiscalYearSummary(9000L).getCurrentBalance()).isEqualTo(5070000);
        var summary = dashboardService.getFiscalYearSummary(nextYear);
        assertThat(summary.getOpeningBalance()).isEqualTo(5200000);
        assertThat(summary.getCarriedFunds()).isEqualTo(5270000);
        assertThat(summary.getCurrentBalance()).isEqualTo(5270000);
        assertThat(summary.getTotalIncome()).isZero();
        assertThat(summary.getTotalExpense()).isZero();
        var year = fiscalYears.listFiscalYears().stream().filter(y -> y.getId() == nextYear).findFirst().orElseThrow();
        assertThat(year.getCurrentBalance()).isEqualTo(5270000);
        assertThat(reportService.getAnnualSettlementReport(nextYear).getCurrentBalance()).isEqualTo(5270000);
        assertThat(engine.report(LocalDate.of(2045, 1, 1), LocalDate.of(2045, 12, 31)).get("cashBalance"))
                .isEqualTo("5270000");
    }

    @Test
    @DisplayName("기초잔액은 입력 기간의 잔액에 더하고 수입·헌금·그래프에는 더하지 않는다")
    void openingBalanceAppearsInPeriodReports(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
            throws Exception {
        engine.openingBalance(9000, date, 5000000, account("1100"));
        long gift = record(true, 100000);
        engine.classify(gift, account("1100"), account("4000"), giftType(), null);
        record(false, 30000);
        var sameWeek = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 1, 1),
                LocalDate.of(2044, 1, 7));
        assertThat(sameWeek.getOpeningBalance()).isEqualTo(5000000);
        assertThat(sameWeek.getPeriodOpeningBalance()).isEqualTo(5000000);
        assertThat(sameWeek.getPreviousBalance()).isZero();
        assertThat(sameWeek.getCurrentBalance()).isEqualTo(5070000);
        assertThat(sameWeek.getWeekly().getIncome()).isEqualTo(100000);
        assertThat(sameWeek.getPeriodTrend().stream().mapToLong(x -> x.income()).sum()).isEqualTo(100000);
        assertThat(sameWeek.getPeriodTrend().stream().mapToLong(x -> x.offerings()).sum()).isEqualTo(100000);
        var nextWeek = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 1, 8),
                LocalDate.of(2044, 1, 14));
        assertThat(nextWeek.getPreviousBalance()).isEqualTo(5070000);
        assertThat(nextWeek.getPeriodOpeningBalance()).isZero();
        assertThat(nextWeek.getCurrentBalance()).isEqualTo(5070000);
        var year = reportService.getAnnualSettlementReport(9000L);
        assertThat(year.getOpeningBalance()).isEqualTo(5000000);
        assertThat(year.getPeriodOpeningBalance()).isEqualTo(5000000);
        assertThat(year.getCurrentBalance()).isEqualTo(5070000);
        assertThat(engine.report(date, date).get("assets")).isEqualTo("5070000");
        var month = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 1, 1), LocalDate.of(2044, 1, 31));
        assertThat(month.getCurrentBalance()).isEqualTo(5070000);
        var style = org.church.steward.ui.view.report.layout.ReportStyle.DEFAULT;
        var reports = List.of(
                org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(sameWeek, style),
                org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(month, style, true),
                org.church.steward.ui.view.report.composer.AnnualSettlementReportComposer.compose(year, style));
        for (int i = 0; i < reports.size(); i++) {
            var file = temp.resolve("opening-report-" + i + ".pdf");
            org.church.steward.ui.view.report.output.ReportPdfExporter.export(reports.get(i), file);
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
                assertThat(text).contains("기초잔액", "5,000,000", "5,070,000");
                String output = System.getenv("SAI_OPENING_REPORT_SAMPLES");
                if (output != null) {
                    var dir = java.nio.file.Path.of(output);
                    java.nio.file.Files.createDirectories(dir);
                    java.nio.file.Files.copy(file, dir.resolve(file.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    javax.imageio.ImageIO.write(
                            new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(0, 110), "png",
                            dir.resolve("opening-report-" + i + ".png").toFile());
                }
            }
        }

    }

    @Test
    @DisplayName("미래 기초잔액은 미리 반영하지 않고 취소한 기초잔액은 상쇄한다")
    void openingBalanceRespectsDateAndCancellation() {
        long entry = engine.openingBalance(9000, LocalDate.of(2044, 2, 1), 5000000, account("1100"));
        var january = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 1, 1),
                LocalDate.of(2044, 1, 31));
        assertThat(january.getOpeningBalance()).isZero();
        assertThat(january.getCurrentBalance()).isZero();
        var february = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 2, 1),
                LocalDate.of(2044, 2, 29));
        assertThat(february.getCurrentBalance()).isEqualTo(5000000);
        engine.reverseManual(entry);
        var cancelled = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 2, 1),
                LocalDate.of(2044, 2, 29));
        assertThat(cancelled.getOpeningBalance()).isZero();
        assertThat(cancelled.getCurrentBalance()).isZero();
    }

    @Test
    @DisplayName("전년도 기초잔액과 수입·지출은 다음 회계 연도로 한 번만 이월된다")
    void openingBalanceCarriesIntoNextYear() {
        engine.openingBalance(9000, date, 5000000, account("1100"));
        record(true, 100000);
        record(false, 30000);
        fiscalYears.createFiscalYear("기초잔액 이월 검증", LocalDate.of(2045, 1, 1), LocalDate.of(2045, 12, 31));
        long nextYear = jdbc.queryForObject("SELECT ID FROM FISCAL_YEAR WHERE NAME='기초잔액 이월 검증'", Long.class);
        var report = reportService.getAnnualSettlementReport(nextYear);
        assertThat(report.getPreviousBalance()).isEqualTo(5070000);
        assertThat(report.getPeriodOpeningBalance()).isZero();
        assertThat(report.getCurrentBalance()).isEqualTo(5070000);
        assertThat(report.getCurrentYear().getIncome()).isZero();
        var month = reportService.getWeeklySettlementReport(nextYear, LocalDate.of(2045, 1, 1),
                LocalDate.of(2045, 1, 31));
        assertThat(month.getPreviousBalance()).isEqualTo(5070000);
        assertThat(month.getCurrentBalance()).isEqualTo(5070000);
    }

    private long datedRecord(boolean income, long amount, LocalDate day) {
        long id = record(income, amount);
        records.updateFinancialRecord(id,
                FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(income ? 9001L : 9002L).date(day)
                        .amount(amount).contactIds(List.of(income ? 9003L : 9004L)).build());
        return id;
    }

    @Test
    @DisplayName("주간 그래프는 이전 이월·이후 거래를 제외하고 해당 주 발생액만 표시한다")
    void weeklyChartsExcludePreviousAndFutureTransactions() {
        record(true, 100000);
        long gift = datedRecord(true, 50000, LocalDate.of(2044, 1, 13));
        engine.classify(gift, account("1100"), account("4000"), giftType(), null);
        datedRecord(false, 10000, LocalDate.of(2044, 1, 14));
        datedRecord(true, 70000, LocalDate.of(2044, 1, 19));
        var report = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 1, 12),
                LocalDate.of(2044, 1, 18));
        assertThat(report.getPreviousBalance()).isEqualTo(100000);
        assertThat(report.getIncomeStatus().stream().mapToLong(x -> x.getActualAmount()).sum()).isEqualTo(150000);
        assertThat(report.getPeriodTrend()).hasSize(7);
        assertThat(report.getPeriodTrend().stream().mapToLong(x -> x.income()).sum()).isEqualTo(50000);
        assertThat(report.getPeriodTrend().stream().mapToLong(x -> x.expense()).sum()).isEqualTo(10000);
        assertThat(report.getPeriodTrend().stream().mapToLong(x -> x.offerings()).sum()).isEqualTo(50000);
        assertThat(report.getPeriodTrend().getLast().income()).isZero();
    }

    @Test
    @DisplayName("월간·연간 그래프와 컬러 PDF는 선택 기간의 원본 합계와 일치한다")
    void monthlyAnnualGraphsAndColorPdf(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        record(true, 100000);
        long gift = datedRecord(true, 50000, LocalDate.of(2044, 2, 29));
        engine.classify(gift, account("1100"), account("4000"), giftType(), null);
        datedRecord(false, 10000, LocalDate.of(2044, 2, 10));
        datedRecord(true, 70000, LocalDate.of(2044, 3, 1));
        var month = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 2, 1), LocalDate.of(2044, 2, 29));
        assertThat(month.getPeriodTrend()).hasSize(29);
        assertThat(month.getPeriodTrend().stream().mapToLong(x -> x.income()).sum()).isEqualTo(50000);
        assertThat(month.getPeriodTrend().stream().mapToLong(x -> x.expense()).sum()).isEqualTo(10000);
        assertThat(month.getPeriodTrend().stream().mapToLong(x -> x.offerings()).sum()).isEqualTo(50000);
        var annual = reportService.getAnnualSettlementReport(9000L);
        assertThat(annual.getPeriodTrend()).hasSize(12);
        assertThat(annual.getPeriodTrend().stream().mapToLong(x -> x.income()).sum()).isEqualTo(220000);
        assertThat(annual.getPeriodTrend().stream().mapToLong(x -> x.expense()).sum()).isEqualTo(10000);
        assertThat(annual.getPeriodTrend().stream().mapToLong(x -> x.offerings()).sum()).isEqualTo(50000);
        var week = reportService.getWeeklySettlementReport(9000L, LocalDate.of(2044, 2, 5), LocalDate.of(2044, 2, 11));
        var style = org.church.steward.ui.view.report.layout.ReportStyle.DEFAULT;
        var documents = List.of(
                org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(week, style),
                org.church.steward.ui.view.report.composer.WeeklySettlementReportComposer.compose(month, style, true),
                org.church.steward.ui.view.report.composer.AnnualSettlementReportComposer.compose(annual, style));
        for (int i = 0; i < documents.size(); i++) {
            var path = temp.resolve("report-" + i + ".pdf");
            org.church.steward.ui.view.report.output.ReportPdfExporter.export(documents.get(i), path);
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
                assertThat(text).contains("해당 기간 발생액", "누계 제외", "기간 합계");
                assertThat(text).contains(i == 0 ? "주간 합계" : i == 1 ? "월간 합계" : "연간 합계");
                if (i == 1)
                    assertThat(text).contains("2044-02-29", "50,000원", "10,000원").doesNotContain("2044-03-01",
                            "220,000원");
                if (i == 2)
                    assertThat(text).contains("220,000원", "10,000원");
                boolean colored = false;
                var renderer = new org.apache.pdfbox.rendering.PDFRenderer(pdf);
                for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                    var image = renderer.renderImageWithDPI(page, 72);
                    for (int y = 0; y < image.getHeight() && !colored; y += 3)
                        for (int x = 0; x < image.getWidth(); x += 3) {
                            var color = new java.awt.Color(image.getRGB(x, y));
                            if (Math.abs(color.getRed() - color.getBlue()) > 35 && color.getGreen() < 230) {
                                colored = true;
                                break;
                            }
                        }
                }
                assertThat(colored).isTrue();
                String samples = System.getenv("SAI_REPORT_SAMPLE_DIR");
                if (samples != null) {
                    var dir = java.nio.file.Path.of(samples);
                    java.nio.file.Files.createDirectories(dir);
                    java.nio.file.Files.copy(path, dir.resolve("report-" + i + ".pdf"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    for (int page = 0; page < pdf.getNumberOfPages(); page++)
                        javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, 110), "png",
                                dir.resolve("report-" + i + "-page-" + page + ".png").toFile());
                }
            }
        }
    }

    @Test
    @DisplayName("기초잔액은 금액과 통장만으로 자동 분개하며 수입·헌금에 더하지 않는다")
    void openingBalanceIsAutomaticAndNotIncome() {
        engine.openingBalance(9000, date, 5000000, account("1100"));
        var report = engine.report(date, date);
        assertThat(report.get("assets")).isEqualTo("5000000");
        assertThat(report.get("netAssets")).isEqualTo("5000000");
        assertThat(report.get("income")).isEqualTo("0");
        assertThat(report.get("offeringsTotal")).isEqualTo("0");
        balanced();
    }

    @Test
    @DisplayName("이름·예산·금액만 입력하면 헌금자와 차변·대변을 자동으로 연결한다")
    void simpleEntryAutomaticallyLinksPersonAndJournal() {
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        long id = commands.record(new HashMap<>(Map.of("kind", "INCOME", "year", 9000, "date", date.toString(),
                "budget", 9001, "amount", 100000, "donorName", "검증 개인 이름", "memo", "자동 입력 검증")));
        var gift = engine.donations(date, date).getFirst();
        assertThat(gift.get("CONTACT_ID")).isEqualTo(9003L);
        assertThat(gift.get("TYPE_NAME")).isEqualTo("약정헌금");
        assertThat(jdbc.queryForObject("SELECT COUNTERPART_ID FROM ACCT_LINK WHERE RECORD_ID=?", Long.class, id))
                .isEqualTo(account("4000"));
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("100000");
        balanced();
    }

    @Test
    @DisplayName("선교비 지출은 차변 선교비·대변 보통예금을 자동 선택한다")
    void expenseAutomaticallyUsesBudgetAccount() {
        jdbc.update("UPDATE BUDGET SET NAME='선교비' WHERE ID=9002");
        long id = commands.record(new HashMap<>(Map.of("kind", "EXPENSE", "year", 9000, "date", date.toString(),
                "budget", 9002, "amount", 10000, "donorName", "검증 수취인", "memo", "자동 지출 검증")));
        assertThat(jdbc.queryForObject("SELECT COUNTERPART_ID FROM ACCT_LINK WHERE RECORD_ID=?", Long.class, id))
                .isEqualTo(account("5200"));
        assertThat(engine.report(date, date).get("expense")).isEqualTo("10000");
        balanced();
    }

    @Test
    @DisplayName("기존 미분류 헌금자는 한 번에 자동 연결하고 이미 지정한 사람은 보존한다")
    void existingUnclassifiedDonorsCanBeConnected() {
        jdbc.update("INSERT INTO CONTACT(ID,TYPE,NAME,IS_HIDDEN) VALUES(9006,'OTHER','통장거래(미분류)',FALSE)");
        long id = records.createFinancialRecord(FinancialRecordRequestDto.builder().type(FinancialRecordType.INCOME)
                .fiscalYearId(9000L).budgetId(9001L).date(date).amount(100000).description("09:00:00 · 검증 개인 이름 약정헌금")
                .contactIds(List.of(9006L)).build()).getId();
        long manual = record(true, 20000);
        assertThat(commands.autoConnectDonors(List.of(id, manual))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CONTACT_ID FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?",
                Long.class, id)).isEqualTo(9003L);
        assertThat(commands.autoConnectDonors(List.of(id, manual))).isZero();
        assertThat(engine.report(date, date).get("income")).isEqualTo("120000");
        balanced();
    }

    @Autowired
    org.church.steward.domain.datatransfer.service.RecordImportService recordImporter;
    @Autowired
    org.church.steward.domain.activitylog.service.ActivityLogService activity;

    @Test
    @DisplayName("실제 XLSX·CSV 가져오기로 등록한 약정헌금이 별도 입력 없이 통계에 반영된다")
    void spreadsheetImportsAppearInOfferingStatistics(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        for (var format : org.church.steward.infrastructure.spreadsheet.SpreadsheetFormat.values()) {
            var file = temp.resolve("헌금." + format.getExtension());
            new org.church.steward.infrastructure.spreadsheet.SpreadsheetWriter().write(file, format,
                    new org.church.steward.infrastructure.spreadsheet.SpreadsheetTable("내역",
                            List.of("일자", "예산", "금액", "헌금자"),
                            List.of(List.of(date.plusDays(format.ordinal()), "약정헌금", 100000L, "검증 개인 이름"))),
                    null);
            var sheet = new org.church.steward.infrastructure.spreadsheet.SpreadsheetReader().read(file, null, null,
                    java.nio.charset.StandardCharsets.UTF_8);
            var request = org.church.steward.domain.datatransfer.dto.RecordImportRequestDto.builder()
                    .rows(sheet.rows().subList(1, sheet.rows().size())).firstRowNumber(2)
                    .fixedType(FinancialRecordType.INCOME).dateColumn(0).budgetPathColumn(1).amountColumn(2)
                    .contactColumn(3).build();
            var analysis = recordImporter.analyze(9000L, request);
            var keys = analysis.getItems().stream()
                    .filter(org.church.steward.domain.datatransfer.dto.RecordImportItemDto::isSelectable)
                    .map(org.church.steward.domain.datatransfer.dto.RecordImportItemDto::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(keys).hasSize(1);
            recordImporter.importRecords(9000L, request, keys, analysis.getSignature(), activity.getLatestLogId(),
                    file.getFileName().toString());
        }
        assertThat(engine.donations(date, date.plusDays(1))).hasSize(2);
        assertThat(engine.report(date, date.plusDays(1)).get("offeringsTotal")).isEqualTo("200000");
        assertThat(engine.report(date, date.plusDays(1)).get("income")).isEqualTo("200000");
        balanced();
    }

    @Test
    @DisplayName("기존 데스크톱 입력도 정확히 일치하는 헌금 예산이면 통계에 자동 연결된다")
    void nativeOfferingBudgetAppearsInStatistics() {
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        record(true, 100000);
        assertThat(engine.donations(date, date)).hasSize(1);
        assertThat(engine.donations(date, date).getFirst().get("TYPE_NAME")).isEqualTo("약정헌금");
        assertThat(engine.report(date, date).get("income")).isEqualTo("100000");
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("100000");
        balanced();
    }

    @Test
    @DisplayName("기존 미연결 수입은 확인 연결 시 중복 없이 헌금 통계에 반영된다")
    void confirmedExistingOfferingsAreLinkedOnce() {
        long id = record(true, 100000);
        assertThat(engine.donations(date, date)).isEmpty();
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        engine.linkBudgetOfferings(List.of(id));
        assertThat(engine.donations(date, date)).hasSize(1);
        assertThat(engine.report(date, date).get("income")).isEqualTo("100000");
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("100000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> engine.linkBudgetOfferings(List.of(id))).isInstanceOf(IllegalArgumentException.class);
        balanced();
    }

    @Test
    @DisplayName("미연결 수입 일괄 확인은 잘못된 항목이 하나라도 있으면 전부 롤백한다")
    void batchOfferingLinkIsAtomic() {
        long income = record(true, 100000), expense = record(false, 50000);
        jdbc.update("UPDATE BUDGET SET NAME='약정헌금' WHERE ID=9001");
        assertThatThrownBy(() -> engine.linkBudgetOfferings(List.of(income, expense)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.donations(date, date)).isEmpty();
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("0");
        balanced();
    }

    @Test
    @DisplayName("4자 로그인·권한·외부 집계·공개 OFF·로그아웃을 실제 HTTP로 검증한다")
    void httpBoundary() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        users.save(org.church.steward.domain.user.entity.User.builder().id("web-admin-test")
                .password(encoder.encode("1234")).role(UserRole.ADMIN).build());
        users.save(org.church.steward.domain.user.entity.User.builder().id("web-reader-test")
                .password(encoder.encode("aaaa")).role(UserRole.USER).build());
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        try {
            web.open("대시보드");
            assertThat(http(client, "GET", "/api/data", null, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "POST", "/api/login", null,
                    "{\"id\":\"web-admin-test\",\"password\":\"bad\"}").statusCode()).isEqualTo(403);
            var login = http(client, "POST", "/api/login", null,
                    "{\"id\":\"web-admin-test\",\"password\":\"1234\"}");
            assertThat(login.statusCode()).isEqualTo(200);
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertThat(login.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Strict");
            assertThat(http(client, "GET", "/api/data", cookie, null).body()).contains("검증 개인 이름");
            var comparison = http(client, "GET", "/api/comparison?from=2044-03-01&to=2044-03-31", cookie, null);
            assertThat(comparison.statusCode()).isEqualTo(200);
            assertThat(comparison.body()).contains("2044-02-01", "2044-02-29", "2043-03-01");
            assertThat(http(client, "GET", "/api/public-preview?from=2044-01-01&to=2044-12-31", cookie, null).body())
                    .doesNotContain("검증 개인 이름", "CONTACT_ID", "CONTACT_NAME", "SHARE", "DESCRIPTION");
            var evil = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8765/api/write"))
                    .header("Origin", "https://invalid.example").header("Cookie", cookie)
                    .POST(java.net.http.HttpRequest.BodyPublishers
                            .ofString("{\"operation\":\"share\",\"enabled\":true}"))
                    .build();
            assertThat(client.send(evil, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(403);
            var reader = http(client, "POST", "/api/login", null,
                    "{\"id\":\"web-reader-test\",\"password\":\"aaaa\"}");
            String rc = reader.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertThat(http(client, "GET", "/api/report", rc, null).statusCode()).isEqualTo(200);
            assertThat(
                    http(client, "POST", "/api/write", rc, "{\"operation\":\"share\",\"enabled\":true}").statusCode())
                    .isEqualTo(403);
            assertThat(http(client, "GET", "/api/data", rc, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "GET", "/api/pledges", rc, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "GET", "/api/external-churches", rc, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "GET", "/api/pledges?from=2044-01-01&to=2044-12-31", cookie, null).body())
                    .contains("검증 개인 이름");
            String runner = System.getenv("SAI_BROWSER_SMOKE");
            if (runner != null) {
                Process browser = new ProcessBuilder("node", runner).inheritIO().start();
                assertThat(browser.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(browser.exitValue()).isZero();
            }
            assertThat(http(client, "POST", "/api/write", cookie,
                    "{\"operation\":\"share\",\"enabled\":true}").statusCode()).isEqualTo(200);
            String token = jdbc.queryForObject(
                    "SELECT VALUE_TEXT FROM FINANCE_PREFERENCE WHERE KEY_NAME='PUBLIC_TOKEN'", String.class);
            var publicUri = java.net.URI
                    .create("http://127.0.0.1:8766/" + token + "/data?from=2044-01-01&to=2044-12-31");
            var published = client.send(java.net.http.HttpRequest.newBuilder(publicUri).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(published.statusCode()).isEqualTo(200);
            assertThat(published.body()).contains("100000").doesNotContain("검증 개인 이름", "CONTACT_ID", "CONTACT_NAME",
                    "SHARE", "DESCRIPTION");
            assertThat(client.send(
                    java.net.http.HttpRequest.newBuilder(publicUri)
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
            assertThat(http(client, "POST", "/api/write", cookie, "{\"operation\":\"share\",\"enabled\":false}")
                    .statusCode()).isEqualTo(200);
            assertThat(client.send(java.net.http.HttpRequest.newBuilder(publicUri).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(http(client, "POST", "/api/logout", cookie, "{}").statusCode()).isEqualTo(200);
            assertThat(http(client, "GET", "/api/data", cookie, null).statusCode()).isEqualTo(403);
        } finally {
            jdbc.update("UPDATE FINANCE_PREFERENCE SET VALUE_TEXT='false' WHERE KEY_NAME='PUBLIC_ENABLED'");
            web.close();
            users.deleteById("web-admin-test");
            users.deleteById("web-reader-test");
        }
    }

    private java.net.http.HttpResponse<String> http(java.net.http.HttpClient client, String method, String path,
            String cookie, String body) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8765" + path));
        if (cookie != null)
            request.header("Cookie", cookie);
        if (body != null)
            request.header("Origin", "http://127.0.0.1:8765").header("Content-Type", "application/json")
                    .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body));
        else
            request.GET();
        return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM PLEDGE_COMMITMENT");
        for (String table : List.of("OFFERING_ALLOCATION", "ACCT_LINK", "ACCT_LINE"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM ACCT_ENTRY WHERE REVERSES_ID IS NOT NULL");
        jdbc.update("DELETE FROM ACCT_ENTRY");
        for (String table : List.of("FINANCIAL_RECORD_CONTACT", "FINANCIAL_RECORD", "EXTERNAL_CHURCH",
                "CONTACT_GROUP_MEMBER",
                "CONTACT_GROUP", "BUDGET", "FISCAL_YEAR", "CONTACT"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("UPDATE ACCT_ACCOUNT SET ACTIVE=TRUE");
        jdbc.update("UPDATE ACCT_ACCOUNT SET NAME='보통예금' WHERE CODE='1100'");
        jdbc.update("UPDATE OFFERING_TYPE SET ACTIVE=TRUE");
        jdbc.update(
                "INSERT INTO FISCAL_YEAR(ID,NAME,START_DATE,END_DATE,CLOSED) VALUES(9000,'검증 연도','2044-01-01','2044-12-31',FALSE)");
        jdbc.update(
                "INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,AMOUNT,SORT_ORDER) VALUES(9001,9000,'INCOME','검증수입',1000000,0),(9002,9000,'EXPENSE','검증비용',1000000,1)");
        jdbc.update(
                "INSERT INTO CONTACT(ID,TYPE,NAME,IS_HIDDEN) VALUES(9003,'INDIVIDUAL','검증 개인 이름',FALSE),(9004,'VENDOR','검증 수취인',FALSE),(9005,'INDIVIDUAL','검증 공동 헌금자',FALSE)");
        sessions.setLoggedInUser(new LoggedInUser("검증 관리자", UserRole.ADMIN));
    }

    private long account(String code) {
        return jdbc.queryForObject("SELECT ID FROM ACCT_ACCOUNT WHERE CODE=?", Long.class, code);
    }

    private long giftType() {
        return jdbc.queryForObject("SELECT ID FROM OFFERING_TYPE WHERE NAME='약정헌금'", Long.class);
    }

    private long record(boolean income, long amount) {
        return records.createFinancialRecord(FinancialRecordRequestDto.builder()
                .type(income ? FinancialRecordType.INCOME : FinancialRecordType.EXPENSE).fiscalYearId(9000L)
                .budgetId(income ? 9001L : 9002L).date(date).amount(amount).description("검증 거래")
                .contactIds(List.of(income ? 9003L : 9004L)).build()).getId();
    }

    private void balanced() {
        assertThat(jdbc.queryForList(
                "SELECT E.ID FROM ACCT_ENTRY E JOIN ACCT_LINE L ON L.ENTRY_ID=E.ID GROUP BY E.ID HAVING SUM(L.DEBIT)<>SUM(L.CREDIT)"))
                .isEmpty();
        assertThat(engine.report(date.withDayOfYear(1), date).get("difference")).isEqualTo("0");
    }

    @Test
    @DisplayName("일반 수입·지출 자동 분개와 기존 합계가 일치한다")
    void automaticPosting_matchesOriginal() {
        record(true, 100000);
        record(false, 50000);
        Map<String, Object> r = engine.report(date.withDayOfYear(1), date);
        assertThat(r.get("income")).isEqualTo("100000");
        assertThat(r.get("expense")).isEqualTo("50000");
        assertThat(r.get("assets")).isEqualTo("50000");
        balanced();
    }

    @Test
    @DisplayName("헌금은 원본 수입 한 건에 연결되고 중복 집계되지 않는다")
    void donation_linksOnce() {
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        assertThat(engine.donations(date, date)).hasSize(1);
        assertThat(engine.report(date, date).get("income")).isEqualTo("100000");
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("100000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD", Integer.class)).isEqualTo(1);
        balanced();
    }

    @Test
    @DisplayName("헌금 수정은 역분개·정정과 개인 배분에 함께 반영된다")
    void donationUpdate_synchronized() {
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        records.updateFinancialRecord(id, FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(9001L)
                .date(date).amount(150000).contactIds(List.of(9003L)).build());
        assertThat(engine.report(date, date).get("income")).isEqualTo("150000");
        assertThat(engine.donations(date, date).getFirst().get("SHARE")).isEqualTo("150000");
        balanced();
    }

    @Test
    @DisplayName("취소는 원본과 헌금자 연결을 보존하고 보고서에서 상쇄된다")
    void cancellation_preservesOriginal() {
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        records.deleteFinancialRecords(List.of(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD WHERE ID=? AND CANCELLED=TRUE",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD_CONTACT WHERE FINANCIAL_RECORD_ID=?",
                Integer.class, id)).isEqualTo(1);
        assertThat(engine.donations(date, date)).isEmpty();
        assertThat(engine.report(date, date).get("income")).isEqualTo("0");
        balanced();
    }

    @Test
    @DisplayName("미지급금 발생·지급은 비용을 한 번만 반영한다")
    void liability_expenseOnce() {
        record(true, 200000);
        engine.journal(9000, date, "미지급", List.of(new FinanceEngine.Line(account("5400"), 100000, 0),
                new FinanceEngine.Line(account("2000"), 0, 100000)), null);
        assertThat(engine.report(date, date).get("liabilities")).isEqualTo("100000");
        engine.journal(9000, date, "지급", List.of(new FinanceEngine.Line(account("2000"), 100000, 0),
                new FinanceEngine.Line(account("1100"), 0, 100000)), null);
        assertThat(engine.report(date, date).get("expense")).isEqualTo("100000");
        assertThat(engine.report(date, date).get("liabilities")).isEqualTo("0");
        balanced();
    }

    @Test
    @DisplayName("불균형 분개와 마감 연도는 저장 전에 차단한다")
    void invalidJournal_rejected() {
        assertThatThrownBy(
                () -> engine.journal(9000, date, "오류",
                        List.of(new FinanceEngine.Line(account("1100"), 10, 0),
                                new FinanceEngine.Line(account("4900"), 0, 9)),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCT_ENTRY", Integer.class)).isZero();
        jdbc.update("UPDATE FISCAL_YEAR SET CLOSED=TRUE WHERE ID=9000");
        assertThatThrownBy(() -> record(true, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("수동 분개 취소는 원본을 보존하며 자동 분개를 직접 취소할 수 없다")
    void manualReversal_preservesJournal() {
        long entry = engine.journal(9000, date, "기초잔액", List.of(new FinanceEngine.Line(account("1100"), 100000, 0),
                new FinanceEngine.Line(account("3000"), 0, 100000)), null);
        engine.reverseManual(entry);
        assertThat(engine.report(date, date).get("assets")).isEqualTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCT_ENTRY", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> engine.reverseManual(entry)).isInstanceOf(IllegalArgumentException.class);
        long source = record(true, 50000);
        long automatic = jdbc.queryForObject("SELECT ENTRY_ID FROM ACCT_LINK WHERE RECORD_ID=?", Long.class, source);
        assertThatThrownBy(() -> engine.reverseManual(automatic)).isInstanceOf(IllegalArgumentException.class);
        balanced();
    }

    @Test
    @DisplayName("비활성 헌금 종류의 과거 기록은 정정 가능하고 신규 연결만 차단한다")
    void inactiveOffering_preservesExisting() {
        long source = record(true, 50000);
        engine.classify(source, account("1100"), account("4000"), giftType(), null);
        jdbc.update("UPDATE OFFERING_TYPE SET ACTIVE=FALSE WHERE ID=?", giftType());
        engine.classify(source, account("1100"), account("4000"), giftType(), null);
        assertThatThrownBy(
                () -> engine.classify(record(true, 10000), account("1100"), account("4000"), giftType(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.donations(date, date)).hasSize(1);
        balanced();
    }

    @Test
    @DisplayName("월·연간·헌금 종류별 개인 합계와 기준일별 재무상태가 원본과 일치한다")
    void donorPeriodTotals_matchSource() {
        long first = record(true, 100000), second = record(true, 50000);
        engine.classify(first, account("1100"), account("4000"), giftType(), null);
        records.updateFinancialRecord(second, FinancialRecordRequestDto.builder().fiscalYearId(9000L).budgetId(9001L)
                .date(LocalDate.of(2044, 2, 2)).amount(50000).contactIds(List.of(9003L)).build());
        long thanks = jdbc.queryForObject("SELECT ID FROM OFFERING_TYPE WHERE NAME='감사헌금'", Long.class);
        engine.classify(second, account("1100"), account("4000"), thanks, null);
        LocalDate end = LocalDate.of(2044, 12, 31);
        var gifts = engine.donations(date.withDayOfYear(1), end);
        assertThat(gifts).hasSize(2);
        assertThat(gifts.stream().mapToLong(g -> Long.parseLong(g.get("SHARE").toString())).sum()).isEqualTo(150000);
        assertThat(engine.donations(date.withDayOfYear(1), LocalDate.of(2044, 1, 31))).hasSize(1);
        assertThat(gifts.stream().filter(g -> g.get("TYPE_ID").equals(thanks))
                .mapToLong(g -> Long.parseLong(g.get("SHARE").toString())).sum()).isEqualTo(50000);
        assertThat(engine.report(date, LocalDate.of(2044, 1, 31)).get("assets")).isEqualTo("100000");
        assertThat(engine.report(date, end).get("assets")).isEqualTo("150000");
        assertThat(engine.report(date, end).get("offeringsTotal")).isEqualTo("150000");
    }

    @Test
    @DisplayName("실패한 헌금·회계 연결은 원본 거래도 남기지 않고 전부 롤백한다")
    void commandFailure_rollsBack() {
        Map<String, Object> b = new HashMap<>(Map.of("kind", "INCOME", "year", 9000, "date", date.toString(), "budget",
                9001, "amount", 100000, "contact", 9003, "asset", account("1100"), "counterpart", account("2000")));
        assertThatThrownBy(() -> commands.record(b)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FINANCIAL_RECORD", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCT_ENTRY", Integer.class)).isZero();
        long id = record(true, 50000);
        jdbc.update("UPDATE FINANCIAL_RECORD SET REMARKS='기존 비고 보존' WHERE ID=?", id);
        b.put("id", id);
        b.put("counterpart", account("4900"));
        commands.record(b);
        assertThat(jdbc.queryForObject("SELECT REMARKS FROM FINANCIAL_RECORD WHERE ID=?", String.class, id))
                .isEqualTo("기존 비고 보존");
    }

    @Test
    @DisplayName("공동 헌금은 임의 균등 배분하지 않고 합계 일치 배분만 허용한다")
    void allocation_validated() {
        long id = records
                .createFinancialRecord(
                        FinancialRecordRequestDto.builder().type(FinancialRecordType.INCOME).fiscalYearId(9000L)
                                .budgetId(9001L).date(date).amount(100000).contactIds(List.of(9003L, 9005L)).build())
                .getId();
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        assertThat(engine.donations(date, date)).hasSize(1);
        assertThat(engine.donations(date, date).getFirst().get("CONTACT_ID")).isNull();
        assertThatThrownBy(() -> engine.classify(id, account("1100"), account("4000"), giftType(),
                Map.of(9003L, 50000L, 9005L, 40000L))).isInstanceOf(IllegalArgumentException.class);
        engine.classify(id, account("1100"), account("4000"), giftType(), Map.of(9003L, 60000L, 9005L, 40000L));
        assertThat(engine.donations(date, date)).hasSize(2);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        assertThat(engine.donations(date, date)).hasSize(2);
        assertThat(engine.donations(date, date).stream().map(r -> r.get("SHARE"))).containsExactlyInAnyOrder("60000",
                "40000");
        assertThat(engine.report(date, date).get("offeringsTotal")).isEqualTo("100000");
        balanced();
    }

    @Test
    @DisplayName("비활성 계정과 헌금 종류도 과거 조회에는 남는다")
    void inactive_historyRetained() {
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        engine.settings("account", Map.of("id", account("4000"), "name", "헌금수익", "type", "INCOME", "active", false));
        engine.settings("offeringType", Map.of("id", giftType(), "name", "약정헌금", "active", false));
        assertThat(engine.donations(date, date)).hasSize(1);
        records.deleteFinancialRecords(List.of(id));
        assertThat(engine.report(date, date).get("income")).isEqualTo("0");
        balanced();
    }

    @Test
    @DisplayName("외부 데이터에는 개인 이름·ID·메모·사용자 지정 명칭을 내보내지 않는다")
    void publicProjection_excludesPrivateData() {
        long id = record(true, 100000);
        engine.classify(id, account("1100"), account("4000"), giftType(), null);
        engine.settings("account", Map.of("id", account("1100"), "name", "검증 개인 이름 비공개", "type", "ASSET"));
        Map<String, Object> p = engine.publicReport(date, date);
        assertThat(p.toString()).doesNotContain("검증 개인 이름", "CONTACT_ID", "CONTACT_NAME", "검증 거래", "9003", "records",
                "accounts");
        assertThat(p.keySet()).contains("monthly", "assets", "offerings");
    }

    @Test
    @DisplayName("기존 V3 자료의 합계·건수는 V4 마이그레이션 뒤에도 유지되고 검토로만 표시된다")
    void migration_preservesLegacy() throws Exception {
        String url = "jdbc:h2:mem:legacy-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("3").locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO FISCAL_YEAR(ID,NAME,START_DATE,END_DATE,CLOSED) VALUES(1,'원본','2026-01-01','2026-12-31',FALSE)");
            s.execute("INSERT INTO BUDGET(ID,FISCAL_YEAR_ID,TYPE,NAME,SORT_ORDER) VALUES(1,1,'INCOME','원본',0)");
            s.execute(
                    "INSERT INTO FINANCIAL_RECORD(ID,TYPE,FISCAL_YEAR_ID,DATE,BUDGET_ID,AMOUNT) VALUES(1,'INCOME',1,'2026-08-01',1,123456)");
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT SUM(AMOUNT) FROM FINANCIAL_RECORD");
            r.next();
            assertThat(r.getLong(1)).isEqualTo(123456);
            r = s.executeQuery("SELECT STATUS FROM ACCT_LINK WHERE RECORD_ID=1");
            r.next();
            assertThat(r.getString(1)).isEqualTo("REVIEW");
            r = s.executeQuery("SELECT COUNT(*) FROM ACCT_ENTRY");
            r.next();
            assertThat(r.getInt(1)).isZero();
        }
    }
}
