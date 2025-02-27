gradlePlugin {
    plugins {
        testSourceSets(java.sourceSets.integrationTest.get())
        create("co.elastic.elastic-conventions") {
            id = "co.elastic.elastic-conventions"
            implementationClass = "co.elastic.gradle.elastic_conventions.ElasticConventionsPlugin"
            displayName = "Elastic Conventions Plugin"
            description = "Implement internal elastic conventions"
        }
    }
}


repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("com.gradle.enterprise:com.gradle.enterprise.gradle.plugin:3.10.1")
    implementation("com.gradle:common-custom-user-data-gradle-plugin:1.7.2")
    implementation(project(":libs:utils"))
    implementation(project(":plugins:lifecycle"))
    implementation(project(":plugins:vault"))
    implementation(project(":plugins:cli:base"))
    implementation(project(":plugins:cli:snyk"))
    implementation(project(":plugins:docker:base-image"))

    integrationTestImplementation(project(":plugins:vault"))
    // for integration testing only
    implementation(project(":plugins:cli:jfrog"))
    integrationTestImplementation(project(":plugins:cli:jfrog"))
    implementation(project(":plugins:cli:manifest-tool"))
    integrationTestImplementation(project(":plugins:cli:manifest-tool"))
    implementation(project(":plugins:docker:component-image"))
}