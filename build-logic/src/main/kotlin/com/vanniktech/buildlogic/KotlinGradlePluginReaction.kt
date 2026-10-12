package com.vanniktech.buildlogic

import com.android.build.api.dsl.Lint as AndroidLint
import com.github.gmazzo.buildconfig.BuildConfigExtension
import com.gradle.develocity.agent.gradle.test.DevelocityTestConfiguration
import org.gradle.api.Project
import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.gradle.api.plugins.JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugin.devel.tasks.ValidatePlugins
import org.jetbrains.dokka.gradle.DokkaExtension

public class KotlinGradlePluginReaction : Reaction<KotlinGradlePlugin, Project> {
  override fun on(data: KotlinGradlePlugin, context: Project, scope: ReactionScope) {
    context.applySharedConventions()
    context.pluginManager.apply("java-gradle-plugin")
    context.pluginManager.apply("com.github.gmazzo.buildconfig")
    context.pluginManager.apply("com.android.lint")

    context.configureDokka(data)
    context.registerPlugins(data)
    context.createIntegrationTestSourceSet()
    context.configureApiElements()
    context.configureBuildConfig(data.buildConfig())
    data.lint().orNull?.let { context.configureLint(it) }
    context.linkDependencies(data)

    context.tasks.named("validatePlugins", ValidatePlugins::class.java).configure { task ->
      // TODO: https://github.com/gradle/gradle/issues/22600
      task.enableStricterValidation.set(true)
    }

    context.registerIntegrationTest(data)

    context.tasks.named("test", Test::class.java).configure { task ->
      // The generated build config fails the test task.
      task.failOnNoDiscoveredTests.set(false)
    }
  }
}

private fun Project.configureDokka(data: KotlinGradlePlugin) {
  val dokka = extensions.getByType(DokkaExtension::class.java)
  val html = dokka.dokkaPublications.named("html")
  data.dokkaHtmlOutputDirectory().orNull?.let { directory ->
    html.configure { it.outputDirectory.set(rootDir.resolve(directory)) }
  }
  val extraCleanPaths = data.extraCleanPaths().getOrElse(emptyList())
  tasks.named("clean", org.gradle.api.tasks.Delete::class.java).configure { task ->
    task.delete(html.map { it.outputDirectory })
    extraCleanPaths.forEach { task.delete(rootDir.resolve(it)) }
  }
}

private fun Project.registerPlugins(data: KotlinGradlePlugin) {
  val gradlePlugin = extensions.getByType(GradlePluginDevelopmentExtension::class.java)
  data.plugins().get().forEach { (name, declaration) ->
    gradlePlugin.plugins.create(name) { plugin ->
      plugin.id = declaration.id().get()
      plugin.implementationClass = declaration.implementationClass().get()
      plugin.displayName = declaration.displayName().get()
      plugin.description = declaration.description().get()
    }
  }
}

private fun Project.createIntegrationTestSourceSet() {
  val sourceSets = extensions.getByType(SourceSetContainer::class.java)
  sourceSets.create(INTEGRATION_TEST) { sourceSet ->
    sourceSet.compileClasspath += sourceSets.getByName("main").output
    sourceSet.compileClasspath += configurations.getByName("testRuntimeClasspath")
    sourceSet.runtimeClasspath += sourceSet.output + sourceSet.compileClasspath
  }
  configurations.getByName("integrationTestImplementation") {
    it.extendsFrom(configurations.getByName("testImplementation"))
  }
}

private fun Project.configureApiElements() {
  val minGradle = catalog("libs").version("minGradle")
  configurations.named(API_ELEMENTS_CONFIGURATION_NAME) { configuration ->
    configuration.attributes.attribute(
      // TODO: https://github.com/gradle/gradle/issues/24608
      GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
      objects.named(GradlePluginApiVersion::class.java, minGradle),
    )
  }
}

