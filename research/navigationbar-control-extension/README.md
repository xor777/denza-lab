# SystemUI navigation-bar control extension

## Result

An ordinary Android `MAIN`/`LAUNCHER` activity is discovered by the BYD launcher and
offered only on the **Apps** page of the bottom navigation-bar editor. It does not appear
on the **Control** page.

The tested firmware builds the Control page from hard-coded item types and implementations
inside the privileged `com.android.systemui` package. No public intent, provider, widget,
tile, service, or manifest contract for registering an additional Control item was found.
Adding a custom Control item therefore requires a SystemUI modification or runtime hook;
a normal `/data/app` APK is not a suitable path.

The standalone live probe used to verify app discovery was removed after the result and
uninstalled from the head unit. Its useful split-control icon is retained here as
`ic_split_control.xml` for a future implementation with an appropriate integration path.
