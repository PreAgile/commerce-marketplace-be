package com.lemong.marketplace.common.web;

import com.lemong.marketplace.common.error.AmountOverflowException;
import com.lemong.marketplace.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 3층 검증의 HTTP 매핑(앱이 거르고, DB가 보장):
 *
 * <ul>
 * <li><b>요청 DTO</b>(Bean Validation) → 400 — 형식·필수값 위반(빠른 피드백)
 * <li><b>도메인 불변식</b>(IllegalArgumentException) → 400 /
 * 상태(IllegalStateException) → 409
 * <li><b>DB 제약</b>(DataIntegrityViolationException) → 409 — 어떤 경로로 들어와도 막는 최후
 * 보루
 * <li><b>없는 리소스</b>(ResourceNotFoundException) → 404
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleNotFound(ResourceNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
		String detail = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> fe.getField() + " " + fe.getDefaultMessage()).findFirst().orElse("validation failed");
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail handleDomainArgument(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(IllegalStateException.class)
	public ProblemDetail handleDomainState(IllegalStateException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	/**
	 * 금액·수량 오버플로우(fail-loud) → 400. 비현실적으로 큰 입력이 원인이라 클라이언트 오류로 본다. 일반
	 * {@link ArithmeticException}은 잡지 않는다(다른 산술 버그는 500으로 드러나야 한다).
	 */
	@ExceptionHandler(AmountOverflowException.class)
	public ProblemDetail handleAmountOverflow(AmountOverflowException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"numeric overflow in amount/quantity calculation");
	}

	/** DB 제약(CHECK/UNIQUE/EXCLUDE/트리거)이 막은 경우 — 불변식의 최후 보루. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "data integrity constraint violated");
	}
}
