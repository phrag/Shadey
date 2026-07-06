# Shadey R8 rules. Release builds are minified (see app/build.gradle.kts); these rules
# cover the two places the app depends on runtime reflection that R8 can't see:
#
# 1. kotlinx.serialization — saved spots are (de)serialized through generated
#    serializers looked up via each @Serializable class's Companion. These are the
#    library's documented rules for R8 full mode (the AGP 8 default), which the
#    consumer rules shipped in the artifact don't fully cover.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# 2. MapLibre's GeoJSON module — tile-harvested building features
#    (queryRenderedFeatures → featuresToBuildings) are parsed by Gson type adapters
#    that reflect over these model classes. The MapLibre SDK ships consumer rules
#    for its own (JNI-facing) classes, but not for geojson model reflection.
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.geojson.**
