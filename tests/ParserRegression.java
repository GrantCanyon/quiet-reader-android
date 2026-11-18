package ru.grant.reader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

/** Host-JVM checks for FB2 whitespace and old reading anchors, not Android rendering. */
public final class ParserRegression {
    private static int checks;

    public static void main(String[] args) throws Exception {
        File fixture = File.createTempFile("reader-format-", ".fb2");
        try {
            String body = "\n <section>\n  <title>\n   <p>Chapter</p>\n  </title>\n"
                    + "  <p>First   paragraph <emphasis>with</emphasis> a\nwrapped line.</p>\n"
                    + "  <poem>\n   <stanza>\n    <v>First verse</v>\n    <v>Second verse</v>\n   </stanza>\n"
                    + "   <stanza>\n    <v>Third verse</v>\n   </stanza>\n  </poem>\n"
                    + "  <empty-line/><empty-line/><p>Last\u00a0paragraph.</p>\n </section>\n";
            String expected = "Chapter\n\nFirst paragraph with a wrapped line.\n\nFirst verse\nSecond verse\n\nThird verse\n\nLast paragraph.";
            Files.writeString(fixture.toPath(), "<FictionBook><body>" + body + "</body></FictionBook>", StandardCharsets.UTF_8);
            String actual = BookTextParser.parse(fixture, "FB2").text;
            check(expected.equals(actual), "semantic paragraph, verse and stanza spacing");
            Files.writeString(fixture.toPath(), "<FictionBook><body>" + body.replaceAll(">\\s+<", "><") + "</body></FictionBook>");
            check(actual.equals(BookTextParser.parse(fixture, "FB2").text), "XML indentation cannot affect the printed text");
            String collection = "<section><title><p>Author</p><p>First book</p></title><section><title><p>Part one</p></title><section><title><p>Chapter 1</p></title><p>First chapter text.</p></section></section></section>"
                    + "<section><title><p>Second book</p></title><section><title><p>Chapter 1</p></title><p>Second chapter text.</p></section></section>";
            Files.writeString(fixture.toPath(), "<FictionBook><body>" + collection + "</body><body name='notes'><title><p>Notes</p></title><p>Note.</p></body></FictionBook>");
            BookTextParser.Parsed navigation = BookTextParser.parse(fixture, "FB2");
            check(navigation.chapters.size() == 6, "collection books, nested parts, repeated chapters and second body indexed");
            check(navigation.chapters.get(0).title.equals("Author · First book"), "multi-paragraph book title label");
            check(navigation.chapters.get(0).level == 0 && navigation.chapters.get(2).level == 2 && navigation.chapters.get(3).level == 0, "collection hierarchy");
            check(navigation.text.substring(navigation.chapters.get(2).offset).startsWith("Chapter 1"), "first chapter exact offset");
            check(navigation.text.substring(navigation.chapters.get(4).offset).startsWith("Chapter 1"), "repeated title points to its own chapter");
            check(navigation.text.substring(navigation.chapters.get(5).offset).startsWith("Notes"), "second body exact offset after whitespace normalization");
            String before = "Chapter\n\n\n   First paragraph with a wrapped line.\n\n\n First verse\n\n\nSecond verse\n\nThird verse\n\n Last paragraph.";
            int anchor = before.indexOf("Second verse");
            int repaired = ReadingPosition.afterWhitespaceRepair(before, actual, anchor);
            check(actual.substring(repaired).startsWith("Second verse"), "saved position stays at the same verse");
            check(ReadingPosition.afterWhitespaceRepair(before, actual, 0) == 0, "start remains at start");
            check(ReadingPosition.afterWhitespaceRepair("", "", 99) == 0, "empty text safe");
            for (int offset = 0; offset < before.length(); offset++) {
                int at = ReadingPosition.afterWhitespaceRepair(before, actual, offset);
                check(at == ReadingPosition.afterWhitespaceRepair(new java.io.StringReader(before), actual, offset), "streamed legacy anchor matches the string implementation");
                check(at >= 0 && at < actual.length(), "anchor range");
                if (!Character.isWhitespace(before.charAt(offset))) {
                    check(before.charAt(offset) == actual.charAt(at), "every printed character survives anchor repair");
                }
            }
            if (args.length > 0) {
                BookTextParser.Parsed suppliedBook = BookTextParser.parse(new File(args[0]), "FB2");
                String supplied = suppliedBook.text;
                check(!Pattern.compile("\\n{3,}").matcher(supplied).find(), "supplied FB2 has no excessive blank lines");
                int previous = -1;
                for (BookRepository.Chapter chapter : suppliedBook.chapters) {
                    check(chapter.offset >= previous && chapter.offset < supplied.length(), "supplied TOC offsets ordered and in range");
                    String firstPart = chapter.title.split(" · ", 2)[0];
                    check(supplied.startsWith(firstPart, chapter.offset), "each supplied TOC entry points to its own printed heading");
                    previous = chapter.offset;
                }
                System.out.println("Supplied FB2: " + supplied.lines().count() + " lines, " + suppliedBook.chapters.size() + " TOC entries; all offsets checked");
            }
            Files.writeString(fixture.toPath(), "<!DOCTYPE FictionBook [<!ENTITY data SYSTEM 'file:///no-read'>]><FictionBook><body><p>&data;</p></body></FictionBook>");
            boolean rejected = false;
            try { BookTextParser.parse(fixture, "FB2"); } catch (Exception expectedFailure) { rejected = true; }
            check(rejected, "DTD/external entities stay disabled");
            System.out.println("PASS: " + checks + " checks");
        } finally { Files.deleteIfExists(fixture.toPath()); }
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
