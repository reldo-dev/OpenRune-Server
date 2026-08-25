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
}
