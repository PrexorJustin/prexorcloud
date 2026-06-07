// Shared base for protocol-tap plugin variants (folia / paper v1_20 / paper v1_21).
//
// Why: the three sibling plugin subprojects only differ in (a) which chat-event
// type they subscribe to and (b) their plugin metadata. Everything else —
// counter, periodic flush, HTTP send — lives here so a change to the
// flush-or-error path touches one file, not three.
plugins {
    id("prexorcloud.java21-compat")
    `java-library`
}

dependencies {
    api(project(":cloud-api"))
    api(libs.jackson.databind)
    api(platform(libs.jackson.bom))
}
