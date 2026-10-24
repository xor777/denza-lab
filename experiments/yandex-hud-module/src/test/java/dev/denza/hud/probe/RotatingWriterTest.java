package dev.denza.hud.probe;

import java.io.File;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Arrays;

/** Run after the Android module javac step: exercises the actual writer without Android calls. */
public final class RotatingWriterTest {
    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("denza-dry-run-utf8-").toFile();
        File file=new File(dir,"trace.jsonl"),old=new File(file.getPath()+".1");
        try {
            Class<?> type=Class.forName("dev.denza.hud.probe.AdasBridge$RotatingWriter");
            Constructor<?> constructor=type.getDeclaredConstructor(File.class);constructor.setAccessible(true);
            try(Writer writer=(Writer)constructor.newInstance(file)) {
                char[] row=new char[4096];Arrays.fill(row,'я');
                for(int i=0;i<1024;i++)writer.write(row);
                writer.flush();
                if(old.length()!=(8L<<20)||file.length()!=0)
                    throw new AssertionError("Rotate at physical UTF-8 byte boundary, not 4 MiB Java chars");
                writer.write("ok");writer.flush();
                if(file.length()!=2||old.length()!=(8L<<20))throw new AssertionError("Fresh file continues after rotation");
            }
            System.out.println("RotatingWriterTest: 2 checks passed");
        } finally {
            Files.deleteIfExists(file.toPath());Files.deleteIfExists(old.toPath());Files.deleteIfExists(dir.toPath());
        }
    }
}
