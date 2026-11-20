# Keep Markwon, CommonMark, and JLatexMath reflection/resource hooks intact under R8
-keep class io.noties.markwon.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class org.commonmark.** { *; }

-dontwarn org.scilab.forge.jlatexmath.**
-dontwarn ru.noties.jlatexmath.**

# Optional Markwon image plugin backends not bundled by MarkNote (offline bitmap-only)
-dontwarn com.caverock.androidsvg.**
-dontwarn okhttp3.**
-dontwarn pl.droidsonroids.gif.**
