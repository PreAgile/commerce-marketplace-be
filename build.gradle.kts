plugins {
	java
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "7.0.2"
}

group = "com.lemong"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.assertj:assertj-core")
	testImplementation("net.jqwik:jqwik:1.9.3")
	testImplementation("net.jqwik:jqwik-spring:0.12.0")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// 포매터: 레이아웃 논쟁을 없애 diff를 로직에만 집중시킨다. spotlessCheck가 check/build에 묶여 CI가 게이트한다.
// 로컬은 `./gradlew spotlessApply`.
//
// Eclipse JDT 포매터를 쓰는 이유: google/palantir-java-format은 com.sun.tools.javac 내부 API를 건드려
// 최신 JDK(이 머신은 25)에서 NoSuchMethodError로 깨진다. Eclipse JDT는 순수 구현이라 JDK 버전에 무관하게
// 동작한다(데몬 JVM 핀 불필요). removeUnusedImports도 기본 엔진(google-format) 대신 JavaParser 변형을 써
// 같은 JDK-내부-API 문제를 피한다.
spotless {
	java {
		eclipse()
		removeUnusedImports("cleanthat-javaparser-unnecessaryimport")
		trimTrailingWhitespace()
		endWithNewline()
	}
}
