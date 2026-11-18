package ru.grant.reader;

import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Run with a small -Xmx against a local book; prints hashes rather than its text. */
public final class BookMemoryProbe {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        BookTextParser.Parsed book = BookTextParser.parse(new File(args[0]), "FB2");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Writer writer = new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)) {
            writer.write(book.text);
        }
        String textHash = HexFormat.of().formatHex(digest.digest());
        for (BookRepository.Chapter chapter : book.chapters) {
            digest.update((chapter.offset + "\t" + chapter.level + "\t" + chapter.title + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String tocHash = HexFormat.of().formatHex(digest.digest());
        if (args.length > 1 && !args[1].equals(textHash)) throw new AssertionError("Printed text changed");
        if (args.length > 2 && !args[2].equals(tocHash)) throw new AssertionError("TOC or offsets changed");
        System.out.println("textSha256=" + textHash);
        System.out.println("tocSha256=" + tocHash);
        System.out.println("chars=" + book.text.length() + ", chapters=" + book.chapters.size()
                + ", maxHeapMiB=" + Runtime.getRuntime().maxMemory() / 1048576
                + ", seconds=" + (System.nanoTime() - started) / 1000000000.0);
    }
}
