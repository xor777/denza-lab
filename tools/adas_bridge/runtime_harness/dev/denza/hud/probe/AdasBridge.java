package dev.denza.hud.probe;
import java.io.*;
/** Test writer only. Production rotating writer has its separate physical-byte test. */
final class AdasBridge {
    static final String TAG="runtime-harness";
    static final class RotatingWriter extends FileWriter {RotatingWriter(File f)throws IOException{super(f,true);}}
}
