package ru.grant.reader;

import android.text.Html;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;

/** Parsers never load remote resources, run JavaScript, or extract archive paths to disk. */
final class BookTextParser {
    private static final int MAX_DOCUMENT = 32 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 12 * 1024 * 1024;
    private static final int MAX_XML_DEPTH = 256;

    static final class Parsed {
        String title = "", author = "", text = "";
        final ArrayList<BookRepository.Chapter> chapters = new ArrayList<>();
    }

    static Parsed parse(File file, String format) throws Exception {
        if (!file.isFile()) throw new IOException("Копия книги не найдена. Импортируйте файл заново.");
        if ("EPUB".equals(format)) return epub(file);
        if ("FB2".equals(format)) return Fb2Reader.read(file);
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) { bytes = BookRepository.readLimited(input, MAX_DOCUMENT); }
        String source = decode(bytes);
        Parsed parsed = new Parsed();
        if ("HTML".equals(format)) {
            Matcher title = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(source);
            if (title.find()) parsed.title = htmlText(title.group(1));
            parsed.text = htmlText(source, 0, parsed.chapters);
        } else if ("TXT".equals(format)) parsed.text = normalize(source);
        else throw new IOException("Неизвестный формат книги");
        checkText(parsed.text);
        return parsed;
    }

    /** Keeps only the printed text, small metadata and the open XML element stack in memory. */
    private static final class Fb2Reader extends DefaultHandler implements LexicalHandler {
        private static final String LEXICAL = "http://xml.org/sax/properties/lexical-handler";
        private final Parsed result = new Parsed();
        private final ArrayList<Fb2Frame> stack = new ArrayList<>();
        private final ArrayList<Fb2Capture> captures = new ArrayList<>();
        private final ArrayList<Fb2Author> authors = new ArrayList<>();
        private final Fb2Output out = new Fb2Output();
        private int bodyDepth, sectionDepth, skipped = -1, line = -1, metadata = -1;
        private int captureBudget;
        private boolean foundMetadata, foundTitle, lineStarted, lineSpace;
        private boolean nodeNonTrim, nodeSpace, nodeHeldSpace;

        static Parsed read(File file) throws Exception {
            if (file.length() > MAX_DOCUMENT) throw new IOException("FB2 exceeds the document size limit");
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            // Expat on Android does not implement every Xerces feature. The required lexical
            // handler rejects every DTD before declarations can expand; the resolver also fails closed.
            for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities",
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
                try { factory.setFeature(feature, false); } catch (Exception ignored) { }
            }
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignored) { }
            XMLReader reader = factory.newSAXParser().getXMLReader();
            Fb2Reader handler = new Fb2Reader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entities are disabled"); });
            reader.setProperty(LEXICAL, handler);
            try (InputStream input = new FilterInputStream(new FileInputStream(file)) {
                private long count;
                private void consumed(int amount) throws IOException {
                    if (amount > 0 && (count += amount) > MAX_DOCUMENT) throw new IOException("FB2 exceeds the document size limit");
                }
                @Override public int read() throws IOException {
                    int value = in.read(); consumed(value < 0 ? 0 : 1); return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int amount = in.read(bytes, offset, length); consumed(amount); return amount;
                }
            }) {
                // The XML reader handles UTF-8/UTF-16 BOMs and the declared Windows-1251 encoding.
                reader.parse(new InputSource(input));
            }
            handler.result.text = handler.out.finish();
            StringBuilder names = new StringBuilder();
            for (Fb2Author author : handler.authors) {
                String name = author.name();
                if (!name.isEmpty()) {
                    if (names.length() > 0) names.append(", ");
                    names.append(name);
                    if (names.length() > MAX_TEXT_CHARS) throw new IOException("FB2 metadata is too long");
                }
            }
            handler.result.author = names.toString();
            checkText(handler.result.text);
            return handler.result;
        }

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            textBoundary();
            String name = localName.isEmpty() ? qName : localName;
            String tag = name.toLowerCase(Locale.ROOT);
            int depth = stack.size();
            if (depth > MAX_XML_DEPTH) throw new SAXException("FB2 XML nesting is too deep");
            if (depth == 0 && !tag.equals("fictionbook")) throw new SAXException("Not an FB2 document");
            Fb2Frame parent = depth == 0 ? null : stack.get(depth - 1);
            Fb2Frame frame = new Fb2Frame(tag);
            if (parent != null && parent.chapter != null) {
                frame.titlePart = capture(frame, parent, null, -1);
            }
            if (!foundMetadata && depth > 0 && name.equals("title-info")) {
                foundMetadata = true;
                metadata = depth;
            }
            if (metadata >= 0 && depth > metadata) {
                if (name.equals("book-title") && !foundTitle) {
                    foundTitle = true;
                    capture(frame, null, null, -1);
                }
                for (Fb2Author author : authors) {
                    if (!author.open) continue;
                    int part = name.equals("first-name") ? 0 : name.equals("middle-name") ? 1
                            : name.equals("last-name") ? 2 : name.equals("nickname") ? 3 : -1;
                    if (part >= 0 && !author.selected[part]) {
                        author.selected[part] = true;
                        capture(frame, null, author, part);
                    }
                }
                if (name.equals("author")) {
                    if (authors.size() >= 10000) throw new SAXException("Too many FB2 authors");
                    frame.author = new Fb2Author();
                    authors.add(frame.author);
                }
            }
            if (tag.equals("section")) sectionDepth++;
            if (name.equals("body") && depth > 0) bodyDepth++;
            if (bodyDepth > 0 && skipped < 0) {
                if (tag.equals("binary") || tag.equals("image")) skipped = depth;
                else if (line < 0) {
                    if (tag.equals("empty-line")) { out.breakBefore(2); skipped = depth; }
                    else if (tag.equals("p") || tag.equals("v") || tag.equals("subtitle") || tag.equals("text-author")) {
                        line = depth;
                        lineStarted = lineSpace = false;
                        frame.verse = tag.equals("v");
                    } else {
                        frame.block = tag.equals("title") || tag.equals("section") || tag.equals("stanza")
                                || tag.equals("poem") || tag.equals("cite") || tag.equals("epigraph") || tag.equals("tr");
                        if (frame.block) out.breakBefore(2);
                        if (tag.equals("title") && result.chapters.size() < 10000) {
                            frame.chapter = new BookRepository.Chapter("", out.rawLength, Math.max(0, sectionDepth - 1));
                            frame.label = new StringBuilder();
                            result.chapters.add(frame.chapter);
                        }
                    }
                }
            }
            stack.add(frame);
        }

        @Override public void endElement(String uri, String localName, String qName) throws SAXException {
            textBoundary();
            int depth = stack.size() - 1;
            Fb2Frame frame = stack.get(depth);
            if (frame.chapter != null) {
                frame.chapter.title = frame.label.toString();
                if (frame.chapter.title.isEmpty()) result.chapters.remove(frame.chapter);
            }
            for (Fb2Capture capture : frame.ending) {
                captures.remove(capture);
                if (capture.title != null) appendTitlePart(capture.title, capture.value);
                else if (capture.author != null) capture.author.parts[capture.part] = capture.value.toString();
                else result.title = normalizeMetadata(capture.value);
            }
            if (frame.author != null) frame.author.open = false;
            if (line == depth) { line = -1; lineStarted = lineSpace = false; }
            if (skipped == depth) skipped = -1;
            if (frame.block) out.breakBefore(2);
            String name = localName.isEmpty() ? qName : localName;
            if (name.equals("body") && depth > 0) { out.breakBefore(2); bodyDepth--; }
            if (frame.tag.equals("section")) sectionDepth--;
            if (metadata == depth) metadata = -1;
            stack.remove(depth);
        }

        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            if (length == 0) return;
            for (Fb2Capture capture : captures) appendCapture(capture.value, chars, start, length);
            Fb2Frame parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            if (parent != null && parent.chapter != null) {
                if (parent.directText == null) parent.directText = new StringBuilder();
                appendCapture(parent.directText, chars, start, length);
            }
            if (bodyDepth == 0 || skipped >= 0) return;
            for (int i = start, end = start + length; i < end; i++) {
                char c = chars[i];
                if (line >= 0) {
                    if (c == '\uFEFF') continue;
                    if (inlineSpace(c)) { lineSpace = true; continue; }
                    if (!lineStarted) {
                        out.breakBefore(stack.get(line).verse ? 1 : 2);
                        lineStarted = true;
                    } else if (lineSpace) out.append(' ');
                    out.append(c);
                    lineSpace = false;
                } else {
                    // DOM ignored whitespace-only TEXT_NODEs. Delay their leading space until
                    // a non-trim character is seen, and retain state across SAX buffer boundaries.
                    if (!nodeNonTrim && c > ' ') {
                        nodeNonTrim = true;
                        if (nodeHeldSpace) { out.append(' '); nodeHeldSpace = false; }
                    }
                    if (c == '\uFEFF') continue;
                    if (inlineSpace(c)) {
                        if (!nodeSpace) {
                            if (nodeNonTrim) out.append(' '); else nodeHeldSpace = true;
                        }
                        nodeSpace = true;
                    } else { out.append(c); nodeSpace = false; }
                }
            }
        }

        @Override public void ignorableWhitespace(char[] chars, int start, int length) throws SAXException { characters(chars, start, length); }

        private Fb2Capture capture(Fb2Frame owner, Fb2Frame title, Fb2Author author, int part) {
            Fb2Capture capture = new Fb2Capture(title, author, part);
            owner.ending.add(capture);
            captures.add(capture);
            return capture;
        }

        private void appendCapture(StringBuilder value, char[] chars, int start, int length) throws SAXException {
            if (length > MAX_TEXT_CHARS - captureBudget) throw new SAXException("FB2 metadata is too long");
            captureBudget += length;
            value.append(chars, start, length);
        }

        private void textBoundary() throws SAXException {
            nodeNonTrim = nodeSpace = nodeHeldSpace = false;
            if (!stack.isEmpty()) {
                Fb2Frame frame = stack.get(stack.size() - 1);
                if (frame.directText != null) {
                    appendTitlePart(frame, frame.directText);
                    frame.directText = null;
                }
            }
        }

        private void appendTitlePart(Fb2Frame title, CharSequence raw) throws SAXException {
            String part = inlineNormalized(raw);
            if (part.isEmpty()) return;
            if (title.label.length() > 0) title.label.append(" \u00b7 ");
            if (part.length() > MAX_TEXT_CHARS - title.label.length()) throw new SAXException("FB2 title is too long");
            title.label.append(part);
        }

        @Override public void processingInstruction(String target, String data) throws SAXException { textBoundary(); }
        @Override public void comment(char[] chars, int start, int length) throws SAXException { textBoundary(); }
        @Override public void startCDATA() throws SAXException { textBoundary(); }
        @Override public void endCDATA() throws SAXException { textBoundary(); }
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { throw new SAXException("XML DTDs are disabled"); }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }

    private static final class Fb2Frame {
        final String tag;
        final ArrayList<Fb2Capture> ending = new ArrayList<>();
        boolean block, verse;
        BookRepository.Chapter chapter;
        StringBuilder label, directText;
        Fb2Author author;
        Fb2Capture titlePart;
        Fb2Frame(String tag) { this.tag = tag; }
    }

    private static final class Fb2Capture {
        final StringBuilder value = new StringBuilder();
        final Fb2Frame title;
        final Fb2Author author;
        final int part;
        Fb2Capture(Fb2Frame title, Fb2Author author, int part) { this.title = title; this.author = author; this.part = part; }
    }

    private static final class Fb2Author {
        final String[] parts = {"", "", "", ""};
        final boolean[] selected = new boolean[4];
        boolean open = true;
        String name() {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) { if (name.length() > 0) name.append(' '); name.append(part); }
            }
            return name.length() == 0 ? parts[3].trim() : name.toString();
        }
    }

    /** Normalizes while writing, avoiding repeated full-book regex copies. Raw offsets stay compatible. */
    private static final class Fb2Output {
        private final StringBuilder text = new StringBuilder();
        int rawLength, rawNewlines, newlines;
        boolean space;
        void append(char c) throws SAXException {
            if (rawLength >= MAX_TEXT_CHARS) throw new SAXException("FB2 contains too much text");
            rawLength++;
            rawNewlines = c == '\n' ? rawNewlines + 1 : 0;
            if (c == ' ') {
                if (text.length() > 0 && newlines == 0) space = true;
            } else if (c == '\n') {
                space = false;
                if (text.length() > 0 && newlines < 2) { text.append('\n'); newlines++; }
            } else {
                if (space) text.append(' ');
                text.append(c);
                space = false;
                newlines = 0;
            }
        }
        void breakBefore(int wanted) throws SAXException {
            if (rawLength == 0) return;
            while (rawNewlines < wanted) append('\n');
        }
        String finish() {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) <= ' ') end--;
            text.setLength(end);
            return text.toString();
        }
    }

    private static boolean inlineSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000B' || c == '\f'
                || c == '\u00A0' || c == '\u2007' || c == '\u202F';
    }

    private static String inlineNormalized(CharSequence raw) {
        StringBuilder text = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\uFEFF') continue;
            if (inlineSpace(c)) { space = text.length() > 0; continue; }
            if (space) text.append(' ');
            text.append(c);
            space = false;
        }
        return text.toString();
    }

    private static String normalizeMetadata(CharSequence raw) {
        StringBuilder text = new StringBuilder(raw.length());
        boolean space = false;
        int newlines = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\uFEFF' || (c == '\n' && i > 0 && raw.charAt(i - 1) == '\r')) continue;
            if (c == '\r' || c == '\n') {
                space = false;
                if (text.length() > 0 && newlines < 2) { text.append('\n'); newlines++; }
            } else if (inlineSpace(c)) {
                if (text.length() > 0 && newlines == 0) space = true;
            } else {
                if (space) text.append(' ');
                text.append(c); space = false; newlines = 0;
            }
        }
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) <= ' ') end--;
        text.setLength(end);
        return text.toString();
    }

    private static String inlineWhitespace(String value) {
        return value.replace("\uFEFF", "").replaceAll("[\\s\\u00A0\\u2007\\u202F]+", " ");
    }

    private static void breakBefore(StringBuilder out, int wanted) {
        if (out.length() == 0) return;
        int trailing = 0;
        for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) trailing++;
        while (trailing++ < wanted) out.append('\n');
    }

    private static Parsed epub(File file) throws Exception {
        Parsed result = new Parsed();
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() > 5000) throw new IOException("В EPUB слишком много файлов");
            long[] budget = {0};
            Set<String> encryptedPaths = new HashSet<>();
            if (zip.getEntry("META-INF/rights.xml") != null || zip.getEntry("META-INF/license.lcpl") != null) {
                throw new IOException("Эта EPUB-книга защищена DRM. Нужен файл без DRM.");
            }
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                Document encryption = xml(readEntry(zip, "META-INF/encryption.xml", budget));
                for (Element reference : descendants(encryption.getDocumentElement(), "CipherReference")) {
                    String target = reference.getAttribute("URI");
                    if (!target.isEmpty()) encryptedPaths.add(normalizePath("", target));
                }
            }
            Document container = xml(readEntry(zip, "META-INF/container.xml", budget));
            Element rootfile = first(container.getDocumentElement(), "rootfile");
            if (rootfile == null) throw new IOException("В EPUB не указан основной документ");
            String opfPath = normalizePath("", rootfile.getAttribute("full-path"));
            Document opf = xml(readEntry(zip, opfPath, budget));
            Element metadata = first(opf.getDocumentElement(), "metadata");
            if (metadata != null) {
                Element title = first(metadata, "title");
                if (title != null) result.title = normalize(title.getTextContent());
                ArrayList<String> authors = new ArrayList<>();
                for (Element creator : descendants(metadata, "creator")) if (!creator.getTextContent().trim().isEmpty()) authors.add(creator.getTextContent().trim());
                result.author = android.text.TextUtils.join(", ", authors);
            }
            Map<String, Element> items = new HashMap<>();
            Element manifest = first(opf.getDocumentElement(), "manifest");
            if (manifest == null) throw new IOException("В EPUB отсутствует список файлов");
            for (Element item : descendants(manifest, "item")) items.put(item.getAttribute("id"), item);
            Element spine = first(opf.getDocumentElement(), "spine");
            if (spine == null) throw new IOException("В EPUB отсутствует порядок глав");
            String parent = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Set<String> visited = new HashSet<>();
            StringBuilder text = new StringBuilder();
            for (Element ref : descendants(spine, "itemref")) {
                if ("no".equalsIgnoreCase(ref.getAttribute("linear"))) continue;
                Element item = items.get(ref.getAttribute("idref"));
                if (item == null) throw new IOException("В EPUB отсутствует одна из глав");
                String media = item.getAttribute("media-type");
                if (!media.equals("application/xhtml+xml") && !media.equals("text/html") && !media.isEmpty()) continue;
                String path = normalizePath(parent, item.getAttribute("href"));
                if (encryptedPaths.contains(path)) throw new IOException("Текст EPUB зашифрован или защищён DRM. Нужен файл без DRM.");
                if (!visited.add(path)) continue;
                String html = readEntry(zip, path, budget);
                int before = result.chapters.size();
                String chapter = htmlText(html, text.length(), result.chapters);
                if (!chapter.isEmpty()) {
                    if (result.chapters.size() == before && before < 10000) {
                        String firstLine = chapter.split("\n", 2)[0].trim();
                        if (firstLine.length() > 160) firstLine = firstLine.substring(0, 157) + "…";
                        result.chapters.add(new BookRepository.Chapter(firstLine, text.length(), 0));
                    }
                    text.append(chapter).append("\n\n");
                }
                if (text.length() > MAX_TEXT_CHARS) throw new IOException("В EPUB слишком много текста");
            }
            result.text = normalize(text.toString());
            if (result.text.isEmpty() && zip.getEntry("META-INF/encryption.xml") != null) throw new IOException("Эта EPUB-книга защищена DRM или зашифрована. Нужен файл без DRM.");
            checkText(result.text);
        } catch (java.util.zip.ZipException error) { throw new IOException("EPUB повреждён или защищён паролем", error); }
        return result;
    }

    private static String readEntry(ZipFile zip, String path, long[] budget) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null || entry.isDirectory()) throw new IOException("В EPUB не найден файл: " + path);
        if (entry.getSize() > MAX_DOCUMENT) throw new IOException("Раздел EPUB слишком большой");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = BookRepository.readLimited(input, MAX_DOCUMENT);
            budget[0] += bytes.length;
            if (budget[0] > 64L * 1024 * 1024) throw new IOException("Распакованный EPUB слишком большой");
            return decode(bytes);
        }
    }

    private static String normalizePath(String base, String href) throws IOException {
        String path = href.replace('\\', '/');
        int fragment = path.indexOf('#'); if (fragment >= 0) path = path.substring(0, fragment);
        int query = path.indexOf('?'); if (query >= 0) path = path.substring(0, query);
        if (path.contains(":") || path.startsWith("/")) throw new IOException("EPUB содержит внешнюю или недопустимую ссылку");
        // ZIP names use UTF-8; URI path escapes, including spaces, are decoded without treating '+' as a space.
        try { path = java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8"); }
        catch (IllegalArgumentException error) { throw new IOException("Недопустимое имя главы EPUB", error); }
        ArrayList<String> segments = new ArrayList<>();
        for (String part : (base + path).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (segments.isEmpty()) throw new IOException("Недопустимый путь внутри EPUB");
                segments.remove(segments.size() - 1);
            } else segments.add(part);
        }
        return android.text.TextUtils.join("/", segments);
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        int skip = bytes.length >= 3 && bytes[0] == (byte)0xEF && bytes[1] == (byte)0xBB && bytes[2] == (byte)0xBF ? 3 : 0;
        String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1);
        Matcher encoding = Pattern.compile("(?i)<\\?xml[^>]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(head);
        if (encoding.find()) {
            try { return new String(bytes, skip, bytes.length - skip, Charset.forName(encoding.group(1))); }
            catch (Exception ignored) { }
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString(); }
        catch (CharacterCodingException error) { return new String(bytes, skip, bytes.length - skip, Charset.forName("windows-1251")); }
    }

    private static Document xml(String source) throws Exception {
        if (Pattern.compile("(?i)<!\\s*(DOCTYPE|ENTITY)\\b").matcher(source).find()) throw new IOException("XML с DTD и внешними сущностями не поддерживается");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        // Android's XML implementation varies; rejecting DTD above plus the resolver below is mandatory.
        for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities", "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try { factory.setFeature(feature, false); } catch (Exception ignored) { }
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("Внешние XML-ссылки запрещены"); });
        Document document = builder.parse(new InputSource(new StringReader(source)));
        checkDepth(document.getDocumentElement(), 0);
        return document;
    }

    private static void checkDepth(Node node, int depth) throws IOException {
        if (depth > MAX_XML_DEPTH) throw new IOException("Слишком сложная структура XML");
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) if (children.item(i).getNodeType() == Node.ELEMENT_NODE) checkDepth(children.item(i), depth + 1);
    }

    private static Element first(Element parent, String name) {
        NodeList all = parent.getElementsByTagNameNS("*", name);
        if (all.getLength() > 0) return (Element)all.item(0);
        all = parent.getElementsByTagName(name);
        return all.getLength() == 0 ? null : (Element)all.item(0);
    }

    private static ArrayList<Element> descendants(Element parent, String name) {
        ArrayList<Element> result = new ArrayList<>();
        NodeList all = parent.getElementsByTagNameNS("*", name);
        if (all.getLength() == 0) all = parent.getElementsByTagName(name);
        for (int i = 0; i < all.getLength(); i++) result.add((Element)all.item(i));
        return result;
    }

    private static String local(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }

    private static String htmlText(String html) throws IOException {
        return htmlText(html, 0, null);
    }

    private static final class HtmlBoundary {
        int raw = -1, normalized;
    }

    private static final class HtmlHeading {
        final int start, level;
        int end;
        final HtmlBoundary first = new HtmlBoundary(), last = new HtmlBoundary();
        HtmlHeading(int start, int level) { this.start = start; this.level = level; }
    }

    private static String htmlText(String html, int base, ArrayList<BookRepository.Chapter> chapters) throws IOException {
        if (html.length() > MAX_TEXT_CHARS * 2) throw new IOException("Раздел книги слишком большой");
        String safe = html.replaceAll("(?is)<(script|style|noscript|head)\\b[^>]*>.*?</\\1\\s*>", "")
                .replaceAll("(?is)<img\\b[^>]*>", "");
        ArrayList<HtmlHeading> headings = htmlHeadings(safe, chapters == null ? 0 : 10000 - chapters.size());
        if (headings.isEmpty()) return normalize(Html.fromHtml(safe, Html.FROM_HTML_MODE_LEGACY).toString());

        // Empty custom tags leave Html's whitespace handling unchanged, including empty headings.
        // Text markers would change its collapsing of spaces and insertion of block newlines.
        String prefix;
        do { prefix = "readertoc" + UUID.randomUUID().toString().replace("-", ""); }
        while (Pattern.compile(prefix, Pattern.CASE_INSENSITIVE).matcher(safe).find());
        Map<String, HtmlBoundary> markers = new HashMap<>();
        StringBuilder marked = new StringBuilder(safe.length());
        int from = 0, number = 0;
        for (HtmlHeading heading : headings) {
            String start = prefix + number + "s", end = prefix + number++ + "e";
            markers.put(start, heading.first);
            markers.put(end, heading.last);
            marked.append(safe, from, heading.start).append('<').append(start).append("></").append(start).append('>');
            marked.append(safe, heading.start, heading.end).append('<').append(end).append("></").append(end).append('>');
            from = heading.end;
        }
        marked.append(safe, from, safe.length());
        ArrayList<HtmlBoundary> boundaries = new ArrayList<>();
        String raw = Html.fromHtml(marked.toString(), Html.FROM_HTML_MODE_LEGACY, null, (opening, tag, output, reader) -> {
            HtmlBoundary boundary = markers.get(tag);
            if (opening && boundary != null && boundary.raw < 0) {
                boundary.raw = output.length();
                boundaries.add(boundary);
            }
        }).toString();
        String text = normalizeHtml(raw, boundaries);
        for (HtmlHeading heading : headings) {
            if (heading.first.raw < 0 || heading.last.raw < heading.first.raw) continue;
            int start = heading.first.normalized, end = heading.last.normalized;
            while (start < end && text.charAt(start) <= ' ') start++;
            while (end > start && text.charAt(end - 1) <= ' ') end--;
            if (start < end) chapters.add(new BookRepository.Chapter(inlineWhitespace(text.substring(start, end)), base + start, heading.level));
        }
        return text;
    }

    private static ArrayList<HtmlHeading> htmlHeadings(String html, int limit) {
        ArrayList<HtmlHeading> headings = new ArrayList<>();
        HtmlHeading current = null;
        int cursor = 0;
        while (headings.size() < limit) {
            int at = html.indexOf('<', cursor);
            if (at < 0) break;
            if (html.startsWith("<!--", at) || html.startsWith("<![CDATA[", at)) {
                String close = html.startsWith("<!--", at) ? "-->" : "]]>";
                int end = html.indexOf(close, at + 4);
                if (end < 0) break;
                cursor = end + close.length();
                continue;
            }
            int name = at + 1;
            boolean closing = name < html.length() && html.charAt(name) == '/';
            if (closing) name++;
            int nameEnd = name;
            while (nameEnd < html.length()) {
                char c = html.charAt(nameEnd);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == ':')) break;
                nameEnd++;
            }
            if (nameEnd == name) { cursor = at + 1; continue; }
            char quote = 0;
            int end = nameEnd;
            for (; end < html.length(); end++) {
                char c = html.charAt(end);
                if (quote != 0) { if (c == quote) quote = 0; }
                else if (c == '\'' || c == '"') quote = c;
                else if (c == '>') break;
            }
            if (end == html.length()) break;
            cursor = end + 1;
            String tag = html.substring(name, nameEnd).toLowerCase(Locale.ROOT);
            // Heading-looking text inside raw-text elements is not markup. Inserting tags there
            // would display the markers literally; quoted attributes and comments are skipped too.
            if (!closing && (tag.equals("textarea") || tag.equals("title") || tag.equals("xmp") || tag.equals("plaintext")
                    || tag.equals("script") || tag.equals("style") || tag.equals("noscript") || tag.equals("head")
                    || tag.equals("iframe") || tag.equals("noembed") || tag.equals("noframes"))) {
                if (tag.equals("plaintext")) break;
                Matcher close = Pattern.compile("(?is)</" + Pattern.quote(tag) + "\\s*>").matcher(html);
                if (!close.find(cursor)) break;
                cursor = close.end();
            } else if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                int level = tag.charAt(1) - '1';
                if (!closing) current = new HtmlHeading(cursor, level);
                else if (current != null && current.level == level) {
                    current.end = at;
                    headings.add(current);
                    current = null;
                }
            }
        }
        return headings;
    }

    /** The same normalization as normalize(), with all callback offsets mapped in one pass. */
    private static String normalizeHtml(String raw, ArrayList<HtmlBoundary> boundaries) {
        int first = 0, last = raw.length(), boundary = 0;
        while (first < last && htmlTrim(raw.charAt(first))) first++;
        while (last > first && htmlTrim(raw.charAt(last - 1))) last--;
        StringBuilder out = new StringBuilder(last - first);
        boolean space = false;
        int newlines = 0;
        for (int i = first; i < last; i++) {
            while (boundary < boundaries.size() && boundaries.get(boundary).raw <= i) boundaries.get(boundary++).normalized = out.length();
            char c = raw.charAt(i);
            if (c == '\uFEFF' || (c == '\n' && i > 0 && raw.charAt(i - 1) == '\r')) continue;
            if (c == '\r' || c == '\n') {
                space = false;
                if (newlines < 2) { out.append('\n'); newlines++; }
            } else if (c == ' ' || c == '\t' || c == '\u000B' || c == '\f' || c == '\u00A0' || c == '\u2007' || c == '\u202F') {
                if (newlines == 0) space = true;
            } else {
                if (space) out.append(' ');
                out.append(c);
                space = false;
                newlines = 0;
            }
        }
        while (boundary < boundaries.size()) boundaries.get(boundary++).normalized = out.length();
        return out.toString();
    }

    private static boolean htmlTrim(char c) {
        return c <= ' ' || c == '\uFEFF' || c == '\u00A0' || c == '\u2007' || c == '\u202F';
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ').replace("\uFEFF", "")
                .replaceAll("[\\t\\x0B\\f\\u2007\\u202F ]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static void checkText(String text) throws IOException {
        if (text.isEmpty()) throw new IOException("В книге не найден текст. Возможно, это скан или защищённый файл.");
        if (text.length() > MAX_TEXT_CHARS) throw new IOException("В книге слишком много текста");
    }
}
