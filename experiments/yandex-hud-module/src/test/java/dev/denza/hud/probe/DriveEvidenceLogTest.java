package dev.denza.hud.probe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host check: UTF8 byte accounting, whole records, retention, and over-budget rejection. */
public final class DriveEvidenceLogTest {
    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("drive-evidence-log").toFile();
        File file=new File(dir,"progress.jsonl"),old=new File(dir,"progress.jsonl.1");
        try {
            DriveEvidenceLog log=new DriveEvidenceLog(file,10);
            log.append("ули"); //6UTF8 bytes plus newline
            log.append("abc"); //must rotate before writing4bytes
            if(file.length()!=4||old.length()!=7)throw new AssertionError("Exact UTF8 rotation");
            log.append("12345");
            if(file.length()!=10)throw new AssertionError("Exact limit retained");
            log.append("x");
            if(file.length()!=2||old.length()!=10)throw new AssertionError("Only predecessor retained");
            String saved=new String(Files.readAllBytes(old.toPath()),StandardCharsets.UTF_8);
            if(!saved.equals("abc\n12345\n"))throw new AssertionError("Whole records preserved");
            try{log.append("1234567890");throw new AssertionError("Oversize accepted");}
            catch(java.io.IOException expected){}
            if(file.length()!=2||old.length()!=10)throw new AssertionError("Oversize changed logs");
            System.out.println("DriveEvidenceLogTest:5checks PASS");
        }finally{file.delete();old.delete();dir.delete();}
    }
}
