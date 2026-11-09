plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Water for true-sim: rivers and streams as a flowing-water material (flow-mapped normals, foam, " +
    "refraction, depth absorption, reflections), waterfalls' sheets and jets, spray as particles and mist as fog " +
    "volumes, placed from common-procedural-water's networks through the scene."

dependencies {
    api("com.truesim.engine:engine-scene:0.198.0") // material types, shading hooks, the scene bridge
    api("com.truesim.common:common-procedural-water:0.5.0") // the networks: channels, falls, stones, the field
    api("com.truesim.common:common-procedural-assets:0.5.0") // their meshes as generated assets, rocks
    api("com.truesim.scene:scene-effects:0.70.0") // spray as effect data
    api("com.truesim.effects:atmosphere-fog:0.4.0") // mist as fog volumes
    api("com.truesim.effects:effects-terrain-data:0.5.0") // beds and banks as a terrain modifier
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.198.0")) // the headless renderer
    testImplementation("com.truesim.effects:effects-terrain:0.5.0") // the terrain the films are over
    testImplementation("com.truesim.effects:atmosphere-render:0.4.0") // the sky and its light
    testImplementation("com.truesim.effects:effects-particles:0.4.0") // spray drawn
}

// The material records, the hook's record and the push layouts of src/main/slang, as Java; the listing
// (WaterShadingHooks) rebuilds the engine's shading with the water's hook and the libraries' it sees, the tests'
// (TestWaterShadingHooks) with those they draw with too (the atmosphere's, the particles'). An application lists
// every hook in its own, which wins.
slang {
    javaPackage = "com.truesim.effects.water.materials"
    shadingHooks = "com.truesim.effects.water.materials.WaterShadingHooks"
    particleModules = "com.truesim.effects.water.materials.WaterParticleModules" // the tests draw spray
    pushBase = "com.truesim.engine.vulkan.PushConstants"
}

// The films (TRUESIM_FILMS=true) measure the code, not its coverage probes; they read effects-terrain's cache
// (-Ptruesim.terrain.cache=<dir>) and write to -Pfilms=<dir>.
tasks.test {
    if (providers.environmentVariable("TRUESIM_FILMS").orNull == "true") {
        maxHeapSize = "12g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    } else {
        maxHeapSize = "4g"
    }
    providers.gradleProperty("films").orNull?.let { systemProperty("truesim.films", it) }
    providers.gradleProperty("truesim.terrain.cache").orNull?.let { systemProperty("truesim.terrain.cache", it) }
}
