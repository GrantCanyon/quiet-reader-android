package ru.grant.reader;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host-JVM streaming, encoding and resource-limit checks, with no Android device required. */
public final class StreamingFb2Regression {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File fixture = File.createTempFile("reader-stream-", ".fb2");
        try {
            String xml = "<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'>"
                    + "<description><title-info><book-title>  Русская\u00a0книга  </book-title>"
                    + "<author><first-name> Иван </first-name><middle-name> Иванович </middle-name>"
                    + "<last-name> Иванов </last-name></author><author><nickname> Псевдоним </nickname></author>"
                    + "</title-info></description><body><section><title><p>Раздел &amp; один</p>"
                    + "<p>Подзаголовок</p></title><p>Первый <emphasis>абзац</emphasis> и &lt;текст&gt;.</p>"
                    + "<poem><stanza><v>Первая строка</v><v>Вторая строка</v></stanza>"
                    + "<stanza><v>Третья строка</v></stanza></poem></section></body>"
                    + "<body name='notes'><title><p>Примечания</p></title><p>Конец.</p></body>"
                    + "<binary id='cover'>AAAAAA</binary></FictionBook>";
            String expected = "Раздел & один\n\nПодзаголовок\n\nПервый абзац и <текст>.\n\n"
                    + "Первая строка\nВторая строка\n\nТретья строка\n\nПримечания\n\nКонец.";
            for (String encoding : new String[]{"UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "windows-1251"}) {
                Files.write(fixture.toPath(), ("<?xml version='1.0' encoding='" + encoding + "'?>" + xml).getBytes(Charset.forName(encoding)));
                BookTextParser.Parsed book = BookTextParser.parse(fixture, "FB2");
                check(book.text.equals(expected), encoding + " printed text");
                check(book.title.equals("Русская книга"), encoding + " title metadata");
                check(book.author.equals("Иван Иванович Иванов, Псевдоним"), encoding + " all author names");
                check(book.chapters.size() == 2 && book.chapters.get(0).title.equals("Раздел & один · Подзаголовок"), encoding + " multi-part heading");
                check(book.text.startsWith("Примечания", book.chapters.get(1).offset), encoding + " second body offset");
            }

            String words = "word  \n".repeat(12000);
            write(fixture, "<FictionBook><body><p>" + words + "<emphasis> tail </emphasis> end</p></body></FictionBook>");
            check(BookTextParser.parse(fixture, "FB2").text.equals("word ".repeat(12000) + "tail end"), "paragraph whitespace across SAX callback boundaries");
            write(fixture, "<FictionBook><body><section>" + " ".repeat(20000) + "A" + " \n".repeat(20000) + "B"
                    + "</section></body></FictionBook>");
            check(BookTextParser.parse(fixture, "FB2").text.equals("A B"), "direct text whitespace across SAX callback boundaries");
            write(fixture, "<FictionBook><body><title>Alpha<![CDATA[ & beta]]><!-- comment --><p>Gamma</p></title>"
                    + "<p>one<![CDATA[  & two]]><!-- comment --> three</p>"
                    + "a<!-- comment -->   <!-- comment -->b<image>hidden</image>c"
                    + "<binary>" + "ABCD".repeat(100000) + "</binary>d</body></FictionBook>");
            BookTextParser.Parsed mixed = BookTextParser.parse(fixture, "FB2");
            check(mixed.text.equals("Alpha & beta\n\nGamma\n\none & two threeabcd"), "CDATA, comments, text-node boundaries and hidden image/binary data");
            check(mixed.chapters.get(0).title.equals("Alpha · & beta · Gamma"), "CDATA creates a separate heading part");

            String[] forbidden = {
                    "<!DOCTYPE FictionBook [<!ENTITY data 'expanded'>]><FictionBook><body><p>&data;</p></body></FictionBook>",
                    "<!DOCTYPE FictionBook SYSTEM 'https://example.invalid/book.dtd'><FictionBook><body><p>Text</p></body></FictionBook>",
                    "<!DOCTYPE FictionBook [<!ENTITY % external SYSTEM 'file:///no-read'>%external;]><FictionBook><body><p>Text</p></body></FictionBook>"
            };
            for (String forbiddenXml : forbidden) {
                write(fixture, forbiddenXml);
                rejected(fixture, "DTD/entity rejection");
                Files.write(fixture.toPath(), ("<?xml version='1.0' encoding='UTF-16'?>" + forbiddenXml).getBytes(StandardCharsets.UTF_16));
                rejected(fixture, "UTF-16 DTD/entity rejection");
            }
            write(fixture, "<FictionBook><body><p>Unclosed</body></FictionBook>");
            rejected(fixture, "malformed XML rejection");
            write(fixture, "<Other><body><p>Text</p></body></Other>");
            rejected(fixture, "wrong root rejection");
            write(fixture, "<FictionBook><body><section>".replace("<section>", "")
                    + "<section>".repeat(258) + "<p>Text</p>" + "</section>".repeat(258) + "</body></FictionBook>");
            rejected(fixture, "XML depth limit");
            write(fixture, "<FictionBook><body><p>" + "x".repeat(12 * 1024 * 1024 + 1) + "</p></body></FictionBook>");
            rejected(fixture, "printed text size limit");
            try (RandomAccessFile oversized = new RandomAccessFile(fixture, "rw")) { oversized.setLength(32L * 1024 * 1024 + 1); }
            rejected(fixture, "document byte size limit");
            System.out.println("PASS: " + checks + " streaming FB2 checks");
        } finally { Files.deleteIfExists(fixture.toPath()); }
    }

    private static void write(File file, String xml) throws Exception { Files.writeString(file.toPath(), xml, StandardCharsets.UTF_8); }

    private static void rejected(File fixture, String message) throws Exception {
        boolean failed = false;
        try { BookTextParser.parse(fixture, "FB2"); } catch (Exception expected) { failed = true; }
        check(failed, message);
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
