/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jreleaser.model.Active
import org.jreleaser.model.Distribution.DistributionType
import org.jreleaser.model.Stereotype
import java.util.Properties

import java.util.regex.Pattern

plugins {
    java
    application
    `maven-publish`
    checkstyle

    id("org.jreleaser") version "1.13.0"

    // Fork of runtime plugin with java 21 support, until https://github.com/beryx/badass-runtime-plugin/issues/153
    // is resolved.
    id("com.dua3.gradle.runtime") version "1.13.1-patch-1"
}

// Reusable license copySpec for building JARs
val licenseSpec = copySpec {
    from("LICENSE", "NOTICE")
}

tasks.register<Jar>("sourcesJar") {
    metaInf.with(licenseSpec)
    from(sourceSets.main.get().allJava)
    archiveClassifier = "sources"
}

tasks.register<Jar>("javadocJar") {
    metaInf.with(licenseSpec)
    from(tasks.javadoc)
    archiveClassifier = "javadoc"
}

group = "software.amazon.smithy"
version = file("VERSION").readText().trim()
description = "Language Server Protocol implementation for Smithy"

println("Smithy Language Server version: '${version}'")

val stagingDirectory = layout.buildDirectory.dir("staging")

repositories {
    mavenLocal()
    mavenCentral()
}

publishing {
    repositories {
        maven {
            name = "localStaging"
            url = uri(stagingDirectory)
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            artifact(tasks["sourcesJar"])
            artifact(tasks["javadocJar"])

            pom {
                name = "Smithy Language Server"
                description = project.description
                url = "https://github.com/smithy-lang/smithy-language-server"

                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }

                developers {
                    developer {
                        id = "smithy"
                        name = "Smithy"
                        organization = "Amazon Web Services"
                        organizationUrl = "https://aws.amazon.com"
                        roles.add("developer")
                    }
                }

                scm {
                    url = "https://github.com/smithy-lang/smithy-language-server.git"
                }
            }
        }
    }
}

