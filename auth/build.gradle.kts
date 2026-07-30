// :auth - authentication, JWT, sessions, password reset, user-company-role.
dependencies {
    implementation(project(":core"))
    implementation(project(":audit-trail"))
    // JWT (Finding #18 — version issue du catalog gradle/libs.versions.toml)
    implementation(libs.nimbus.jose.jwt)
}
