package org.church.steward.infrastructure.spreadsheet;

import java.time.LocalDate;
import java.util.*;

/** 카카오뱅크의 안내문·잔액을 거래 금액과 혼동하지 않고 표준 가져오기 열로 변환한다. */
public final class BankStatementNormalizer {
    public static final String CONTACT = "통장거래(미분류)";

    private BankStatementNormalizer() {
    }

    public static boolean supports(SpreadsheetData data) {
        return header(data) >= 0;
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString().strip();
    }

    private static int header(SpreadsheetData data) {
        for (int i = 0; i < Math.min(40, data.rows().size()); i++) {
            var names = data.rows().get(i).stream().map(BankStatementNormalizer::text).map(s -> s.replace(" ", ""))
                    .toList();
            if (names.contains("거래일시") && names.contains("거래금액") && names.contains("구분") && names.contains("거래후잔액"))
                return i;
        }
        return -1;
    }

    public static SpreadsheetData normalize(SpreadsheetData data) {
        int header = header(data);
        if (header < 0)
            return data;
        Map<String, Integer> columns = new HashMap<>();
        var heads = data.rows().get(header);
        for (int i = 0; i < heads.size(); i++)
            columns.put(text(heads.get(i)).replace(" ", ""), i);
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("일자", "구분", "금액", "예산", "내용", "관계자", "비고"));
        for (var source : data.rows().subList(header + 1, data.rows().size())) {
            var get = new java.util.function.Function<String, String>() {
                public String apply(String key) {
                    var i = columns.get(key);
                    return i == null || i >= source.size() ? "" : text(source.get(i));
                }
            };
            String rawDate = get.apply("거래일시"), kind = get.apply("구분"), rawAmount = get.apply("거래금액");
            if (kind.isBlank() && rawAmount.isBlank())
                continue;
            Object date = rawDate, amount = rawAmount;
            try {
                date = LocalDate.parse(rawDate.substring(0, 10).replace('.', '-').replace('/', '-'));
            } catch (RuntimeException ignored) {
            }
            try {
                var n = new java.math.BigInteger(rawAmount.replace(",", "").replace("원", "").strip());
                amount = (kind.equals("출금") ? n.abs() : n).longValueExact();
            } catch (RuntimeException ignored) {
            }
            String time = rawDate.length() > 10 ? rawDate.substring(10).strip() : "";
            String description = (time.isBlank() ? "" : time + " · ") + get.apply("내용");
            String memo = "일시: " + rawDate + " / 거래: " + get.apply("거래구분") + " / 잔액: " + get.apply("거래후잔액") + " / 메모: "
                    + get.apply("메모");
            rows.add(List.of(date, kind, amount, kind.equals("입금") ? "기타수입" : "기타비용", description, CONTACT, memo));
        }
        return new SpreadsheetData(rows, data.charset());
    }
}
