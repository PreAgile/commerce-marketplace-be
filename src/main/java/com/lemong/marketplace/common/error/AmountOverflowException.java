package com.lemong.marketplace.common.error;

/**
 * 금액·수량 계산이 long/int 범위를 넘어 fail-loud로 터진 경우.
 *
 * <p>
 * 전역 매핑이 일반 {@link ArithmeticException}을 통째로 400으로 보면 다른 컨텍스트의 산술 버그(0 나누기 등)까지
 * 클라이언트 오류로 오분류된다. 금액·수량 경로에서만 이 전용 예외를 던져 400으로 좁힌다.
 */
public class AmountOverflowException extends ArithmeticException {

	public AmountOverflowException(String message) {
		super(message);
	}
}