checkstyle {
    toolVersion = "10.12.4"
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
    implementation("software.amazon.smithy:smithy-build:[smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-cli:[smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-model:[smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-syntax:[smithyVersion, 2.0[")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.hamcrest:hamcrest:2.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    checkstyle("com.puppycrawl.tools:checkstyle:${checkstyle.toolVersion}")
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events = setOf(TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.register("createProperties") {
    dependsOn(tasks.processResources)

    doLast {
        val file = layout.buildDirectory.file("resources/main/version.properties").get().asFile
        val properties = Properties()
        properties["version"] = version.toString()
        properties.store(file.writer(), null)
    }
}

tasks.classes {
    dependsOn(tasks["createProperties"])
}

application {
    // Define the main class for the application.
    mainClass = "software.amazon.smithy.lsp.Main"
}

tasks.checkstyleTest {
    enabled = false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.jar {
    from(configurations.runtimeClasspath.get().map { zipTree(it) }) {
        // Don't need signature files, and we don't use modules
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    manifest {
        attributes("Main-Class" to "software.amazon.smithy.lsp.Main")
    }
}

runtime {
    addOptions("--compress", "2", "--strip-debug", "--no-header-files", "--no-man-pages")
    addModules("java.logging", "java.naming", "java.xml", "jdk.crypto.ec")

    launcher {
        jvmArgs = listOf(
                "-XX:-UsePerfData",
                "-Xshare:auto",
                "-XX:SharedArchiveFile={{BIN_DIR}}/../lib/smithy.jsa"
        )
    }

    val imageJreVersion = "21"
    val correttoRoot = "https://corretto.aws/downloads/latest/amazon-corretto-${imageJreVersion}"

    targetPlatform("linux-x86_64") {
        jdkHome = jdkDownload("${correttoRoot}-x64-linux-jdk.tar.gz")
    }

    targetPlatform("linux-aarch64") {
        jdkHome = jdkDownload("${correttoRoot}-aarch64-linux-jdk.tar.gz")
    }

    targetPlatform("darwin-x86_64") {
        jdkHome = jdkDownload("${correttoRoot}-x64-macos-jdk.tar.gz")
    }

    targetPlatform("darwin-aarch64") {
        jdkHome = jdkDownload("${correttoRoot}-aarch64-macos-jdk.tar.gz")
    }

    targetPlatform("windows-x64") {
        jdkHome = jdkDownload("${correttoRoot}-x64-windows-jdk.zip")
    }

    // Because we're using target-platforms, it will use this property as a prefix for each target zip
    imageZip = layout.buildDirectory.file("image/smithy-language-server.zip")
}

tasks.assembleDist {
    dependsOn(tasks.publish, tasks.runtimeZip)
}

// Generate a changelog that only includes the changes for the latest version
// which JReleaser will add to the release notes of the GitHub release.
val releaseChangelogFile = layout.buildDirectory.file("resources/RELEASE_CHANGELOG.md").get()
tasks.register("createReleaseChangelog") {
    dependsOn(tasks.processResources)

    doLast {
        val changelog = file("CHANGELOG.md").readText()
        // Copy the text in between the first two version headers
        val matcher = Pattern.compile("^## \\d+\\.\\d+\\.\\d+", Pattern.MULTILINE).matcher(changelog)
        val getIndex = fun(): Int {
            matcher.find()
            return matcher.start()
        }
        val result = changelog.substring(getIndex(), getIndex()).trim()
        releaseChangelogFile.asFile.writeText(result)
    }
}

tasks.jreleaserRelease {
    dependsOn(tasks["createReleaseChangelog"])
}

jreleaser {
    dryrun = false

    project {
        website = "https://smithy.io"
        authors = listOf("Smithy")
        vendor = "Smithy"
        license = "Apache-2.0"
        description = "Smithy Language Server - A Language Server Protocol implementation for the Smithy IDL."
        copyright = "2019"
    }

    release {
        github {
            overwrite = true
            tagName = "{{projectVersion}}"
            releaseName = "Smithy Language Server v{{{projectVersion}}}"

            changelog {
                external = releaseChangelogFile
            }

            commitAuthor {
                name = "smithy-automation"
                email = "github-smithy-automation@amazon.com"
            }
        }
    }

    files {
        active = Active.ALWAYS

        artifact {
            // We'll include the VERSION file in the release artifacts so that the version can be easily
            // retrieving by hitting the GitHub `releases/latest` url
            path = file("VERSION")
            extraProperties.put("skipSigning", true)
        }
    }

    platform {
        // These replacements are for the names of files that are released, *not* for names within this build config
        replacements = mapOf(
                "osx" to "darwin",
                "aarch_64" to "aarch64",
                "windows_x86_64" to "windows_x64"
        )
    }

    distributions {
        create("smithy-language-server") {
            distributionType = DistributionType.JLINK
            stereotype = Stereotype.CLI

            artifact {
                path = layout.buildDirectory.file("image/smithy-language-server-linux-x86_64.zip")
                platform = "linux-x86_64"
            }

            artifact {
                path = layout.buildDirectory.file("image/smithy-language-server-linux-aarch64.zip")
                platform = "linux-aarch_64"
            }

            artifact {
                path = layout.buildDirectory.file("image/smithy-language-server-darwin-x86_64.zip")
                platform = "osx-x86_64"
            }

            artifact {
                path = layout.buildDirectory.file("image/smithy-language-server-darwin-aarch64.zip")
                platform = "osx-aarch_64"
            }

            artifact {
                path = layout.buildDirectory.file("image/smithy-language-server-windows-x64.zip")
                platform = "windows-x86_64"
            }
        }
    }

    checksum {
        individual = true
        files = false
    }

    signing {
        active = Active.RELEASE
        armored = true
        verify = true
    }

    // Configuration for deploying to Maven Central.
    // https://jreleaser.org/guide/latest/examples/maven/maven-central.html#_gradle
    deploy {
        maven {
            nexus2 {
                create("maven-central") {
                    active = Active.ALWAYS
                    url = "https://aws.oss.sonatype.org/service/local"
                    snapshotUrl = "https://aws.oss.sonatype.org/content/repositories/snapshots"
                    closeRepository = true
                    releaseRepository = true
                    stagingRepository(stagingDirectory.get().toString())
                }
            }
        }
    }
}
