package it.ingdati.lucene;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Piccola utilità: stampa sempre in UTF-8, così le lettere accentate non diventano "??". */
final class Console {
    private Console() { }

    static void usaUtf8() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }
}
