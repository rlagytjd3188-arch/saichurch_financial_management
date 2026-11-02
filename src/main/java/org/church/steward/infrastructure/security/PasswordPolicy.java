package org.church.steward.infrastructure.security;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 로컬 서비스 비밀번호 길이 검증 유틸 (완전 오프라인, 외부 의존성 없음).
 * <ul>
 * <li>문자 종류 조합 규칙 없음</li>
 * <li>길이: 4~64자 (유니코드 코드포인트 기준)</li>
 * <li>사용자 요청에 따라 문자 조합과 패턴 제한은 적용하지 않는다.</li>
 * </ul>
 */
public final class PasswordPolicy {

    public enum Violation {
        TOO_SHORT("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다. 여러 단어를 이어 만든 문장형 비밀번호를 권장합니다."),
        TOO_LONG("비밀번호는 " + MAX_LENGTH + "자 이하여야 합니다.");

        private final String message;

        Violation(String message) {
            this.message = message;
        }

        /** 사용자에게 그대로 보여줄 수 있는 한국어 안내 메시지 */
        public String getMessage() {
            return message;
        }
    }

    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 64;

    private PasswordPolicy() {
        // 인스턴스화 방지
    }

    /** 검증뿐 아니라 해싱(가입/변경)과 로그인 비교 전에도 동일하게 적용해야 한다. */
    public static String normalize(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    /**
     * @param rawPassword  사용자가 입력한 비밀번호
     * @param contextWords 기존 호출 호환을 위해 유지하며 길이 검증에는 사용하지 않는다 (null 허용)
     * @return 위반 항목 목록 (비어 있으면 통과)
     */
    public static List<Violation> validate(String rawPassword, Collection<String> contextWords) {
        List<Violation> violations = new ArrayList<>();
        if (rawPassword == null || rawPassword.isEmpty()) {
            violations.add(Violation.TOO_SHORT);
            return violations;
        }

        String pw = normalize(rawPassword);
        int length = pw.codePointCount(0, pw.length());
        if (length < MIN_LENGTH)
            violations.add(Violation.TOO_SHORT);
        if (length > MAX_LENGTH)
            violations.add(Violation.TOO_LONG);

        return violations;
    }

}
