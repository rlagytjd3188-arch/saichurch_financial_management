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
    JdbcTemplate jdbc;
    @Autowired
    FinancialRecordService records;
    @Autowired
    UserSessionHolder sessions;
    @Autowired
    FinanceWeb web;
    @Autowired
    org.church.steward.domain.user.repository.UserRepository users;
    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final LocalDate date = LocalDate.of(2044, 1, 5);

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
        for (String table : List.of("OFFERING_ALLOCATION", "ACCT_LINK", "ACCT_LINE"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM ACCT_ENTRY WHERE REVERSES_ID IS NOT NULL");
        jdbc.update("DELETE FROM ACCT_ENTRY");
        for (String table : List.of("FINANCIAL_RECORD_CONTACT", "FINANCIAL_RECORD", "CONTACT_GROUP_MEMBER",
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
