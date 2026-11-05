# Media3, Room and Compose ship consumer shrinker rules. Entry-point services
# remain named for platform discovery and release crash diagnostics.
-keepnames class app.carsync.music.PlaybackService
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
