package android.text;

import org.xml.sax.XMLReader;

/** Compile-only boundary. CharSequence stands in for Editable; no HTML is rendered on the host. */
public final class Html {
    public static final int FROM_HTML_MODE_LEGACY = 0;
    public interface TagHandler {
        void handleTag(boolean opening, String tag, CharSequence output, XMLReader reader);
    }
    public static CharSequence fromHtml(String source, int flags) {
        throw new UnsupportedOperationException("HTML requires Android instrumentation");
    }
    public static CharSequence fromHtml(String source, int flags, Object imageGetter, TagHandler tagHandler) {
        throw new UnsupportedOperationException("HTML requires Android instrumentation");
    }
}
