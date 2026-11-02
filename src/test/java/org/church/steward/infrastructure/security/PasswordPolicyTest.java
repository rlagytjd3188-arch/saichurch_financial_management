package org.church.steward.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    @DisplayName("4글자 비밀번호는 숫자·한글·기호 조합 제한 없이 사용할 수 있다")
    void validate_fourCharacters_succeeds() {
        for (String password : List.of("1234", "가나다라", "aaaa", "a!2Z")) {
            assertThat(PasswordPolicy.validate(password, List.of("관리자"))).isEmpty();
        }
    }

    @Test
    @DisplayName("3글자 이하와 빈 값은 거절한다")
    void validate_short_rejected() {
        for (String password : List.of("", "123", "가나다")) {
            assertThat(PasswordPolicy.validate(password, List.of())).contains(PasswordPolicy.Violation.TOO_SHORT);
        }
        assertThat(PasswordPolicy.validate(null, List.of())).contains(PasswordPolicy.Violation.TOO_SHORT);
    }

    @Test
    @DisplayName("길이는 정규화한 유니코드 코드포인트로 계산한다")
    void validate_unicode_succeeds() {
        assertThat(PasswordPolicy.validate("😀😃😄😁", List.of())).isEmpty();
        assertThat(PasswordPolicy.normalize("ＡＢＣＤ")).isEqualTo("ABCD");
    }

    @Test
    @DisplayName("64글자까지 허용하고 초과하면 거절한다")
    void validate_maximum_checked() {
        assertThat(PasswordPolicy.validate("가".repeat(64), List.of())).isEmpty();
        assertThat(PasswordPolicy.validate("가".repeat(65), List.of())).contains(PasswordPolicy.Violation.TOO_LONG);
    }
}
