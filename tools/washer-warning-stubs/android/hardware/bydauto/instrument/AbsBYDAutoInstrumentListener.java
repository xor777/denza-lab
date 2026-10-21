package android.hardware.bydauto.instrument;

/** Compile-only ABI stub. Never include this class in the probe dex. */
public abstract class AbsBYDAutoInstrumentListener {
    public void onTextInfoChanged(int value) { }
    public void onDoorStateChanged(int area, int state) { }
    public void onError(int code, String message) { }
}
