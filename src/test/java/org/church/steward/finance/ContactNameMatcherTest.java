package org.church.steward.finance;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ContactNameMatcherTest {
    @Test
    void fullNamesAndOfferingSuffixAreRecognized() {
        var people = Map.of(1L, "가상 헌금자", 2L, "다른 이름");
        assertThat(ContactNameMatcher.match("09:01:02 · 가상헌금자 (약정헌금)", people, List.of("약정헌금"))).containsExactly(1L);
        assertThat(ContactNameMatcher.match("가상 헌금자", people, List.of())).containsExactly(1L);
    }

    @Test
    void ambiguousAndPartialNamesAreNeverGuessed() {
        assertThat(ContactNameMatcher.match("가상헌금자", Map.of(1L, "가상 헌금자", 2L, "가상헌금자"), List.of())).isEmpty();
        assertThat(ContactNameMatcher.match("가상", Map.of(1L, "가상헌금자"), List.of())).isEmpty();
        assertThat(ContactNameMatcher.match("다른 거래내용", Map.of(1L, "가상헌금자"), List.of())).isEmpty();
    }
}
