package dev.denza.hud.probe;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.Files;import java.util.Map;
import dev.denza.adaslink.Json;
final class RouteSnapshot {static void writeAtomic(File f,Map<String,Object> m)throws IOException{Files.write(f.toPath(),Json.write(m).getBytes(StandardCharsets.UTF_8));}}
