/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.gradle.gradlebuild.testing.integrationtests.cleanup.WhenNotEmpty
import org.gradle.build.BuildReceipt
import org.gradle.gradlebuild.test.integrationtests.IntegrationTest
import org.gradle.gradlebuild.test.integrationtests.integrationTestUsesSampleDir
import org.gradle.gradlebuild.test.integrationtests.integrationTestUsesToolingApiJar
import org.gradle.gradlebuild.test.integrationtests.integrationTestUsesDistribution

plugins {
    gradlebuild.distribution.`core-api-java`
    gradlebuild.`publish-public-libraries`
    gradlebuild.`shaded-jar`
}

val buildReceipt: Provider<RegularFile> = rootProject.tasks.named<BuildReceipt>("createBuildReceipt").map { layout.file(provider { it.receiptFile }).get() }

shadedJar {
    shadedConfiguration.exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-api"))
    keepPackages.set(listOf("org.gradle.tooling"))
    unshadedPackages.set(listOf("org.gradle", "org.slf4j", "sun.misc"))
    ignoredPackages.set(setOf("org.gradle.tooling.provider.model"))
    buildReceiptFile.set(buildReceipt)
}

dependencies {
    shadedImplementation(library("slf4j_api")) { version { require(libraryVersion("slf4j_api")) } }

    implementation(project(":baseServices"))
    implementation(project(":messaging"))
    implementation(project(":logging"))
    implementation(project(":coreApi"))
    implementation(project(":core"))
    implementation(project(":wrapper"))

    implementation(library("guava"))

    testFixturesImplementation(project(":coreApi"))
    testFixturesImplementation(project(":core"))
    testFixturesImplementation(project(":modelCore"))
    testFixturesImplementation(project(":baseServices"))
    testFixturesImplementation(project(":baseServicesGroovy"))
    testFixturesImplementation(project(":internalIntegTesting"))
    testFixturesImplementation(library("commons_io"))
    testFixturesImplementation(library("slf4j_api"))

    integTestImplementation(project(":jvmServices"))
    integTestImplementation(project(":persistentCache"))

    crossVersionTestImplementation(project(":jvmServices"))
    crossVersionTestImplementation(testLibrary("jetty"))
    crossVersionTestImplementation(library("commons_io"))
    crossVersionTestRuntimeOnly(testLibrary("cglib")) {
        because("BuildFinishedCrossVersionSpec classpath inference requires cglib enhancer")
    }

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":logging")))
    testImplementation(testFixtures(project(":dependencyManagement")))
    testImplementation(testFixtures(project(":ide")))
    testImplementation(testFixtures(project(":workers")))

    integTestDistributionRuntimeOnly(project(":distributionsBasics"))
    crossVersionTestDistributionRuntimeOnly(project(":distributionsFull"))
}

strictCompile {
    ignoreRawTypes() // raw types used in public API
}

classycle {
    excludePatterns.set(listOf("org/gradle/tooling/**"))
}

apply(from = "buildship.gradle")

tasks.withType<IntegrationTest>().configureEach {
    binaryDistributions.binZipRequired = true
    libsRepository.required = true
}

testFilesCleanup {
    policy.set(WhenNotEmpty.REPORT)
}
integrationTestUsesSampleDir("subprojects/tooling-api/src/main")
integrationTestUsesToolingApiJar()
integrationTestUsesDistribution()
