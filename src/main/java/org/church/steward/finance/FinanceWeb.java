package org.church.steward.finance;

import com.sun.net.httpserver.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.church.steward.infrastructure.security.*;
import org.church.steward.domain.user.repository.UserRepository;
import org.church.steward.domain.financialrecord.service.FinancialRecordService;
import org.church.steward.domain.contact.service.ContactService;
import org.church.steward.domain.contact.dto.ContactDto;
import org.church.steward.domain.contact.entity.ContactType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/** 관리자용 루프백 웹 화면과 집계 전용 LAN 공개 서버의 경계를 분리한다. */
@Component
@RequiredArgsConstructor
public class FinanceWeb {
    private final FinanceEngine engine;
    private final FinanceCommands commands;
    private final PledgeService pledges;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final FinancialRecordService records;
    private final ContactService contacts;
    private final UserSessionHolder desktopSession;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private HttpServer adminServer, publicServer;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    private record WebSession(String id, boolean admin, Instant expires, String passwordHash) {
    }

    private String token() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public synchronized URI open(String page) throws IOException {
        if (desktopSession.getLoggedInUser() == null)
            throw new IllegalStateException("먼저 로그인하세요.");
        if (adminServer == null) {
            String origin = adminOrigin();
            String bind = System.getProperty("sai.admin.bind", origin.startsWith("https:") ? "0.0.0.0" : "127.0.0.1");
            if (origin.startsWith("https:")) {
                try {
                    java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
                    String password = System.getenv("SAI_TLS_PASSWORD"), file = System.getenv("SAI_TLS_KEYSTORE");
                    if (password == null || file == null)
                        throw new IOException("모바일 관리 HTTPS 인증서 설정이 필요합니다.");
                    try (InputStream input = new FileInputStream(file)) {
                        store.load(input, password.toCharArray());
                    }
                    javax.net.ssl.KeyManagerFactory km = javax.net.ssl.KeyManagerFactory
                            .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                    km.init(store, password.toCharArray());
                    javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
                    ssl.init(km.getKeyManagers(), null, null);
                    HttpsServer secure = HttpsServer.create(new InetSocketAddress(bind, 8765), 0);
                    secure.setHttpsConfigurator(new HttpsConfigurator(ssl));
                    adminServer = secure;
                } catch (Exception ex) {
                    throw new IOException("HTTPS 인증서 설정을 확인하세요.", ex);
                }
            } else {
                if (!bind.equals("127.0.0.1"))
                    throw new IOException("모바일 관리 서버는 HTTPS 설정이 필요합니다.");
                adminServer = HttpServer.create(new InetSocketAddress(bind, 8765), 0);
            }
            adminServer.createContext("/", this::admin);
            adminServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            adminServer.start();
        }
        if (enabled())
            startPublic();
        return URI.create(adminOrigin() + "/#" + page);
    }

    private String adminOrigin() {
        return System.getProperty("sai.admin.origin", "http://127.0.0.1:8765");
    }

    private boolean allowedHost(String host) {
        return Objects.equals(host, URI.create(adminOrigin()).getRawAuthority())
                || adminOrigin().equals("http://127.0.0.1:8765") && Objects.equals(host, "localhost:8765");
    }

    private boolean enabled() {
        return "true".equals(jdbc.queryForObject(
                "SELECT VALUE_TEXT FROM FINANCE_PREFERENCE WHERE KEY_NAME='PUBLIC_ENABLED'", String.class));
    }

    private synchronized void startPublic() throws IOException {
        if (publicServer != null)
            return;
        publicServer = HttpServer.create(new InetSocketAddress("0.0.0.0", 8766), 0);
        publicServer.createContext("/", this::publicRequest);
        publicServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        publicServer.start();
    }

    private String shareToken() {
        List<String> found = jdbc
                .queryForList("SELECT VALUE_TEXT FROM FINANCE_PREFERENCE WHERE KEY_NAME='PUBLIC_TOKEN'", String.class);
        if (!found.isEmpty())
            return found.getFirst();
        String t = token();
        jdbc.update("INSERT INTO FINANCE_PREFERENCE VALUES('PUBLIC_TOKEN',?)", t);
        return t;
    }

