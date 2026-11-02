package org.church.steward.finance;

import java.text.Normalizer;
import java.util.*;

/** 은행 내용이 이름 전체와 일치할 때만 연결한다. 부분 이름과 동명이인은 추정하지 않는다. */
public final class ContactNameMatcher {
    private ContactNameMatcher() {
    }

    private static String key(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replaceFirst("^\\d{2}:\\d{2}:\\d{2}\\s*[·:]?\\s*", "").replaceAll("[\\s\\p{Z}()\\[\\]{}·:：/_-]", "");
    }

    public static List<Long> match(String text, Map<Long, String> people, Collection<String> offeringNames) {
        String value = key(text);
        if (value.isEmpty())
            return List.of();
        List<Long> exact = people.entrySet().stream().filter(e -> key(e.getValue()).equals(value))
                .map(Map.Entry::getKey).toList();
        if (!exact.isEmpty())
            return exact.size() == 1 ? exact : List.of();
        Set<Long> matches = new LinkedHashSet<>();
        for (var p : people.entrySet())
            for (String type : offeringNames) {
                String name = key(p.getValue()), gift = key(type);
                if (!gift.isEmpty() && (value.equals(name + gift) || value.equals(gift + name)))
                    matches.add(p.getKey());
            }
        return matches.size() == 1 ? List.copyOf(matches) : List.of();
    }
}
