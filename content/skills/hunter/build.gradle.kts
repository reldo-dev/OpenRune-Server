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
    // For `clueScrollTransformObj`, which every clue-carrying drop table in the repo applies.
    implementation(projects.content.drops)

    // `invAdd`/`invDel` route through a lateinit that `InvTransactionsScript` fills in at boot,
    // so the collect-path tests start that script themselves.
    testImplementation(projects.api.invStorage)

    // The default quest policy is `assume-completed`; the test that exercises the untransformed
    // clue branch flips the `content/quest` policy singleton back and forth.
    testImplementation(projects.content.quest)

    // Test-only. `PitfallSitesTest` proves every authored pitfall coordinate against the packed map
    // the way `GameMapDecoder` reads it, and `MapLocListDefinition.spawns` is a fastutil
    // `LongArrayList`. `or-cache` depends on fastutil with `implementation`, so the type is not on
    // this module's compile classpath without saying so.
    testImplementation(libs.fastutil)
}
