package ru.grant.reader;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs only in a debug APK, against its own disposable cache directory. */
public final class ReaderDataSelfTest extends Instrumentation {
    private int assertions;
    private File sandbox;
    private Context isolated;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        new Thread(() -> {
            Bundle result = new Bundle();
            try {
                runChecks();
                result.putBoolean("success", true);
                result.putInt("assertions", assertions);
                result.putString("details", "TXT/encoding, persistence/progress/delete, FB2/ZIP, EPUB spine, HTML and malformed/hostile inputs passed");
                finish(0, result);
            } catch (Throwable error) {
                result.putBoolean("success", false);
                result.putString("error", android.util.Log.getStackTraceString(error));
                finish(1, result);
            }
        }, "reader-data-selftest").start();
    }

    private void runChecks() throws Exception {
        Context target = getTargetContext();
        sandbox = new File(target.getCacheDir(), "reader-data-selftest-" + System.nanoTime());
        File privateFiles = new File(sandbox, "files");
        File privateCache = new File(sandbox, "cache");
        check(privateFiles.mkdirs(), "create isolated files");
        check(privateCache.mkdirs(), "create isolated cache");
        isolated = new ContextWrapper(target) {
            @Override public File getFilesDir() { return privateFiles; }
            @Override public File getCacheDir() { return privateCache; }
            @Override public Context getApplicationContext() { return this; }
        };
        BookRepository repository = new BookRepository(isolated);
        check(repository.list().isEmpty(), "fresh shelf");

        File original = textFile("Рассказ.txt", "Первая строка\n\nВторая строка: ёжик.", StandardCharsets.UTF_8);
        BookRepository.Book text = repository.importBook(Uri.fromFile(original));
        check(text.format.equals("TXT"), "TXT type");
        check(text.title.equals("Рассказ"), "display name/title");
        check(repository.readText(text).contains("Вторая строка: ёжик."), "Cyrillic UTF8 text");
        check(repository.fileFor(text).isFile(), "private copy exists");
        check(!repository.fileFor(text).getCanonicalPath().equals(original.getCanonicalPath()), "copy independent of original");
        repository.updateProgress(text, 13);
        BookRepository reopened = new BookRepository(isolated);
        BookRepository.Book restored = reopened.list().get(0);
        check(restored.id.equals(text.id), "shelf restored");
        check(restored.position == 13, "position persisted");
        check(reopened.readText(restored).equals(repository.readText(text)), "text roundtrip");
        reopened.delete(restored);
        check(reopened.list().isEmpty(), "delete from shelf");
        check(original.isFile(), "delete preserves original");
        check(!reopened.fileFor(restored).exists(), "delete private copy");
        check(new BookRepository(isolated).list().isEmpty(), "delete persisted");
        repository = reopened;

        BookRepository.Book cp1251 = repository.importBook(Uri.fromFile(textFile("Кодировка.txt", "Привет, ёжик!", Charset.forName("windows-1251"))));
        check(repository.readText(cp1251).equals("Привет, ёжик!"), "Windows1251 decoding");
        File utf16 = new File(sandbox, "unicode.txt");
        try (FileOutputStream output = new FileOutputStream(utf16)) {
            output.write(new byte[]{(byte)0xFF, (byte)0xFE});
            output.write("Текст UTF16".getBytes(StandardCharsets.UTF_16LE));
        }
        check(repository.readText(repository.importBook(Uri.fromFile(utf16))).equals("Текст UTF16"), "UTF16 BOM decoding");

        String fb2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description><title-info><book-title>Тестовая книга</book-title><author><first-name>Иван</first-name><last-name>Петров</last-name></author></title-info></description><body><section><title><p>Глава</p></title><p>Первый абзац.</p><p>Второй <emphasis>важный</emphasis> абзац.</p></section></body></FictionBook>";
        BookRepository.Book fiction = repository.importBook(Uri.fromFile(textFile("fiction.fb2", fb2, StandardCharsets.UTF_8)));
        check(fiction.title.equals("Тестовая книга"), "FB2 title");
        check(fiction.author.equals("Иван Петров"), "FB2 author");
        check(repository.readText(fiction).contains("Второй важный абзац."), "FB2 inline content");
        check(repository.readText(fiction).contains("Первый абзац.\n"), "FB2 paragraph break");
        String repairedText = repository.readText(fiction);
        String oldText = repairedText.replace("\n", "\n\n   ");
        File oldCache = new File(privateFiles, "book-text/" + fiction.id + ".txt");
        Files.writeString(oldCache.toPath(), oldText, StandardCharsets.UTF_8);
        fiction.textVersion = 0;
        repository.updateProgress(fiction, oldText.indexOf("Второй"));
        check(repository.readText(fiction).equals(repairedText), "old cache reparsed automatically");
        check(repairedText.substring(fiction.position).startsWith("Второй"), "migration keeps the printed-word anchor");
        int repairedPosition = fiction.position;
        check(repository.readText(fiction).equals(repairedText) && fiction.position == repairedPosition, "cache repair is idempotent");
        BookRepository afterMigration = new BookRepository(isolated);
        BookRepository.Book persistedFiction = null;
        for (BookRepository.Book item : afterMigration.list()) if (item.id.equals(fiction.id)) persistedFiction = item;
        check(persistedFiction != null && persistedFiction.textVersion == 2 && persistedFiction.position == repairedPosition, "cache version and anchor persisted");
        java.util.List<BookRepository.Chapter> fictionChapters = repository.readChapters(fiction);
        check(fictionChapters.size() == 1 && fictionChapters.get(0).title.equals("Глава"), "FB2 title indexed");
        check(repairedText.substring(fictionChapters.get(0).offset).startsWith("Глава"), "FB2 TOC offset matches display text");
        check(afterMigration.readChapters(persistedFiction).get(0).offset == fictionChapters.get(0).offset, "TOC cache roundtrip");
        File fictionZip = zipFile("fiction.fb2.zip", single("folder/book.fb2", fb2));
        BookRepository.Book zipped = repository.importBook(Uri.fromFile(fictionZip));
        check(zipped.format.equals("FB2"), "FB2 ZIP detected");
        check(repository.readText(zipped).equals(repository.readText(fiction)), "FB2 ZIP text matches");

        Map<String, String> epubFiles = new LinkedHashMap<>();
        epubFiles.put("META-INF/container.xml", "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
        epubFiles.put("OPS/book.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>EPUB по порядку</dc:title><dc:creator>Автор</dc:creator></metadata><manifest><item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"b\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"b\"/><itemref idref=\"a\"/></spine></package>");
        epubFiles.put("OPS/a.xhtml", "<html><body><h1>ВТОРАЯ ГЛАВА</h1><p>После первой.</p></body></html>");
        epubFiles.put("OPS/b.xhtml", "<html><body><h1>ПЕРВАЯ ГЛАВА</h1><p>Читать сначала.</p></body></html>");
        BookRepository.Book epub = repository.importBook(Uri.fromFile(zipFile("ordered.epub", epubFiles)));
        String epubText = repository.readText(epub);
        check(epub.format.equals("EPUB"), "EPUB detection");
        check(epub.title.equals("EPUB по порядку") && epub.author.equals("Автор"), "EPUB metadata");
        check(epubText.indexOf("ПЕРВАЯ ГЛАВА") < epubText.indexOf("ВТОРАЯ ГЛАВА"), "EPUB spine order instead of ZIP order");
        java.util.List<BookRepository.Chapter> epubChapters = repository.readChapters(epub);
        check(epubChapters.size() == 2 && epubChapters.get(0).title.equals("ПЕРВАЯ ГЛАВА"), "EPUB headings follow spine");
        check(epubText.substring(epubChapters.get(1).offset).startsWith("ВТОРАЯ ГЛАВА"), "EPUB exact chapter offset");

        String firstSpine = "<html><body><p>Ранее упомянута Глава снова.</p><h1>Глава <b>снова</b></h1>"
                + "<p>Первый раздел.</p><h3>Глава снова</h3><p>Вложенный раздел.</p></body></html>";
        String secondSpine = "<html><body><h6>&nbsp;<i></i></h6><p>Глава снова заранее.</p>"
                + "<h2>Глава <em>снова</em></h2><p>После второго заголовка.</p></body></html>";
        Map<String, String> repeatedEpubFiles = new LinkedHashMap<>(epubFiles);
        repeatedEpubFiles.put("OPS/book.opf", epubFiles.get("OPS/book.opf")
                .replace("</manifest>", "<item id=\"empty\" href=\"empty.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>")
                .replace("<spine>", "<spine><itemref idref=\"empty\"/>"));
        repeatedEpubFiles.put("OPS/b.xhtml", firstSpine);
        repeatedEpubFiles.put("OPS/a.xhtml", secondSpine);
        repeatedEpubFiles.put("OPS/empty.xhtml", "<html><body><h4> \t&nbsp;<i></i></h4></body></html>");
        BookRepository.Book repeatedEpub = repository.importBook(Uri.fromFile(zipFile("repeated-headings.epub", repeatedEpubFiles)));
        String repeatedEpubText = repository.readText(repeatedEpub);
        java.util.List<BookRepository.Chapter> repeatedEpubChapters = repository.readChapters(repeatedEpub);
        String firstSpineText = renderedHtmlBaseline(firstSpine), secondSpineText = renderedHtmlBaseline(secondSpine);
        check(repeatedEpubText.equals(firstSpineText + "\n\n" + secondSpineText), "EPUB markers preserve displayed text and empty spine stays empty");
        check(repeatedEpubChapters.size() == 3, "EPUB indexes repeated headings and omits empty headings");
        int firstSpineHeading = firstSpineText.indexOf("Глава снова", firstSpineText.indexOf("Глава снова") + 1);
        int nestedSpineHeading = firstSpineText.indexOf("Глава снова", firstSpineHeading + 1);
        int secondSpineHeading = secondSpineText.indexOf("Глава снова", secondSpineText.indexOf("Глава снова") + 1);
        check(repeatedEpubChapters.get(0).offset == firstSpineHeading, "EPUB heading skips its earlier prose mention");
        check(repeatedEpubChapters.get(1).offset == nestedSpineHeading, "EPUB duplicate heading points to its own occurrence");
        check(repeatedEpubChapters.get(2).offset == firstSpineText.length() + 2 + secondSpineHeading, "EPUB repeated title keeps the exact next-spine base offset");
        check(repeatedEpubChapters.get(0).level == 0 && repeatedEpubChapters.get(1).level == 2 && repeatedEpubChapters.get(2).level == 1, "EPUB heading hierarchy survives repeated titles");
        for (BookRepository.Chapter chapter : repeatedEpubChapters) check(chapter.title.equals("Глава снова"), "EPUB inline heading title has no marker text");

        File html = textFile("page.html", "<html><head><title>HTML книга</title><style>PRIVATE_STYLE</style></head><body><p>Читаемый текст</p><script>PRIVATE_SCRIPT;throw new Error('do not execute');</script><p>Продолжение</p></body></html>", StandardCharsets.UTF_8);
        BookRepository.Book webBook = repository.importBook(Uri.fromFile(html));
        String webText = repository.readText(webBook);
        check(webBook.title.equals("HTML книга"), "HTML title");
        check(webText.contains("Читаемый текст") && webText.contains("Продолжение"), "HTML text");
        check(!webText.contains("PRIVATE_SCRIPT") && !webText.contains("PRIVATE_STYLE"), "HTML active content removed");

        String navigationHtml = "<html><body><p>Ранее упомянута Глава снова.</p>"
                + "<h1 data-note=\"2 > 1\"> \tГлава <em>снова</em> </h1><p>Первый раздел.</p>"
                + "<h2>&nbsp;<b>Глава снова</b>&nbsp;</h2><p>Второй раздел.</p>"
                + "<h3> \t&nbsp;<i></i></h3><!-- <h5>Скрытая глава</h5> -->"
                + "<h4>Деталь<br>раздела</h4><p>Конец.</p></body></html>";
        BookRepository.Book navigation = repository.importBook(Uri.fromFile(textFile("headings.html", navigationHtml, StandardCharsets.UTF_8)));
        String navigationText = repository.readText(navigation);
        java.util.List<BookRepository.Chapter> navigationChapters = repository.readChapters(navigation);
        check(navigationText.equals(renderedHtmlBaseline(navigationHtml)), "HTML markers preserve exact Android whitespace and leave no marker text");
        check(navigationChapters.size() == 3, "HTML empty and commented headings add no navigation entries");
        int proseMention = navigationText.indexOf("Глава снова");
        int firstHeading = navigationText.indexOf("Глава снова", proseMention + 1);
        int repeatedHeading = navigationText.indexOf("Глава снова", firstHeading + 1);
        check(proseMention >= 0 && firstHeading > proseMention && navigationChapters.get(0).offset == firstHeading, "HTML heading skips its earlier prose mention");
        check(repeatedHeading > firstHeading && navigationChapters.get(1).offset == repeatedHeading, "HTML duplicate title points to its own heading");
        check(navigationChapters.get(0).title.equals("Глава снова") && navigationChapters.get(1).title.equals("Глава снова"), "HTML inline formatting and surrounding whitespace in heading titles");
        check(navigationChapters.get(2).title.equals("Деталь раздела") && navigationChapters.get(2).offset == navigationText.indexOf("Деталь\nраздела"), "HTML multiline heading title and exact start");
        check(navigationChapters.get(0).level == 0 && navigationChapters.get(1).level == 1 && navigationChapters.get(2).level == 3, "HTML heading hierarchy");

        int count = repository.list().size();
        expectRejected(repository, textFile("unsupported.mobi", "unsupported", StandardCharsets.UTF_8), "unsupported MOBI");
        expectRejected(repository, textFile("broken.fb2", "<FictionBook><body>", StandardCharsets.UTF_8), "malformed XML");
        expectRejected(repository, textFile("entity.fb2", "<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///does-not-exist'>]><FictionBook><body><p>&x;</p></body></FictionBook>", StandardCharsets.UTF_8), "external XML entity rejected");
        expectRejected(repository, zipFile("traversal.zip", single("../../outside.fb2", fb2)), "archive traversal rejected");
        Map<String, String> badEpub = new LinkedHashMap<>(epubFiles);
        badEpub.put("OPS/book.opf", epubFiles.get("OPS/book.opf").replace("b.xhtml", "../../outside.xhtml"));
        expectRejected(repository, zipFile("escape.epub", badEpub), "EPUB root escape rejected");
        expectRejected(repository, zipFile("many-books.zip", two("one.fb2", fb2, "two.fb2", fb2)), "ambiguous archive rejected");
        File bigZip = new File(sandbox, "oversized.epub");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bigZip))) {
            for (Map.Entry<String, String> item : epubFiles.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                if (item.getKey().equals("OPS/b.xhtml")) {
                    byte[] megabyte = new byte[1024 * 1024]; java.util.Arrays.fill(megabyte, (byte)'a');
                    for (int i = 0; i < 33; i++) zip.write(megabyte);
                } else zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        expectRejected(repository, bigZip, "inflated chapter size bounded");
        check(repository.list().size() == count, "rejected imports leave shelf unchanged");
        for (File child : new File(isolated.getFilesDir(), "books").listFiles()) check(!child.getName().endsWith(".part") && !child.getName().endsWith(".unpacked"), "failed import temporary files removed");
        check(!new File(target.getCacheDir(), "outside.fb2").exists(), "archive never writes outside sandbox");
    }

    private void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    /** Independent unmarked Android rendering, with the pre-existing displayed-text normalization. */
    private String renderedHtmlBaseline(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ').replace("\uFEFF", "")
                .replaceAll("[\\t\\x0B\\f\\u2007\\u202F ]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private void expectRejected(BookRepository repository, File file, String name) throws Exception {
        boolean rejected = false;
        try { repository.importBook(Uri.fromFile(file)); }
        catch (Exception expected) { rejected = true; }
        check(rejected, name);
    }

    private File textFile(String name, String content, Charset charset) throws IOException {
        File file = new File(sandbox, name);
        Files.write(file.toPath(), content.getBytes(charset));
        return file;
    }

    private File zipFile(String name, Map<String, String> contents) throws IOException {
        File file = new File(sandbox, name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> item : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private Map<String, String> single(String name, String contents) {
        Map<String, String> result = new LinkedHashMap<>(); result.put(name, contents); return result;
    }

    private Map<String, String> two(String name1, String contents1, String name2, String contents2) {
        Map<String, String> result = single(name1, contents1); result.put(name2, contents2); return result;
    }
}
