package com.vanniktech.buildlogic

import org.gradle.api.Project
import org.gradle.api.xdcl.Reaction
import org.gradle.api.xdcl.ReactionScope

public class KotlinJvmLibraryReaction : Reaction<KotlinJvmLibrary, Project> {
  override fun on(data: KotlinJvmLibrary, context: Project, scope: ReactionScope) {
    context.applySharedConventions()

    val dependencies = data.dependencies().orNull ?: return
    context.addDependencies("api", dependencies.api().getOrElse(emptyList()))
    context.addDependencies("implementation", dependencies.implementation().getOrElse(emptyList()))
    context.addDependencies("compileOnly", dependencies.compileOnly().getOrElse(emptyList()))
    context.addDependencies("runtimeOnly", dependencies.runtimeOnly().getOrElse(emptyList()))
    context.addDependencies("testImplementation", dependencies.testImplementation().getOrElse(emptyList()))
    context.addDependencies("testRuntimeOnly", dependencies.testRuntimeOnly().getOrElse(emptyList()))
  }
}
