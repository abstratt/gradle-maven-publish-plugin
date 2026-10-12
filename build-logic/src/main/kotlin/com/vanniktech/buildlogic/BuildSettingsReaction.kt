package com.vanniktech.buildlogic

import com.gradle.develocity.agent.gradle.DevelocityConfiguration
import org.gradle.api.initialization.Settings
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope

public class BuildSettingsReaction : Reaction<BuildSettings, Settings> {
  override fun on(data: BuildSettings, context: Settings, scope: ReactionScope) {
    context.pluginManager.apply("org.gradle.toolchains.foojay-resolver-convention")

    data.versionCatalogs().getOrElse(emptyMap()).forEach { (name, file) ->
      context.dependencyResolutionManagement.versionCatalogs.create(name) { catalog ->
        catalog.from(context.layout.rootDirectory.files(file))
      }
    }

    data.featurePreviews().getOrElse(emptyList()).forEach { context.enableFeaturePreview(it) }

    data.develocity().orNull?.let { develocity ->
      context.pluginManager.apply("com.gradle.develocity")
      val publishOnlyOnCi = develocity.publishOnlyOnCi().get()
      // TODO: workaround for https://github.com/gradle/gradle/issues/22879.
      val isCi = context.providers.environmentVariable("CI").isPresent
      context.extensions.getByType(DevelocityConfiguration::class.java).buildScan { buildScan ->
        buildScan.termsOfUseUrl.set(develocity.termsOfUseUrl().get())
        buildScan.termsOfUseAgree.set(develocity.termsOfUseAgree().get())
        if (publishOnlyOnCi) {
          buildScan.publishing.onlyIf { isCi }
        }
      }
    }
  }
}
