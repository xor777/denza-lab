package dev.denza.hudsurfaces.probe;

/**
 * One of the probe's two SOME/IP sessions. The stock service names a session by the bind
 * intent's type and nothing else (`SomeIpServerService.onBind` passes `intent.getType()` to the
 * binder as its uuid), so one app holding two types holds two sessions, exactly as two apps would.
 * A uses the package name, as every app of ours does; B the package name with a suffix.
 */
enum SessionId {
    A(""),
    B(".b");

    private final String suffix;

    SessionId(String suffix) {
        this.suffix = suffix;
    }

    /** The bind intent's type for this session. */
    String type(String packageName) {
        return packageName + suffix;
    }
}
