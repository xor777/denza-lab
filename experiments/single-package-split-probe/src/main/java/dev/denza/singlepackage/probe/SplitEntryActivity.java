package dev.denza.singlepackage.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/** Hidden target of the toggle-controlled launcher alias. */
public final class SplitEntryActivity extends Activity {
    private static final String TAG = "SinglePackageProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "ENTRY_OPENED taskId=" + getTaskId()
                + " component=" + getComponentName().flattenToShortString());
        Toast.makeText(this, "Split entry probe opened", Toast.LENGTH_SHORT).show();
        finish();
    }
}
