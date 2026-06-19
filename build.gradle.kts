plugins {
    alias(libs.plugins.fabric.loom)
}

loom {
    runConfigs {
        named("client") {
            programArgs("--username", "noc")
        }
    }
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        name = "seedfinding-maven"
        url = uri("https://maven.seedfinding.com")
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)
    implementation("com.seedfinding:mc_math:1.171.0")     { isTransitive = false }
    implementation("com.seedfinding:mc_seed:1.171.1")     { isTransitive = false }
    implementation("com.seedfinding:mc_core:1.210.0")     { isTransitive = false }
    implementation("com.seedfinding:mc_noise:1.171.1")    { isTransitive = false }
    implementation("com.seedfinding:mc_biome:1.171.1")    { isTransitive = false }
    implementation("com.seedfinding:mc_terrain:1.171.1")  { isTransitive = false }
    implementation("com.seedfinding:mc_feature:1.171.11") { isTransitive = false }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
