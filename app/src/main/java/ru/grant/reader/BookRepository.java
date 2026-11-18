package ru.grant.reader;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Private, offline bookshelf. Text position is a character offset; PDF position a page index. */
public final class BookRepository {
    public static final class Chapter {
        public String title;
        public int offset, level;
        public Chapter(String title, int offset, int level) {
            this.title = title; this.offset = offset; this.level = level;
        }
    }
    public static final class Book {
        public String id, title, author, format, fileName;
        public int position;
        int textVersion;
        public long importedAt;
    }

    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 40 * 1024 * 1024;
    private final Context context;
    private final File booksDir, textDir;
    private final AtomicFile index;
    private final ArrayList<Book> books = new ArrayList<>();
    private String lastError = "";
    private boolean damagedIndex;

    public BookRepository(Context context) {
        this.context = context.getApplicationContext();
        booksDir = new File(this.context.getFilesDir(), "books");
        textDir = new File(this.context.getFilesDir(), "book-text");
        booksDir.mkdirs();
        textDir.mkdirs();
        index = new AtomicFile(new File(this.context.getFilesDir(), "bookshelf.json"));
        load();
    }

    public synchronized String getLastError() { return lastError; }

    /** Returns a snapshot in most-recently-imported order. Do not edit metadata directly. */
    public synchronized List<Book> list() {
        ArrayList<Book> result = new ArrayList<>(books);
        Collections.sort(result, (a, b) -> Long.compare(b.importedAt, a.importedAt));
        return result;
    }

