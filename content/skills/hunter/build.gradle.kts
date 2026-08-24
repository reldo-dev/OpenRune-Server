plugins {
    id("base-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.registry)
    implementation(projects.api.utils.utilsSkills)
    implementation(projects.content.skills.utils)

    // Test-only. `invAdd`/`invDel` route through a module-internal `lateinit` in `api:invtx` that
    // `InvTransactionsScript` fills in at boot, so the collect-path tests have to start that script
    // themselves and need its `PlayerItemStorage` argument on the classpath to do it.
    testImplementation(projects.api.invStorage)
}
