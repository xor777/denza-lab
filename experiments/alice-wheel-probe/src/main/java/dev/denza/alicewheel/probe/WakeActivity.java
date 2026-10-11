package dev.denza.alicewheel.probe;

import android.app.Activity;
import android.os.Bundle;

/** Bring the UID alive before binding, without leaving a foreground window. */
public final class WakeActivity extends Activity {
    @Override protected void onCreate(Bundle state) { super.onCreate(state); finish(); }
}
