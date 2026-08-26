plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.dropTable)
    implementation(projects.api.dropTablePlugin)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.registry)
    implementation(projects.api.utils.utilsSkills)
    implementation(projects.content.skills.utils)
    // For `clueScrollTransformObj`, which turns a rolled clue into a scroll box once X Marks the
    // Spot is done. Every drop table in the repo that carries a clue applies it; implings would be
    // the odd one out without it.
    implementation(projects.content.drops)

    // Test-only. `invAdd`/`invDel` route through a module-internal `lateinit` in `api:invtx` that
    // `InvTransactionsScript` fills in at boot, so the collect-path tests have to start that script
    // themselves and need its `PlayerItemStorage` argument on the classpath to do it.
    testImplementation(projects.api.invStorage)

    // Test-only. The default quest policy is `assume-completed`, so `clueScrollTransformObj` always
    // takes its X-Marks-the-Spot branch and the untransformed one - a real server running
    // `respect-progress` - would never be exercised. `content/quest` owns the policy singleton and
    // is already a transitive dependency of `content/drops`; this only makes it visible to the test
    // that flips it back and forth.
    testImplementation(projects.content.quest)

    // Test-only. `PitfallSitesTest` proves every authored pitfall coordinate against the packed map
    // the way `GameMapDecoder` reads it, and `MapLocListDefinition.spawns` is a fastutil
    // `LongArrayList`. `or-cache` depends on fastutil with `implementation`, so the type is not on
    // this module's compile classpath without saying so.
    testImplementation(libs.fastutil)
}

/**
 * A second test suite that boots a real server, separate from `test` on purpose.
 *
 * Booting the game inside the unit-test JVM contaminates it: `ServerCacheManager` and the registries
 * are singletons, so a booted server leaves state behind that the hand-built worlds in `test` then
 * fail against, and a second boot in the same JVM dies on `Key already registered`. The deleted
 * `integration-test-suite` convention existed for exactly this reason - this is the same shape,
 * inlined here rather than reviving `api/testing`, which the spike showed is not needed to get a
 * real game loop under test.
 *
 * `workingDir = rootDir` because the server resolves `.data/...` relative to the working directory,
 * which Gradle otherwise sets to the module directory.
 */
@Suppress("UnstableApiUsage")
testing.suites {
    val integration by
        registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(projects.server.app)
                implementation(projects.api.gameProcess)
                implementation(projects.api.player)
                implementation(projects.api.registry)
                implementation(projects.api.repo)
                implementation(projects.engine.events)
                implementation(projects.engine.game)
                implementation(projects.engine.map)
                implementation(projects.engine.objtx)
                implementation(projects.engine.routefinder)
                implementation(libs.clikt)
                implementation(libs.guice)
                implementation(projects.orCache)
            }
            targets.all {
                testTask.configure {
                    workingDir = rootDir
                    // One JVM, one boot: see the suite doc.
                    systemProperty("junit.jupiter.execution.parallel.enabled", false)
                }
            }
        }
}
