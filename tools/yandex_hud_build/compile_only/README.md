# Compile-only SDK signatures

The nested `OfflineCacheManager.PathGetterListener` interface is transcribed from
Navigator 29.8.1 `classes11.dex`. Compile these signatures to a separate output
directory and use that directory only as a javac classpath. Exclude all stub
classes from the module jar and dex: the running Navigator provides the real SDK
interface through its classloader.

`YandexOfflineCachePath` checks that its typed interface and the reflected SDK
interface are the same `Class` before requesting the path. Runtime listener/JNI
acceptance remains unverified; source checks do not establish it.
