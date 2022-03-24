gradlePlugin {
    plugins {
        testSourceSets(java.sourceSets.integrationTest.get())
        create("co.elastic.cli.manifest-tool") {
            id = "co.elastic.cli.manifest-tool"
            implementationClass = "co.elastic.gradle.cli.manifest.ManifestToolPlugin"
        }
    }
}

dependencies {
    implementation(project(":plugins:cli:base"))
    implementation(project(":libs:utils"))
    // This is really only needed for the test runtime, but if declared like that it's not found by buildkit
    implementation(project(":plugins:vault"))
}