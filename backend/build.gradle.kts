// SPDX-License-Identifier: AGPL-3.0-only
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

group = "com.honestrobin.time"
version = (findProperty("honestrobinVersion") as String?) ?: "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

repositories {
    mavenCentral()
}

// `./gradlew :backend:checkLicense` rejects runtime dependencies with AGPL-incompatible licences.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    allowedLicensesFile = rootProject.file("config/allowed-licenses.json")
    filters = arrayOf(com.github.jk1.license.filter.LicenseBundleNormalizer())
}

// jOOQ classes are generated from the Flyway migrations by `./gradlew :backend:jooqCodegen`
// and committed, so a normal build never needs a running database.
val generatedJooqDir = layout.projectDirectory.dir("src/generated/jooq")

sourceSets {
    main {
        java.srcDir(generatedJooqDir)
    }
    create("codegen") {
        kotlin.srcDir("src/codegen/kotlin")
    }
}

val codegenImplementation: Configuration by configurations.getting
val codegenRuntimeOnly: Configuration by configurations.getting

// Spring Boot's BOM still pins Kotlin 1.9; keep the libraries in step with the compiler plugin.
extra["kotlin.version"] = "2.4.20"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.17")
    implementation("com.github.kagkarlsson:db-scheduler-spring-boot-starter:16.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
    // Invoice PDFs: HTML rendered to PDF (PDF/A-capable), spec §3.1.
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.22")
    // Report exports to Excel (spec §12); streams rows, Apache-2.0.
    implementation("org.dhatim:fastexcel:0.20.2")
    // CSV imports (Harvest's own exports, spec §6.6), Apache-2.0.
    implementation("org.apache.commons:commons-csv:1.14.1")
    // QR codes for setting up two-factor sign-in (MIT).
    implementation("io.nayuki:qrcodegen:1.8.0")
    // E-invoices (spec §5.7, M5): Factur-X/ZUGFeRD and XRechnung (CII), Apache-2.0.
    implementation("org.mustangproject:library:2.26.0") {
        // FOP and Batik only serve Mustang's XML-to-PDF visualiser, which we don't use.
        exclude(group = "org.apache.xmlgraphics", module = "fop-core")
    }
    // CII to UBL for Peppol BIS; the current ph-commons generation, as the validators in tests use.
    // (Mustang only pulls the older 2.x along; it doesn't call it.)
    implementation("com.helger:en16931-cii2ubl:4.0.1")
    // JAXB for reading CII and writing UBL, version from the Spring Boot BOM.
    implementation("org.glassfish.jaxb:jaxb-runtime")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Also used directly: LISTEN for live updates (platform/live).
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    // Property-based tests for the time and money arithmetic.
    testImplementation("io.kotest:kotest-property:5.9.1")
    // The official EN 16931, XRechnung and Peppol BIS validation rules, to check our e-invoices (AT-5.1).
    testImplementation("com.helger.phive.rules:phive-rules-en16931:4.6.3")
    testImplementation("com.helger.phive.rules:phive-rules-xrechnung:4.6.3")
    testImplementation("com.helger.phive.rules:phive-rules-peppol:4.6.3")
    // PDF/A-3 conformance of the Factur-X PDFs (AT-5.1), MPL-2.0 (dual GPL).
    testImplementation("org.verapdf:validation-model-jakarta:1.30.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    codegenImplementation("org.jooq:jooq-codegen")
    codegenImplementation("org.jooq:jooq-meta")
    codegenImplementation("org.flywaydb:flyway-core")
    codegenImplementation("org.flywaydb:flyway-database-postgresql")
    codegenImplementation("org.testcontainers:postgresql")
    codegenRuntimeOnly("org.postgresql:postgresql")
    codegenRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

val jooqCodegen by tasks.registering(JavaExec::class) {
    group = "build setup"
    description = "Runs Flyway migrations against a throwaway Postgres and regenerates the jOOQ classes."
    classpath = sourceSets["codegen"].runtimeClasspath
    mainClass.set("com.honestrobin.time.codegen.JooqCodegenKt")
    workingDir = projectDir
    jvmArgs("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    listOf("JOOQ_JDBC_URL", "JOOQ_JDBC_USER", "JOOQ_JDBC_PASSWORD", "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED")
        .forEach { name -> System.getenv(name)?.let { environment(name, it) } }
}

// The built SPA is copied into the jar so a single image serves both.
val frontendDist = rootProject.layout.projectDirectory.dir("frontend/dist")
// The browser extension's selector config, served to it at /extension/selectors.json (spec §9).
val extensionSelectors = rootProject.layout.projectDirectory.file("extension/src/selectors.json")
tasks.processResources {
    if (frontendDist.asFile.exists()) {
        from(frontendDist) { into("static") }
    }
    if (extensionSelectors.asFile.exists()) {
        from(extensionSelectors) { into("extension") }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1536m"
    systemProperty("honestrobin.edition", System.getenv("HONESTROBIN_EDITION") ?: "selfhost")
    listOf("DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED", "HONESTROBIN_TEST_JDBC_URL").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

springBoot {
    buildInfo()
}

tasks.bootJar {
    archiveFileName.set("honestrobin-time.jar")
}

tasks.jar {
    enabled = false
}
