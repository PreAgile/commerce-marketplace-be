package com.lemong.marketplace;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * AGENTS.md 황금률 #1(DDD 경계)을 실행 가능한 테스트로 박제한다. 산문 규칙은 썩지만 테스트는 안 썩는다 — 누가 BC 경계를
 * 넘는 import를 추가하면 CI가 빨갛게 막는다.
 */
@AnalyzeClasses(packages = "com.lemong.marketplace", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule 컨텍스트_간_순환_의존_없음 = slices().matching("com.lemong.marketplace.(*)..").should().beFreeOfCycles();

	// BC끼리는 서로의 내부를 못 본다. 협력은 published 계약(읽기 모델/포트)으로만 — 그 의존만 예외로 허용한다
	// (예: order → cart.published). common(공유 인프라)은 슬라이스에서 빠지므로 BC→common은 자유.
	@ArchTest
	static final ArchRule BC는_서로의_내부를_참조하지_않는다 = slices()
			.matching("com.lemong.marketplace.(cart|order|payment|shipping|settlement)..").should()
			.notDependOnEachOther().ignoreDependency(alwaysTrue(), resideInAPackage("..published.."));

	// 의존 방향은 web/infra → application → domain. 도메인이 상위 계층을 거꾸로 의존하면 안 된다.
	@ArchTest
	static final ArchRule 도메인은_상위_계층을_의존하지_않는다 = noClasses().that().resideInAPackage("..domain..").should()
			.dependOnClassesThat().resideInAnyPackage("..application..", "..web..", "..infra..");
}