    public synchronized Book importBook(Uri uri) throws Exception {
        if (uri == null) throw new IOException("Файл не выбран");
        ensureWritableIndex();
        String displayName = displayName(uri);
        String extension = extension(displayName);
        String mime = context.getContentResolver().getType(uri);
        String id = UUID.randomUUID().toString();
        File incoming = new File(booksDir, id + ".part");
        File destination = null;
        File cachedText = new File(textDir, id + ".v2.txt");
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(incoming)) {
                if (input == null) throw new IOException("Не удалось открыть выбранный файл");
                copyLimited(input, output, MAX_FILE_BYTES);
            }
            String type = detectType(incoming, extension, mime);
            BookTextParser.Parsed parsed = null;
            if (type.equals("ZIP")) {
                ZipChoice choice = selectArchiveBook(incoming);
                File extracted = new File(booksDir, id + ".unpacked");
                try (ZipFile zip = new ZipFile(incoming);
                     InputStream input = zip.getInputStream(zip.getEntry(choice.name));
                     FileOutputStream output = new FileOutputStream(extracted)) {
                    copyLimited(input, output, MAX_FILE_BYTES);
                } catch (Exception error) {
                    extracted.delete();
                    throw error;
                }
                incoming.delete();
                incoming = extracted;
                type = choice.type;
            }
            if (!type.equals("PDF")) parsed = BookTextParser.parse(incoming, type);
            destination = new File(booksDir, id + "." + type.toLowerCase(Locale.ROOT));
            if (!incoming.renameTo(destination)) throw new IOException("Не удалось сохранить книгу");
            Book book = new Book();
            book.id = id;
            book.fileName = destination.getName();
            book.title = parsed != null && !parsed.title.isEmpty() ? parsed.title : cleanTitle(displayName);
            book.author = parsed == null ? "" : parsed.author;
            book.format = type;
            book.position = 0;
            book.textVersion = 2;
            book.importedAt = System.currentTimeMillis();
            if (parsed != null) { writeText(cachedText, parsed.text); writeChapters(id, parsed.chapters); }
            books.add(book);
            try { persist(); }
            catch (IOException | OutOfMemoryError error) { books.remove(book); throw error; }
            return book;
        } catch (Exception | OutOfMemoryError error) {
            incoming.delete();
            if (destination != null) destination.delete();
            cachedText.delete();
            new File(textDir, id + ".v2.toc.json").delete();
            throw error;
        }
    }

    public synchronized void updateProgress(Book book, int position) {
        Book stored = find(book.id);
        if (stored == null) return;
        int previous = stored.position;
        stored.position = Math.max(0, position);
        try { persist(); }
        catch (IOException error) {
            stored.position = previous;
            throw new IllegalStateException("Не удалось сохранить место чтения", error);
        }
        book.position = stored.position;
    }

    /** Deletes only this app's imported copy. The user's original file is untouched. */
    public synchronized void delete(Book book) {
        Book stored = find(book.id);
        if (stored == null) return;
        int at = books.indexOf(stored);
        books.remove(at);
        try { persist(); }
        catch (IOException error) {
            books.add(at, stored);
            throw new IllegalStateException("Не удалось удалить книгу с полки", error);
        }
        fileFor(stored).delete();
        new File(textDir, stored.id + ".txt").delete();
        new File(textDir, stored.id + ".v2.txt").delete();
        new File(textDir, stored.id + ".v2.toc.json").delete();
    }

    public synchronized String readText(Book book) throws Exception {
        if ("PDF".equals(book.format)) throw new IOException("PDF открывается постранично");
        File cache = new File(textDir, book.id + ".v2.txt");
        if (book.textVersion == 2 && cache.isFile() && cache.length() <= MAX_TEXT_BYTES) {
            return readCachedBook(cache);
        }
        BookTextParser.Parsed parsed = BookTextParser.parse(fileFor(book), book.format);
        int previousPosition = book.position, previousVersion = book.textVersion;
        File legacy = new File(textDir, book.id + ".txt");
        int correctedPosition = previousPosition;
        if (previousVersion != 2 && previousPosition > 0 && legacy.isFile() && legacy.length() <= MAX_TEXT_BYTES) {
            try (Reader input = new InputStreamReader(new FileInputStream(legacy), StandardCharsets.UTF_8)) {
                correctedPosition = ReadingPosition.afterWhitespaceRepair(input, parsed.text, previousPosition);
            }
        }
        writeText(cache, parsed.text);
        writeChapters(book.id, parsed.chapters);
        book.position = correctedPosition;
        book.textVersion = 2;
        try { persist(); }
        catch (IOException | OutOfMemoryError error) { book.position = previousPosition; book.textVersion = previousVersion; throw error; }
        return parsed.text;
    }

    private static String readCachedBook(File cache) throws IOException {
        try (Reader input = new InputStreamReader(new FileInputStream(cache), StandardCharsets.UTF_8)) {
            StringBuilder result = new StringBuilder((int)Math.min(cache.length(), 12 * 1024 * 1024));
            char[] buffer = new char[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if ((long)result.length() + count > 12 * 1024 * 1024) throw new IOException("Сохранённый текст книги слишком большой");
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    /** Offsets use exactly the normalized text returned by readText. Call on the I/O worker. */
    public synchronized List<Chapter> readChapters(Book book) throws Exception {
        if ("PDF".equals(book.format)) return new ArrayList<>();
        File cache = new File(textDir, book.id + ".v2.toc.json");
        if (cache.isFile() && cache.length() <= 4 * 1024 * 1024) {
            try {
                JSONArray array = new JSONArray(readFileText(cache, 4 * 1024 * 1024));
                ArrayList<Chapter> result = new ArrayList<>();
                if (array.length() > 10000) throw new IOException("Оглавление слишком большое");
                int previousOffset = -1;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.getJSONObject(i);
                    int offset = entry.getInt("offset"), level = entry.getInt("level");
                    String title = entry.getString("title");
                    if (offset < previousOffset || level < 0 || level > 256 || title.isEmpty()) throw new IOException("Повреждено оглавление");
                    result.add(new Chapter(title, offset, level)); previousOffset = offset;
                }
                return result;
            } catch (Exception invalidCache) { /* Rebuild from the private original. */ }
        }
        BookTextParser.Parsed parsed = BookTextParser.parse(fileFor(book), book.format);
        writeChapters(book.id, parsed.chapters);
        return new ArrayList<>(parsed.chapters);
    }

    private static String readFileText(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return new String(readLimited(input, limit), StandardCharsets.UTF_8); }
    }

    private void writeChapters(String id, List<Chapter> chapters) throws Exception {
        JSONArray array = new JSONArray();
        for (Chapter chapter : chapters) array.put(new JSONObject().put("title", chapter.title).put("offset", chapter.offset).put("level", chapter.level));
        writeText(new File(textDir, id + ".v2.toc.json"), array.toString());
    }

    public File fileFor(Book book) {
        if (book == null || book.fileName == null || !book.fileName.matches("[a-zA-Z0-9-]+\\.(pdf|epub|fb2|txt|html)")) {
            throw new IllegalArgumentException("Повреждена запись книги");
        }
        return new File(booksDir, book.fileName);
    }

    private Book find(String id) {
        for (Book book : books) if (book.id.equals(id)) return book;
        return null;
    }

    private void load() {
        if (!index.getBaseFile().exists() && !new File(index.getBaseFile() + ".bak").exists()) return;
        try {
            JSONArray array = new JSONObject(new String(index.readFully(), StandardCharsets.UTF_8)).getJSONArray("books");
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Book book = new Book();
                book.id = item.getString("id");
                if (!book.id.matches("[a-zA-Z0-9-]+")) throw new IOException("Недопустимый идентификатор");
                book.title = item.optString("title", "Без названия");
                book.author = item.optString("author", "");
                book.format = item.getString("format");
                book.fileName = item.getString("fileName");
                book.position = Math.max(0, item.optInt("position", 0));
                book.textVersion = item.optInt("textVersion", 0);
                book.importedAt = item.optLong("importedAt", 0);
                fileFor(book);
                books.add(book);
            }
        } catch (Exception error) {
            damagedIndex = true;
            lastError = "Файл книжной полки повреждён. Копии книг сохранены, но изменение полки заблокировано, чтобы не потерять данные.";
        }
    }

    private void ensureWritableIndex() throws IOException {
        if (damagedIndex) throw new IOException(lastError);
    }

    private void persist() throws IOException {
        ensureWritableIndex();
        JSONArray array = new JSONArray();
        try {
            for (Book book : books) {
                JSONObject item = new JSONObject();
                item.put("id", book.id); item.put("title", book.title); item.put("author", book.author);
                item.put("format", book.format); item.put("fileName", book.fileName);
                item.put("position", book.position); item.put("importedAt", book.importedAt);
                item.put("textVersion", book.textVersion);
                array.put(item);
            }
            byte[] bytes = new JSONObject().put("version", 1).put("books", array).toString(2).getBytes(StandardCharsets.UTF_8);
            FileOutputStream output = null;
            try {
                output = index.startWrite();
                output.write(bytes);
                index.finishWrite(output);
            } catch (IOException | OutOfMemoryError error) {
                if (output != null) index.failWrite(output);
                throw error;
            }
        } catch (org.json.JSONException error) { throw new IOException("Не удалось сохранить книжную полку", error); }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { }
        String name = uri.getLastPathSegment();
        return name == null ? "Книга" : name.substring(name.lastIndexOf('/') + 1);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String cleanTitle(String name) {
        String title = name.replaceFirst("(?i)\\.(fb2\\.zip|epub|fb2|zip|pdf|txt|html|htm)$", "").trim();
        return title.isEmpty() ? "Без названия" : title;
    }

    private static String detectType(File file, String extension, String mime) throws IOException {
        byte[] prefix = new byte[8];
        int length;
        try (InputStream input = new FileInputStream(file)) { length = input.read(prefix); }
        if (length >= 5 && new String(prefix, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "PDF";
        if (length >= 2 && prefix[0] == 'P' && prefix[1] == 'K') {
            try (ZipFile zip = new ZipFile(file)) {
                if (zip.getEntry("META-INF/container.xml") != null) return "EPUB";
            }
            return "ZIP";
        }
        switch (extension) {
            case "fb2": return "FB2";
            case "html": case "htm": return "HTML";
            case "txt": return "TXT";
            case "epub": throw new IOException("Файл EPUB повреждён или не является ZIP-контейнером");
            case "pdf": throw new IOException("Файл не содержит корректный заголовок PDF");
            default:
                if ("text/plain".equals(mime)) return "TXT";
                if ("text/html".equals(mime)) return "HTML";
                if ("application/x-fictionbook+xml".equals(mime)) return "FB2";
                throw new IOException("Поддерживаются EPUB, FB2, FB2.ZIP, TXT, HTML и PDF. Файлы MOBI, AZW и DJVU сначала нужно преобразовать.");
        }
    }

    private static final class ZipChoice { String name, type; }

    private static ZipChoice selectArchiveBook(File file) throws IOException {
        ZipChoice selected = null;
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() > 5000) throw new IOException("В архиве слишком много файлов");
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.ROOT);
                String portableName = name.replace('\\', '/');
                if (portableName.startsWith("/") || portableName.matches("^[a-z]:.*") ||
                        java.util.Arrays.asList(portableName.split("/")).contains("..")) {
                    throw new IOException("Архив содержит недопустимый путь");
                }
                if (entry.isDirectory() || name.startsWith("__macosx/")) continue;
                String type = name.endsWith(".fb2") ? "FB2" : name.endsWith(".epub") ? "EPUB" : null;
                if (type != null) {
                    if (selected != null) throw new IOException("В архиве несколько книг. Распакуйте его и выберите одну книгу.");
                    if (entry.getSize() > MAX_FILE_BYTES) throw new IOException("Книга в архиве слишком большая");
                    selected = new ZipChoice(); selected.name = entry.getName(); selected.type = type;
                }
            }
        }
        if (selected == null) throw new IOException("В ZIP-архиве не найдена книга FB2 или EPUB");
        return selected;
    }

    static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyLimited(input, output, limit);
        return output.toByteArray();
    }

    private static void copyLimited(InputStream input, java.io.OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Файл слишком большой для импорта (предел 256 МБ; текст — до 40 МБ)");
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IOException("Выбран пустой файл");
    }

    private static void writeText(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            OutputStream sink = output;
            OutputStream limited = new OutputStream() {
                private long written;
                @Override public void write(int value) throws IOException {
                    if (++written > MAX_TEXT_BYTES) throw new IOException("В книге слишком много текста (предел 40 МБ)");
                    sink.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                    if (written + count > MAX_TEXT_BYTES) throw new IOException("В книге слишком много текста (предел 40 МБ)");
                    sink.write(bytes, offset, count); written += count;
                }
            };
            OutputStreamWriter writer = new OutputStreamWriter(limited, StandardCharsets.UTF_8);
            char[] buffer = new char[8192];
            for (int offset = 0; offset < text.length();) {
                int count = Math.min(buffer.length, text.length() - offset);
                text.getChars(offset, offset + count, buffer, 0);
                writer.write(buffer, 0, count); offset += count;
            }
            writer.flush();
            atomic.finishWrite(output);
        } catch (IOException | OutOfMemoryError error) { if (output != null) atomic.failWrite(output); throw error; }
    }
}
