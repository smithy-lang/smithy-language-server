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

group = "software.amazon.smithy"
version = file("VERSION").readText().trim()
description = "Language Server Protocol implementation for Smithy"

println("Smithy Language Server version: '${version}'")

val stagingDirectory = layout.buildDirectory.dir("staging")
val releaseChangelogFile = layout.buildDirectory.file("resources/RELEASE_CHANGELOG.md").get()
val smithyVersion: String by project

plugins {
    java
    application
    `maven-publish`
    checkstyle

    // Fork of runtime plugin with java 21 support, until https://github.com/beryx/badass-runtime-plugin/issues/153
    // is resolved.
    id("com.dua3.gradle.runtime") version "1.13.1-patch-1"

    id("org.jreleaser") version "1.13.0"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
    implementation("software.amazon.smithy:smithy-build:[$smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-cli:[$smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-model:[$smithyVersion, 2.0[")
    implementation("software.amazon.smithy:smithy-syntax:[$smithyVersion, 2.0[")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.hamcrest:hamcrest:2.2")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    withType<Test>().configureEach {
        useJUnitPlatform()

        testLogging {
            events = setOf(TestLogEvent.SKIPPED, TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    // Reusable license copySpec for building JARs
    val licenseSpec = copySpec {
        from("LICENSE", "NOTICE")
    }

    register<Jar>("sourcesJar") {
        metaInf.with(licenseSpec)
        from(sourceSets.main.get().allJava)
        archiveClassifier = "sources"
    }

    register<Jar>("javadocJar") {
        metaInf.with(licenseSpec)
        from(javadoc)
        archiveClassifier = "javadoc"
    }

    val createProperties by register("createProperties") {
        dependsOn(processResources)

        doLast {
            val file = layout.buildDirectory.file("resources/main/version.properties").get().asFile
            val properties = Properties()
            properties["version"] = version.toString()
            properties.store(file.writer(), null)
        }
    }

    // Generate a changelog that only includes the changes for the latest version
    // which JReleaser will add to the release notes of the GitHub release.
    val createReleaseChangelog by register("createReleaseChangelog") {
        dependsOn(processResources)

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

    classes {
        dependsOn(createProperties)
    }

    jar {
        from(configurations.runtimeClasspath.get().map { zipTree(it) }) {
            // Don't need signature files, and we don't use modules
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        manifest {
            attributes("Main-Class" to "software.amazon.smithy.lsp.Main")
        }
    }

    checkstyleTest {
        enabled = false
    }

    assembleDist {
        dependsOn(publish, runtimeZip)
    }

    jreleaserRelease {
        dependsOn(createReleaseChangelog)
    }
}

application {
    mainClass = "software.amazon.smithy.lsp.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
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

    targetPlatform("windows-x86_64") {
        jdkHome = jdkDownload("${correttoRoot}-x64-windows-jdk.zip")
    }

    // This block creates a custom `Zip` task for each `targetPlatform`, which zip up the contents of
    // the platform's runtime image directory created by the runtime plugin. These tasks replace the
    // action of `runtimeZip`.
    //
    // The only difference between this and what the runtime plugin does by default, is that the
    // plugin zips each runtime image _directory_, whereas this zips each directory's _contents_.
    // For example, with the runtime plugin's zips, if you `unzip smithy-language-server-<platform>.zip`,
    // the launch script will be `./smithy-language-server-<platform>/bin/smithy-language-server`. With
    // this, it will just be `./bin/smithy-language-server`. This makes it easier to download and run
    // because there isn't an extra directory to go through, with a platform-dependent name.
    afterEvaluate {
        val runtimeZipTasks = targetPlatforms.get().keys.map { platformName ->
            tasks.register<Zip>("runtimeZip$platformName") {
                dependsOn(tasks.runtime)

                archiveFileName = "smithy-language-server-$platformName.zip"
                destinationDirectory = layout.buildDirectory.dir("image")
                from(layout.buildDirectory.dir("image/smithy-language-server-$platformName"))
            }
        }

        tasks.runtimeZip {
            dependsOn(runtimeZipTasks)
            actions.clear()
        }
    }
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
                path = layout.buildDirectory.file("image/smithy-language-server-windows-x86_64.zip")
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
