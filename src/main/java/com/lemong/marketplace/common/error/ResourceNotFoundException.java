package com.lemong.marketplace.common.error;

/**
 * 존재하지 않는 리소스 접근의 공통 베이스. 각 컨텍스트가 이를 상속한다(예: CartNotFoundException).
 *
 * <p>
 * 공통 모듈이 컨텍스트를 거꾸로 의존하지 않도록, 예외 매핑은 이 베이스 타입으로 한다 (컨텍스트→common 단방향 의존 유지).
 * GlobalExceptionHandler가 404로 매핑.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
