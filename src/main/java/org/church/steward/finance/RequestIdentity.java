package org.church.steward.finance;

import org.church.steward.infrastructure.security.LoggedInUser;

/** 웹 요청의 감사 주체를 데스크톱 로그인과 분리한다. */
public final class RequestIdentity {
    private static final ThreadLocal<LoggedInUser> CURRENT = new ThreadLocal<>();

    private RequestIdentity() {
    }

    public static LoggedInUser current() {
        return CURRENT.get();
    }

    public static void set(LoggedInUser user) {
        CURRENT.set(user);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