private fun Project.configureBuildConfig(data: BuildConfig) {
  val libs = catalog("libs")
  val string = String::class.java
  val buildConfig = extensions.getByType(BuildConfigExtension::class.java)
  buildConfig.packageName(data.packageName().get())
  buildConfig.generateAtSync.set(true)

  buildConfig.sourceSets.configureEach { sourceSet ->
    sourceSet.buildConfigField(string, "VERSION_NAME", providers.gradleProperty("VERSION_NAME"))
    sourceSet.buildConfigField(string, "ANDROID_GRADLE_MIN", libs.version("minAgp"))
    sourceSet.buildConfigField(string, "KOTLIN_MIN", libs.version("minKgp"))
  }

  buildConfig.sourceSets.named("main") { sourceSet ->
    data.fields().getOrElse(emptyMap()).forEach { (name, value) ->
      sourceSet.buildConfigField(string, name, value)
    }
  }

  buildConfig.sourceSets.named(INTEGRATION_TEST) { sourceSet ->
    sourceSet.buildConfigField(string, "TEST_CONFIG_METHOD", providers.gradleProperty("testConfigMethod").orElse(""))
    sourceSet.buildConfigField("Boolean", "QUICK_TEST", providers.gradleProperty("quickTest").orElse("false"))

    sourceSet.buildConfigField(string, "GRADLE_MIN", libs.version("minGradle"))
    sourceSet.buildConfigField(string, "DOKKA_STABLE", libs.version("dokka"))
    val channels = mapOf("ALPHA" to catalog("alpha"), "BETA" to catalog("beta"), "RC" to catalog("rc"), "STABLE" to libs)
    channels.forEach { (channel, versions) ->
      sourceSet.buildConfigField(string, "GRADLE_$channel", versions.version("gradle"))
      sourceSet.buildConfigField(string, "ANDROID_GRADLE_$channel", versions.version("android-gradle"))
      sourceSet.buildConfigField(string, "KOTLIN_$channel", versions.version("kotlin"))
      sourceSet.buildConfigField(string, "GRADLE_PUBLISH_$channel", versions.version("gradle-plugin-publish"))
    }
  }
}

private fun Project.configureLint(data: Lint) {
  extensions.configure(AndroidLint::class.java) { lint ->
    data.baseline().orNull?.let { lint.baseline = file(it) }
    lint.ignoreTestSources = data.ignoreTestSources().get()
    lint.warningsAsErrors = data.warningsAsErrors().get()
    lint.disable += data.disable().getOrElse(emptyList())
  }
}

private fun Project.linkDependencies(data: KotlinGradlePlugin) {
  val dependencies = data.dependencies().orNull ?: return
  addDependencies("api", dependencies.api().getOrElse(emptyList()))
  addDependencies("implementation", dependencies.implementation().getOrElse(emptyList()))
  addDependencies("compileOnly", dependencies.compileOnly().getOrElse(emptyList()))
  addDependencies("runtimeOnly", dependencies.runtimeOnly().getOrElse(emptyList()))
  addDependencies("testImplementation", dependencies.testImplementation().getOrElse(emptyList()))
  addDependencies("testRuntimeOnly", dependencies.testRuntimeOnly().getOrElse(emptyList()))
  addDependencies("integrationTestImplementation", dependencies.integrationTestImplementation().getOrElse(emptyList()))
  addDependencies("lintChecks", dependencies.lintChecks().getOrElse(emptyList()))
}

private fun Project.registerIntegrationTest(data: KotlinGradlePlugin) {
  val sourceSet = extensions.getByType(SourceSetContainer::class.java).getByName(INTEGRATION_TEST)
  val publishedProjects = data.integrationTestPublishedProjects().getOrElse(emptyList())
  val testJavaVersion = providers.gradleProperty("testJavaVersion").orNull
  val isCi = providers.environmentVariable("CI").isPresent

  val integrationTest = tasks.register(INTEGRATION_TEST, Test::class.java) { task ->
    task.dependsOn("publishToMavenLocal")
    publishedProjects.forEach { task.dependsOn("$it:publishToMavenLocal") }
    task.mustRunAfter("test")

    task.description = "Runs the integration tests."
    task.group = "verification"

    task.testClassesDirs = sourceSet.output.classesDirs
    task.classpath = sourceSet.runtimeClasspath

    if (testJavaVersion != null && testJavaVersion != "latest") {
      task.javaLauncher.set(
        extensions.getByType(JavaToolchainService::class.java).launcherFor {
          it.languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
        },
      )
    }

    task.testLogging.showStandardStreams = true
    task.maxHeapSize = "2g"
    task.maxParallelForks = if (isCi) {
      Runtime.getRuntime().availableProcessors() / 2
    } else {
      Runtime.getRuntime().availableProcessors()
    }
    task.jvmArgs(
      "--add-opens",
      "java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.net=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.util=ALL-UNNAMED",
    )

    if (isCi) {
      task.extensions.findByType(DevelocityTestConfiguration::class.java)?.testRetry { retry ->
        retry.maxRetries.set(2)
        retry.maxFailures.set(10)
      }
    }
  }

  tasks.named("check").configure { it.dependsOn(integrationTest) }
}

private const val INTEGRATION_TEST = "integrationTest"