    private String shareUrl() throws IOException {
        String configured = System.getProperty("sai.public.origin");
        if (configured != null) {
            URI origin = URI.create(configured);
            if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || !Set.of("", "/").contains(origin.getPath()))
                throw new IOException("공개 주소에는 HTTPS 원점을 입력하세요.");
            return configured.replaceAll("/$", "") + "/" + shareToken();
        }
        String host = java.net.NetworkInterface.networkInterfaces().flatMap(java.net.NetworkInterface::inetAddresses)
                .filter(a -> a instanceof java.net.Inet4Address && a.isSiteLocalAddress())
                .map(java.net.InetAddress::getHostAddress).sorted().findFirst().orElse("127.0.0.1");
        return "http://" + host + ":8766/" + shareToken();
    }

    private Object clean(Object value) {
        if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp
                || value instanceof java.time.temporal.TemporalAccessor)
            return value.toString();
        if (value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal)
            return value.toString();
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put(k.toString(), clean(v)));
            return result;
        }
        if (value instanceof Collection<?> c)
            return c.stream().map(this::clean).toList();
        return value;
    }

    private void send(HttpExchange e, int status, Object data) throws IOException {
        byte[] b = json.writeValueAsBytes(clean(data));
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.sendResponseHeaders(status, b.length);
        e.getResponseBody().write(b);
        e.close();
    }

    private void html(HttpExchange e) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/finance-web/index.html")) {
            byte[] b = Objects.requireNonNull(in).readAllBytes();
            e.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            e.getResponseHeaders().set("Cache-Control", "no-store");
            e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            e.getResponseHeaders().set("X-Frame-Options", "DENY");
            e.sendResponseHeaders(200, b.length);
            e.getResponseBody().write(b);
            e.close();
        }
    }

    private Map<String, String> params(HttpExchange e) {
        Map<String, String> p = new HashMap<>();
        String q = e.getRequestURI().getRawQuery();
        if (q != null)
            for (String part : q.split("&")) {
                String[] v = part.split("=", 2);
                p.put(URLDecoder.decode(v[0], StandardCharsets.UTF_8),
                        v.length > 1 ? URLDecoder.decode(v[1], StandardCharsets.UTF_8) : "");
            }
        return p;
    }

    private LocalDate date(Map<String, String> p, String key, LocalDate fallback) {
        return p.containsKey(key) ? LocalDate.parse(p.get(key)) : fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpExchange e) throws IOException {
        byte[] b = e.getRequestBody().readNBytes(1048577);
        if (b.length > 1048576)
            throw new IllegalArgumentException("요청이 너무 큽니다.");
        return json.readValue(b, Map.class);
    }

    private void origin(HttpExchange e) {
        String origin = e.getRequestHeaders().getFirst("Origin"), host = e.getRequestHeaders().getFirst("Host");
        if (origin == null || !origin.equals((adminOrigin().startsWith("https:") ? "https://" : "http://") + host)
                || !allowedHost(host))
            throw new SecurityException("허용되지 않은 요청 출처입니다.");
    }

    private WebSession authenticated(HttpExchange e) {
        String cookie = e.getRequestHeaders().getFirst("Cookie");
        if (cookie == null)
            throw new SecurityException("로그인이 필요합니다.");
        for (String s : cookie.split(";")) {
            String[] p = s.trim().split("=", 2);
            if (p.length == 2 && p[0].equals("sai_session")) {
                WebSession session = sessions.get(p[1]);
                if (session != null && session.expires.isAfter(Instant.now())) {
                    var current = users.findById(session.id);
                    if (current.isEmpty() || !current.get().getPassword().equals(session.passwordHash)
                            || current.get().getRole().name().equals("ADMIN") != session.admin)
                        throw new SecurityException("다시 로그인하세요.");
                    return session;
                }
            }
        }
        throw new SecurityException("로그인이 만료되었습니다.");
    }

    private void admin(HttpExchange e) throws IOException {
        try {
            String path = e.getRequestURI().getPath();
            if (!allowedHost(e.getRequestHeaders().getFirst("Host")))
                throw new SecurityException("PC 로컬 주소를 사용하세요.");
            if (!path.startsWith("/api/")) {
                if (!e.getRequestMethod().equals("GET")) {
                    send(e, 405, Map.of("error", "읽기 요청만 가능합니다."));
                    return;
                }
                html(e);
                return;
            }
            if (path.equals("/api/login") && e.getRequestMethod().equals("POST")) {
                origin(e);
                Map<String, Object> b = body(e);
                String id = String.valueOf(b.get("id"));
                if (failures.getOrDefault(id, 0) >= 10)
                    throw new SecurityException("로그인 실패가 누적되었습니다. 앱을 재시작한 뒤 확인하세요.");
                var user = users.findById(id);
                if (user.isEmpty() || !passwords.matches(PasswordPolicy.normalize(String.valueOf(b.get("password"))),
                        user.get().getPassword())) {
                    failures.merge(id, 1, Integer::sum);
                    throw new SecurityException("아이디 또는 비밀번호를 확인하세요.");
                }
                failures.remove(id);
                String t = token();
                sessions.put(t, new WebSession(id, user.get().getRole().name().equals("ADMIN"),
                        Instant.now().plusSeconds(1800), user.get().getPassword()));
                e.getResponseHeaders().add("Set-Cookie",
                        "sai_session=" + t + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800"
                                + (e instanceof HttpsExchange ? "; Secure" : ""));
                send(e, 200, Map.of("ok", true));
                return;
            }
            WebSession session = authenticated(e);
            RequestIdentity.set(
                    new LoggedInUser(session.id, session.admin ? org.church.steward.domain.user.entity.UserRole.ADMIN
                            : org.church.steward.domain.user.entity.UserRole.USER));
            Map<String, String> p = params(e);
            LocalDate from = date(p, "from", LocalDate.now().withDayOfYear(1)), to = date(p, "to", LocalDate.now());
            if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 36525)
                throw new IllegalArgumentException("조회 기간을 확인하세요.");
            if (e.getRequestMethod().equals("GET")) {
                switch (path) {
                case "/api/pledges" -> {
                    if (!session.admin)
                        throw new SecurityException("관리자 권한이 필요합니다.");
                    send(e, 200, pledges.summary(from, to));
                }
                case "/api/me" -> send(e, 200, Map.of("admin", session.admin));
                case "/api/public-preview" -> send(e, 200, engine.publicReport(from, to));
                case "/api/report" -> send(e, 200, engine.report(from, to));
                case "/api/recent" -> send(e, 200, engine.report(to.minusMonths(11).withDayOfMonth(1), to));
                case "/api/share" -> {
                    if (!session.admin)
                        throw new SecurityException("관리자 권한이 필요합니다.");
                    send(e, 200,
                            Map.of("enabled", enabled(), "url", enabled() ? shareUrl() : ""));
                }
                case "/api/data" -> {
                    if (!session.admin)
                        throw new SecurityException("관리자 권한이 필요합니다.");
                    send(e, 200, engine.data());
                }
                case "/api/donations" -> {
                    if (!session.admin)
                        throw new SecurityException("관리자 권한이 필요합니다.");
                    send(e, 200, engine.donations(from, to));
                }
                case "/api/timeline" -> {
                    List<Map<String, Object>> months = new ArrayList<>();
                    for (java.time.YearMonth m = java.time.YearMonth.from(from); !m
                            .isAfter(java.time.YearMonth.from(to)); m = m.plusMonths(1)) {
                        Map<String, Object> report = engine.report(m.atDay(1),
                                m.atEndOfMonth().isAfter(to) ? to : m.atEndOfMonth());
                        months.add(Map.of("label", m.toString(), "assets", report.get("assets"), "liabilities",
                                report.get("liabilities"), "netAssets", report.get("netAssets")));
                    }
                    send(e, 200, months);
                }
                case "/api/comparison" -> {
                    long days = to.toEpochDay() - from.toEpochDay() + 1;
                    LocalDate previousFrom = from.minusDays(days), previousTo = from.minusDays(1);
                    if (from.getDayOfMonth() == 1 && to.equals(java.time.YearMonth.from(from).atEndOfMonth())) {
                        previousFrom = from.minusMonths(1);
                        previousTo = previousFrom
                                .withDayOfMonth(java.time.YearMonth.from(previousFrom).lengthOfMonth());
                    } else if (from.getDayOfYear() == 1 && to.equals(from.withMonth(12).withDayOfMonth(31))) {
                        previousFrom = from.minusYears(1);
                        previousTo = to.minusYears(1);
                    }
                    send(e, 200,
                            Map.of("current", engine.report(from, to), "previous",
                                    engine.report(previousFrom, previousTo), "lastYear",
                                    engine.report(from.minusYears(1), to.minusYears(1))));
                }
                default -> send(e, 404, Map.of("error", "경로가 없습니다."));
                }
                return;
            }
            origin(e);
            if (path.equals("/api/logout")) {
                sessions.entrySet().removeIf(x -> x.getValue().equals(session));
                e.getResponseHeaders().add("Set-Cookie", "sai_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                send(e, 200, Map.of("ok", true));
                return;
            }
            if (!session.admin)
                throw new SecurityException("관리자 권한이 필요합니다.");
            if (!e.getRequestMethod().equals("POST")) {
                send(e, 405, Map.of("error", "지원하지 않는 요청입니다."));
                return;
            }
            Map<String, Object> b = body(e);
            String op = String.valueOf(b.get("operation"));
            switch (op) {
            case "pledge" -> pledges.save(b);
            case "cancelPledge" -> pledges.cancel(FinanceEngine.number(b.get("id")));
            case "account", "offeringType" -> engine.settings(op, b);
            case "linkOfferings" ->
                engine.linkBudgetOfferings(((List<?>) b.get("ids")).stream().map(FinanceEngine::number).toList());
            case "classify" -> {
                Map<Long, Long> allocation = new HashMap<>();
                if (b.get("allocations") instanceof Map<?, ?> m)
                    for (var x : m.entrySet())
                        allocation.put(FinanceEngine.number(x.getKey()), FinanceEngine.number(x.getValue()));
                engine.classify(FinanceEngine.number(b.get("id")), FinanceEngine.number(b.get("asset")),
                        FinanceEngine.number(b.get("counterpart")),
                        b.get("offering") == null ? null : FinanceEngine.number(b.get("offering")), allocation);
            }
            case "journal" -> {
                List<FinanceEngine.Line> lines = new ArrayList<>();
                for (Object o : (List<?>) b.get("lines")) {
                    Map<?, ?> l = (Map<?, ?>) o;
                    lines.add(new FinanceEngine.Line(FinanceEngine.number(l.get("accountId")),
                            FinanceEngine.number(l.get("debit")), FinanceEngine.number(l.get("credit"))));
                }
                engine.journal(FinanceEngine.number(b.get("year")), LocalDate.parse(b.get("date").toString()),
                        String.valueOf(b.get("memo")), lines, null);
            }
            case "openingBalance" -> engine.openingBalance(FinanceEngine.number(b.get("year")),
                    LocalDate.parse(b.get("date").toString()), FinanceEngine.number(b.get("amount")),
                    b.get("asset") == null ? engine.defaultAsset() : FinanceEngine.number(b.get("asset")));
            case "contact" -> {
                ContactDto dto = new ContactDto(b.get("id") == null ? null : FinanceEngine.number(b.get("id")),
                        ContactType.INDIVIDUAL, String.valueOf(b.get("name")),
                        String.valueOf(b.getOrDefault("memo", "")), Boolean.FALSE.equals(b.get("active")));
                if (dto.getId() == null)
                    contacts.createContact(dto);
                else
                    contacts.updateContact(dto.getId(), dto.getType(), dto.getName(), dto.getRemarks(), dto.isHidden());
            }
            case "record" -> commands.record(b);
            case "autoDonors" -> {
                int changed = commands
                        .autoConnectDonors(((List<?>) b.get("ids")).stream().map(FinanceEngine::number).toList());
                send(e, 200, Map.of("changed", changed));
                return;
            }
            case "cancelSelected" -> records.deleteFinancialRecords(
                    ((List<?>) b.get("ids")).stream().map(FinanceEngine::number).distinct().toList());
            case "cancel" -> records.deleteFinancialRecords(List.of(FinanceEngine.number(b.get("id"))));
            case "reverseJournal" -> engine.reverseManual(FinanceEngine.number(b.get("id")));
            case "share" -> {
                boolean on = Boolean.TRUE.equals(b.get("enabled"));
                if (on) {
                    shareUrl();
                    startPublic();
                }
                jdbc.update("UPDATE FINANCE_PREFERENCE SET VALUE_TEXT=? WHERE KEY_NAME='PUBLIC_ENABLED'",
                        String.valueOf(on));
                send(e, 200, Map.of("enabled", on, "url", on ? shareUrl() : ""));
                return;
            }
            default -> throw new IllegalArgumentException("지원하지 않는 작업입니다.");
            }
            send(e, 200, Map.of("ok", true));
        } catch (SecurityException ex) {
            send(e, 403, Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            send(e, 400, Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            send(e, 500, Map.of("error", "처리하지 못했습니다. 앱 로그와 설정을 확인하세요."));
        } finally {
            RequestIdentity.clear();
        }
    }

    private void publicRequest(HttpExchange e) throws IOException {
        try {
            if (!e.getRequestMethod().equals("GET")) {
                send(e, 405, Map.of("error", "읽기 전용입니다."));
                return;
            }
            if (!enabled()) {
                send(e, 404, Map.of("error", "공개가 꺼져 있습니다."));
                return;
            }
            String prefix = "/" + shareToken();
            String path = e.getRequestURI().getPath();
            if (!path.equals(prefix) && !path.equals(prefix + "/data")) {
                send(e, 404, Map.of("error", "공개 자료가 없습니다."));
                return;
            }
            if (path.endsWith("/data")) {
                Map<String, String> p = params(e);
                LocalDate from = date(p, "from", LocalDate.now().withDayOfYear(1)), to = date(p, "to", LocalDate.now());
                if (to.isBefore(from) || to.toEpochDay() - from.toEpochDay() > 36525)
                    throw new IllegalArgumentException();
                send(e, 200, engine.publicReport(from, to));
            } else
                html(e);
        } catch (Exception ex) {
            send(e, 400, Map.of("error", "조회 기간을 확인하세요."));
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (adminServer != null)
            adminServer.stop(0);
        if (publicServer != null)
            publicServer.stop(0);
        adminServer = null;
        publicServer = null;
        sessions.clear();
    }
}
