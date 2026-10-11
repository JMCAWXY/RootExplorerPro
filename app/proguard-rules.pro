# Root Explorer Pro — ProGuard rules.
# P0 does not enable minification; these rules are the baseline kept for release builds.

# Keep Kotlin metadata used by reflection-based tooling.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.metadata.**
