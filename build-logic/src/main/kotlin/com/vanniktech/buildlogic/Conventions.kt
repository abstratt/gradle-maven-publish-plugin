package com.vanniktech.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.catalog(name: String): VersionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named(name)

internal fun VersionCatalog.version(alias: String): String = findVersion(alias)
  .orElseThrow { GradleException("Version '$alias' is not declared in version catalog '$name'") }
  .requiredVersion

/** The conventions shared by every published module of this build. */
internal fun Project.applySharedConventions() {
  pluginManager.apply("org.jetbrains.kotlin.jvm")
  pluginManager.apply("org.jetbrains.dokka")
  pluginManager.apply("com.vanniktech.maven.publish")

  val libs = catalog("libs")
  val jdkRelease = libs.version("jdkRelease")

  addDependencies("compileOnly", listOf(DependencyNotation.SymbolValue("kotlin.stdlib")))

  extensions.configure(KotlinJvmProjectExtension::class.java) { kotlin ->
    kotlin.explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    kotlin.abiValidation()
  }

  tasks.withType(JavaCompile::class.java).configureEach { task ->
    task.options.release.set(jdkRelease.toInt())
  }

  tasks.withType(KotlinCompile::class.java) { task ->
    task.compilerOptions { options ->
      // https://docs.gradle.org/current/userguide/compatibility.html#kotlin
      options.languageVersion.set(KotlinVersion.KOTLIN_2_2)
      options.jvmTarget.set(JvmTarget.fromTarget(jdkRelease))
      options.freeCompilerArgs.add("-Xjdk-release=$jdkRelease")
    }
  }

  tasks.withType(Test::class.java).configureEach { task ->
    task.useJUnitPlatform()
  }
}

/**
 * Adds each notation to the named configuration. A Symbol is an alias of the `libs` version
 * catalog, a String is a module coordinate, and the record form is a project of this build.
 */
internal fun Project.addDependencies(configurationName: String, notations: List<DependencyNotation>) {
  for (notation in notations) {
    val dependency: Any = when (notation) {
      is DependencyNotation.StringValue -> {
        notation.value
      }

      is DependencyNotation.SymbolValue -> {
        val alias = notation.value
        catalog("libs").findLibrary(alias).orElseThrow {
          GradleException("Dependency :$alias in $path is not a library alias of gradle/libs.versions.toml")
        }
      }

      is ProjectDependency -> {
        dependencies.project(mapOf("path" to notation.project().get()))
      }
    }
    dependencies.add(configurationName, dependency)
  }
}
