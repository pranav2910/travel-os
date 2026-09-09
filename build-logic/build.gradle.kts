import org.gradle.plugin.use.PluginDependency

plugins {
    `kotlin-dsl`
}

// Plugins applied by the convention scripts must be on this build's classpath.
dependencies {
    implementation(plugin(libs.plugins.spring.boot))
    implementation(plugin(libs.plugins.protobuf))
    implementation(plugin(libs.plugins.spotless))
}

fun plugin(dependency: Provider<PluginDependency>): Provider<String> =
    dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
