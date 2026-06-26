package com.lemong.marketplace.common;

/**
 * 돈을 나타내는 값 객체(VO). 최소 단위(minor unit; 원이면 1=1원) 정수로만 다룬다.
 *
 * <p>부동소수점(0.1+0.2≠0.3)으로 1원이 증발/생성되면 정산·대사가 깨지므로 절대 double을 쓰지 않는다.
 * 부호는 허용한다(정산 원장의 음수 라인 등) — 도메인별 부호 규칙은 각 엔티티/제약이 따로 강제한다.
 */
public record Money(long minor) {

    public static final Money ZERO = new Money(0);

    public static Money of(long minor) {
        return new Money(minor);
    }

    // 오버플로우는 *조용히 음수로 래핑*되는 게 돈에선 가장 위험하다 → Math.*Exact로 즉시 ArithmeticException.
    // (침묵하는 데이터 오염보다 큰 소리로 실패하는 게 낫다 — fail-loud)
    public Money plus(Money other) {
        return new Money(Math.addExact(this.minor, other.minor));
    }

    public Money times(int quantity) {
        return new Money(Math.multiplyExact(this.minor, (long) quantity));
    }

    public boolean isNegative() {
        return minor < 0;
    }
}
