# Release builds keep minification off (see app/build.gradle.kts): this is a
# privately deployed monitoring app where readable field reports matter more
# than APK size. The file is kept so enabling R8 later has a single place to go.
