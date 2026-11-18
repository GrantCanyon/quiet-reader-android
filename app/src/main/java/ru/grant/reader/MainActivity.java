package ru.grant.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small native Java reader. I/O and PDF rendering stay off the UI thread. */
public class MainActivity extends Activity {
    private static final int IMPORT_BOOK = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Integer> pageHistory = new ArrayList<>();
    private final Locale russian = new Locale("ru");
    private BookRepository repository;
    private SharedPreferences preferences;
    private List<BookRepository.Book> books = new ArrayList<>();
    private List<BookRepository.Chapter> chapters = new ArrayList<>();
    private String chapterLoadError = "";
    private BookRepository.Book current;
    private String text = "";
    private boolean pdf, busy, changingProgress, saveWarningShown, libraryWarningShown;
    private int position, pageEnd, pdfCount;
    private volatile int generation;
    private int fontSize, theme;
    private boolean serif, justify, immersive, restoreImmersive;
    private float lineHeight;
    private int background, paper, ink, muted, accent, border;

    // These two objects are opened, used and closed exclusively on worker.
    private PdfRenderer renderer;
    private ParcelFileDescriptor pdfDescriptor;

    private View root, shelfPanel, readerPanel, busyOverlay, pageHolder, readerHeader, readerFooter;
    private LinearLayout bookList;
    private EditText search;
    private TextView pageText, bookTitle, bookAuthor, pageInfo, count, busyMessage;
    private SeekBar progress;
    private PdfPageView pdfPage;
    private Button previous, next;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("reading", MODE_PRIVATE);
        fontSize = preferences.getInt("font_size", 21);
        theme = preferences.getInt("theme", 0);
        serif = preferences.getBoolean("serif", true);
        justify = preferences.getBoolean("justify", false);
        restoreImmersive = state != null && state.getBoolean("immersive", false);
        lineHeight = preferences.getFloat("line_height", 1.25f);
        repository = new BookRepository(this);
        setContentView(R.layout.activity_main);
        bindViews();
        configureInsets();
        configureActions();
        applyTheme();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBack);
        loadShelf(state == null ? null : state.getString("active_book"), state == null ? -1 : state.getInt("active_position", -1));
    }

    private void bindViews() {
        root = findViewById(R.id.root); shelfPanel = findViewById(R.id.shelf_panel);
        readerPanel = findViewById(R.id.reader_panel); busyOverlay = findViewById(R.id.busy_overlay);
        pageHolder = findViewById(R.id.page_holder); bookList = findViewById(R.id.book_list);
        search = findViewById(R.id.search); pageText = findViewById(R.id.page_text);
        bookTitle = findViewById(R.id.book_title); bookAuthor = findViewById(R.id.book_author);
        pageInfo = findViewById(R.id.page_info); count = findViewById(R.id.shelf_count);
        busyMessage = findViewById(R.id.busy_message); progress = findViewById(R.id.reading_progress);
        pdfPage = findViewById(R.id.pdf_page); previous = findViewById(R.id.previous_button);
        next = findViewById(R.id.next_button);
        readerHeader = findViewById(R.id.reader_header); readerFooter = findViewById(R.id.reader_footer);
    }

    private void configureInsets() {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.displayCutout() | (immersive ? 0 : WindowInsets.Type.systemBars());
                android.graphics.Insets bars = insets.getInsets(types);
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                if (immersive) {
                    android.view.DisplayCutout cutout = Build.VERSION.SDK_INT >= 28 ? insets.getDisplayCutout() : null;
                    view.setPadding(cutout == null ? 0 : cutout.getSafeInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetTop(),
                            cutout == null ? 0 : cutout.getSafeInsetRight(), cutout == null ? 0 : cutout.getSafeInsetBottom());
                } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void configureActions() {
        findViewById(R.id.import_button).setOnClickListener(v -> chooseBooks());
        findViewById(R.id.back_button).setOnClickListener(v -> showShelf());
        findViewById(R.id.settings_button).setOnClickListener(v -> showSettings());
        findViewById(R.id.contents_button).setOnClickListener(v -> showContents());
        previous.setOnClickListener(v -> turn(-1)); next.setOnClickListener(v -> turn(1));
        pageInfo.setOnClickListener(v -> goToPosition());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderShelf(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        search.setOnEditorActionListener((view, action, event) -> { hideKeyboard(); return true; });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                float portion = e.getX() / Math.max(1, pageText.getWidth());
                if (portion < 0.25f) turn(-1);
                else if (portion > 0.75f) turn(1);
                else pageText.performClick();
                return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent last, float vx, float vy) {
                if (first != null && Math.abs(last.getX() - first.getX()) > dp(45) && Math.abs(vx) > Math.abs(vy)) {
                    turn(vx < 0 ? 1 : -1); return true;
                }
                return false;
            }
        });
        pageText.setOnTouchListener((view, event) -> { gestures.onTouchEvent(event); return true; });
        pageText.setOnClickListener(v -> toggleReaderChrome());
        pdfPage.setNavigation(this::turn);
        pdfPage.setOnClickListener(v -> toggleReaderChrome());
        pageHolder.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (current != null && !busy && (r - l != or - ol || b - t != ob - ot)) {
                if (pdf) renderPdf(position); else showTextPage();
            }
        });
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || current == null) return;
                if (pdf) pageInfo.setText("Страница " + (value + 1) + " из " + pdfCount);
                else pageInfo.setText(String.format(russian, "%.1f %%", value / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { changingProgress = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                changingProgress = false;
                if (current == null || busy) return;
                if (pdf) { position = Math.min(Math.max(0, pdfCount - 1), bar.getProgress()); renderPdf(position); }
                else seekText((int)((long)text.length() * bar.getProgress() / 1000));
            }
        });
    }

    private void chooseBooks() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, IMPORT_BOOK); }
        catch (Exception e) { showError("На телефоне не найдено приложение для выбора файлов."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_BOOK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData selection = data.getClipData();
        if (selection != null) for (int i = 0; i < Math.min(30, selection.getItemCount()); i++) uris.add(selection.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        setBusy(true, "Добавляем книги…");
        worker.execute(() -> {
            int imported = 0;
            ArrayList<String> errors = new ArrayList<>();
            for (Uri uri : uris) {
                try { repository.importBook(uri); imported++; }
                catch (OutOfMemoryError memory) {
                    errors.add("Не хватило памяти для этой книги. Попробуйте добавить её отдельно или выбрать файл меньшего размера.");
                    break;
                }
                catch (Exception e) { errors.add(friendly(e)); }
            }
            final int successful = imported;
            final List<BookRepository.Book> updated = repository.list();
            onUi(() -> {
                setBusy(false, ""); books = updated; renderShelf();
                if (successful > 0) Toast.makeText(this, "Добавлено книг: " + successful, Toast.LENGTH_SHORT).show();
                if (!errors.isEmpty()) showError(String.join("\n\n", errors));
            });
        });
    }

    private void loadShelf(String restoreId, int restorePosition) {
        worker.execute(() -> {
            List<BookRepository.Book> loaded = repository.list();
            String warning = repository.getLastError();
            onUi(() -> {
                books = loaded; renderShelf();
                if (!warning.isEmpty() && !libraryWarningShown) { libraryWarningShown = true; showError(warning); }
                if (restoreId != null) for (BookRepository.Book book : books) {
                    if (restoreId.equals(book.id)) {
                        if (restorePosition >= 0) book.position = restorePosition;
                        openBook(book); break;
                    }
                }
            });
        });
    }

    private void renderShelf() {
        if (bookList == null) return;
        bookList.removeAllViews();
        ArrayList<BookRepository.Book> ordered = new ArrayList<>(books);
        Collections.sort(ordered, (left, right) -> Long.compare(right.importedAt, left.importedAt));
        String query = search.getText().toString().trim().toLowerCase(russian);
        int visible = 0;
        for (BookRepository.Book book : ordered) {
            String searchable = safe(book.title) + " " + safe(book.author);
            if (!searchable.toLowerCase(russian).contains(query)) continue;
            visible++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(16), dp(8), dp(16));
            row.setBackground(shape(paper, 18, border));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(12); row.setLayoutParams(rowParams);
            TextView cover = new TextView(this);
            cover.setText(book.format == null ? "КНИГА" : book.format.toUpperCase(Locale.ROOT));
            cover.setGravity(Gravity.CENTER); cover.setTextColor(accent); cover.setTextSize(10);
            cover.setTypeface(Typeface.DEFAULT, Typeface.BOLD); cover.setBackground(shape(background, 9, 0));
            row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(68)));
            LinearLayout details = new LinearLayout(this); details.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1); detailsParams.setMargins(dp(14), 0, dp(4), 0);
            row.addView(details, detailsParams);
            TextView title = label(safe(book.title).isEmpty() ? "Книга без названия" : book.title, 17, ink);
            title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); title.setMaxLines(3);
            details.addView(title);
            if (!safe(book.author).isEmpty()) {
                TextView author = label(book.author, 12, muted); author.setMaxLines(2); details.addView(author);
            }
            TextView status = label(book.position > 0 ? "Продолжить чтение  →" : "Начать чтение  →", 12, accent);
            status.setPadding(0, dp(9), 0, 0); details.addView(status);
            Button menu = new Button(this); menu.setText("⋮"); menu.setTextSize(23); menu.setTextColor(muted);
            menu.setBackgroundColor(Color.TRANSPARENT); menu.setPadding(0, 0, 0, 0);
            menu.setContentDescription("Действия с книгой «" + safe(book.title) + "»");
            row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
            menu.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(dialogContext(), menu);
                popup.getMenu().add("Открыть").setOnMenuItemClickListener(item -> { openBook(book); return true; });
                popup.getMenu().add("Удалить из библиотеки").setOnMenuItemClickListener(item -> { confirmDelete(book); return true; });
                popup.show();
            });
            row.setOnClickListener(v -> openBook(book)); row.setFocusable(true);
            row.setContentDescription(safe(book.title) + ". " + safe(book.author) + ". Открыть книгу");
            bookList.addView(row);
        }
        count.setText(query.isEmpty() ? "МОИ КНИГИ · " + books.size() : "НАЙДЕНО · " + visible);
        findViewById(R.id.empty_state).setVisibility(visible == 0 ? View.VISIBLE : View.GONE);
        ((TextView)findViewById(R.id.empty_title)).setText(query.isEmpty() ? "Первая книга — начало" : "Книга не найдена");
        ((TextView)findViewById(R.id.empty_message)).setText(query.isEmpty()
                ? "Добавьте книгу с телефона.\nEPUB, FB2, TXT, HTML или PDF.\nМесто чтения сохранится автоматически."
                : "Попробуйте другое название\nили имя автора.");
    }

    private void confirmDelete(BookRepository.Book book) {
        new AlertDialog.Builder(dialogContext()).setTitle("Удалить книгу?")
                .setMessage("«" + safe(book.title) + "» будет удалена из библиотеки. Исходный файл на телефоне сохранится.")
                .setNegativeButton("Оставить", null).setPositiveButton("Удалить", (dialog, which) -> worker.execute(() -> {
                    try {
                        repository.delete(book);
                        List<BookRepository.Book> updated = repository.list();
                        onUi(() -> { books = updated; renderShelf(); });
                    } catch (Exception e) { onUi(() -> showError(friendly(e))); }
                })).show();
    }

    private void openBook(BookRepository.Book book) {
        if (busy) return;
        hideKeyboard();
        final int token = ++generation;
        current = book; position = Math.max(0, book.position); pageHistory.clear(); saveWarningShown = false;
        pdf = "pdf".equalsIgnoreCase(book.format) || safe(book.fileName).toLowerCase(Locale.ROOT).endsWith(".pdf");
        text = ""; pdfCount = 0; chapters = new ArrayList<>(); chapterLoadError = "";
        findViewById(R.id.contents_button).setContentDescription(pdf ? "Перейти к странице PDF" : "Оглавление книги");
        bookTitle.setText(safe(book.title)); bookAuthor.setText(safe(book.author).isEmpty() ? safe(book.format).toUpperCase(Locale.ROOT) : book.author);
        shelfPanel.setVisibility(View.GONE); readerPanel.setVisibility(View.VISIBLE);
        setReadingChromeVisible(!restoreImmersive); restoreImmersive = false;
        pageText.setVisibility(pdf ? View.GONE : View.VISIBLE); pdfPage.setVisibility(pdf ? View.VISIBLE : View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setBusy(true, "Открываем книгу…");
        final boolean openingPdf = pdf;
        worker.execute(() -> {
            try {
                closePdfOnWorker();
                if (openingPdf) {
                    pdfDescriptor = ParcelFileDescriptor.open(repository.fileFor(book), ParcelFileDescriptor.MODE_READ_ONLY);
                    renderer = new PdfRenderer(pdfDescriptor);
                    final int pages = renderer.getPageCount();
                    if (pages == 0) throw new IOException("В этом PDF нет страниц.");
                    onUi(() -> {
                        if (token != generation) return;
                        pdfCount = pages; position = Math.min(position, pages - 1); setBusy(false, "");
                        pageHolder.post(() -> renderPdf(position));
                        showGestureHint();
                    });
                } else {
                    final String loaded = repository.readText(book);
                    if (loaded == null || loaded.trim().isEmpty()) throw new IOException("В книге не найден текст. Для сканированного документа используйте PDF.");
                    // A missing or damaged contents cache should not prevent reading.
                    List<BookRepository.Chapter> loadedChapters;
                    String contentsError = "";
                    try { loadedChapters = repository.readChapters(book); }
                    catch (Exception failure) { loadedChapters = new ArrayList<>(); contentsError = friendly(failure); }
                    final List<BookRepository.Chapter> bookChapters = loadedChapters;
                    final String contentsWarning = contentsError;
                    onUi(() -> {
                        if (token != generation) return;
                        // Cache migration may have repaired the old text and remapped
                        // the book's saved anchor while readText ran on the worker.
                        text = loaded; position = Math.min(Math.max(0, book.position), Math.max(0, text.length() - 1));
                        chapters = bookChapters == null ? new ArrayList<>() : new ArrayList<>(bookChapters);
                        chapterLoadError = contentsWarning;
                        setBusy(false, ""); pageText.post(this::showTextPage);
                        showGestureHint();
                    });
                }
            } catch (Exception | OutOfMemoryError e) {
                closePdfOnWorker();
                String message = openingPdf ? "Этот PDF не удалось прочитать. Возможно, он повреждён или защищён паролем. Попробуйте открыть незашифрованную копию." : friendly(e);
                onUi(() -> {
                    if (token != generation) return;
                    setBusy(false, ""); showShelf(); showError(message);
                });
            }
        });
    }

    private void turn(int direction) {
        if (current == null || busy || changingProgress) return;
        if (pdf) {
            int wanted = position + direction;
            if (wanted >= 0 && wanted < pdfCount) { position = wanted; renderPdf(position); }
            return;
        }
        if (text.isEmpty()) return;
        if (direction > 0 && pageEnd < text.length()) {
            pageHistory.add(position); position = pageEnd;
        } else if (direction < 0 && position > 0) {
            if (!pageHistory.isEmpty()) position = pageHistory.remove(pageHistory.size() - 1);
            else position = previousTextPage(position);
        } else return;
        showTextPage();
    }

    private void showTextPage() {
        if (current == null || pdf || text.isEmpty() || pageText.getWidth() <= 0 || pageText.getHeight() <= 0) return;
        position = safeOffset(position);
        pageEnd = endOfPage(position);
        // A page may end with a paragraph separator. It belongs to the saved range,
        // but need not create an extra empty line at the bottom of the displayed page.
        int visibleEnd = pageEnd;
        while (visibleEnd > position && text.charAt(visibleEnd - 1) == '\n') visibleEnd--;
        pageText.setText(text.substring(position, visibleEnd));
        previous.setEnabled(position > 0); next.setEnabled(pageEnd < text.length());
        updateProgressDisplay(); savePosition();
    }

    /** Layout only a bounded window; even a large book never creates one giant layout. */
    private int endOfPage(int start) {
        int width = Math.max(1, pageText.getWidth() - pageText.getPaddingLeft() - pageText.getPaddingRight());
        int height = Math.max(1, pageText.getHeight() - pageText.getPaddingTop() - pageText.getPaddingBottom());
        int limit = Math.min(text.length(), start + 16000);
        if (limit < text.length() && limit > start && Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
        CharSequence chunk = text.subSequence(start, limit);
        StaticLayout layout = StaticLayout.Builder.obtain(chunk, 0, chunk.length(), pageText.getPaint(), width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true)
                .setLineSpacing(0, lineHeight).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setJustificationMode(justify ? Layout.JUSTIFICATION_MODE_INTER_WORD : Layout.JUSTIFICATION_MODE_NONE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).build();
        int line = 0;
        while (line + 1 < layout.getLineCount() && layout.getLineBottom(line + 1) <= height) line++;
        return Math.min(text.length(), Math.max(start + 1, start + layout.getLineEnd(line)));
    }

    private int previousTextPage(int end) {
        int cursor = safeOffset(Math.max(0, end - 16000));
        int previousStart = cursor;
        while (cursor < end) {
            int nextStart = endOfPage(cursor);
            if (nextStart >= end || nextStart <= cursor) return cursor;
            previousStart = cursor; cursor = nextStart;
        }
        return previousStart;
    }

    private int safeOffset(int offset) {
        offset = Math.min(Math.max(0, offset), Math.max(0, text.length() - 1));
        if (offset > 0 && Character.isLowSurrogate(text.charAt(offset)) && Character.isHighSurrogate(text.charAt(offset - 1))) offset--;
        return offset;
    }

    private void seekText(int offset) {
        if (text.isEmpty()) return;
        offset = safeOffset(offset);
        // A jump starts at a word boundary; regular page turns preserve every character.
        int boundary = offset;
        while (boundary > 0 && offset - boundary < 150 && !Character.isWhitespace(text.charAt(boundary - 1))) boundary--;
        position = boundary; pageHistory.clear(); showTextPage();
    }

    private void showContents() {
        if (current == null || busy) return;
        if (pdf) { goToPosition(); return; }
        if (chapters.isEmpty()) {
            String message = chapterLoadError.isEmpty()
                    ? "В этом файле не найдено оглавление. Можно перейти к нужному проценту книги."
                    : "Оглавление не удалось загрузить. Текст книги доступен; можно перейти к нужному проценту.";
            new AlertDialog.Builder(dialogContext()).setTitle("Оглавление").setMessage(message)
                    .setNegativeButton("Закрыть", null)
                    .setPositiveButton("Перейти к позиции", (dialog, which) -> goToPosition()).show();
            return;
        }
        Context context = dialogContext();
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 0);
        TextView book = label(safe(current.title), 13, muted); book.setMaxLines(2);
        book.setPadding(dp(4), 0, dp(4), dp(12)); body.addView(book);
        EditText filter = new EditText(context); filter.setSingleLine(true); filter.setTextSize(15);
        filter.setHint("Найти книгу, часть или главу"); filter.setTextColor(ink); filter.setHintTextColor(muted);
        filter.setPadding(dp(14), 0, dp(14), 0); filter.setBackground(shape(background, 10, border));
        filter.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        filter.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        body.addView(filter, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView summary = label("", 12, muted); summary.setPadding(dp(4), dp(10), dp(4), dp(8)); body.addView(summary);
        ListView list = new ListView(context); list.setDivider(new ColorDrawable(border)); list.setDividerHeight(dp(1));
        list.setSelector(new ColorDrawable(theme == 2 ? 0xFF3A3A3A : border));
        list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(8));
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView noResults = label("Ничего не найдено. Попробуйте другое название.", 14, muted);
        noResults.setGravity(Gravity.CENTER); noResults.setPadding(dp(16), dp(24), dp(16), dp(24));
        noResults.setVisibility(View.GONE); body.addView(noResults); list.setEmptyView(noResults);
        BookRepository.Chapter active = currentChapter();
        ChapterAdapter adapter = new ChapterAdapter(chapters, active); list.setAdapter(adapter);
        summary.setText("Разделов и глав: " + adapter.getCount());
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("Оглавление").setView(body)
                .setNegativeButton("Закрыть", null).create();
        list.setOnItemClickListener((parent, view, index, id) -> {
            BookRepository.Chapter selected = adapter.getItem(index);
            // Heading offsets already point to the exact UTF-16 character in the
            // normalized text. Unlike percentage seeking, no word-boundary search.
            position = safeOffset(selected.offset); pageHistory.clear(); showTextPage();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(filter.getWindowToken(), 0);
            dialog.dismiss();
        });
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                adapter.filter(value.toString());
                summary.setText(value.toString().trim().isEmpty() ? "Разделов и глав: " + adapter.getCount() : "Найдено: " + adapter.getCount());
                list.setSelection(0);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        filter.setOnEditorActionListener((view, action, event) -> {
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(filter.getWindowToken(), 0);
            list.requestFocus(); return true;
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                int height = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
                int width = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
                dialog.getWindow().setLayout(Math.min(dp(600), width - dp(24)), Math.min(dp(680), Math.round(height * 0.86f)));
                dialog.getWindow().setBackgroundDrawable(shape(paper, 16, 0));
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
            if (active != null) list.post(() -> list.setSelection(Math.max(0, adapter.indexOf(active) - 1)));
        });
        dialog.show();
    }

    private BookRepository.Chapter currentChapter() {
        BookRepository.Chapter found = null;
        for (BookRepository.Chapter chapter : chapters) {
            if (chapter.offset <= position && (found == null || chapter.offset >= found.offset)) found = chapter;
        }
        return found;
    }

    private final class ChapterAdapter extends BaseAdapter {
        private final List<BookRepository.Chapter> source;
        private final ArrayList<BookRepository.Chapter> visible = new ArrayList<>();
        private final BookRepository.Chapter active;
        ChapterAdapter(List<BookRepository.Chapter> source, BookRepository.Chapter active) {
            this.source = source; this.active = active; visible.addAll(source);
        }
        void filter(String query) {
            String needle = foldTitle(query.trim()); visible.clear();
            for (BookRepository.Chapter chapter : source) if (needle.isEmpty() || foldTitle(chapter.title).contains(needle)) visible.add(chapter);
            notifyDataSetChanged();
        }
        int indexOf(BookRepository.Chapter chapter) { return visible.indexOf(chapter); }
        @Override public int getCount() { return visible.size(); }
        @Override public BookRepository.Chapter getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int index, View reuse, android.view.ViewGroup parent) {
            LinearLayout row;
            TextView title, detail;
            if (reuse instanceof LinearLayout) {
                row = (LinearLayout)reuse; title = (TextView)row.getChildAt(0); detail = (TextView)row.getChildAt(1);
            } else {
                row = new LinearLayout(parent.getContext()); row.setOrientation(LinearLayout.VERTICAL);
                title = label("", 15, ink); title.setMaxLines(3); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setLineSpacing(dp(2), 1); row.addView(title);
                detail = label("", 11, muted); detail.setPadding(0, dp(5), 0, 0); row.addView(detail);
            }
            BookRepository.Chapter chapter = getItem(index);
            int level = Math.max(0, Math.min(2, chapter.level));
            boolean selected = chapter == active;
            row.setPadding(dp(12 + level * 14), dp(level == 0 ? 17 : 12), dp(12), dp(level == 0 ? 17 : 12));
            row.setBackgroundColor(selected ? (theme == 2 ? 0xFF343434 : border) : chapter.level == 0 ? background : Color.TRANSPARENT);
            title.setText(safe(chapter.title)); title.setTextColor(ink); title.setTextSize(chapter.level == 0 ? 17 : 15);
            title.setTypeface(Typeface.DEFAULT, chapter.level == 0 || selected ? Typeface.BOLD : Typeface.NORMAL);
            String percent = String.format(russian, "%.1f %%", Math.min(100f, Math.max(0, chapter.offset * 100f / Math.max(1, text.length()))));
            detail.setText((selected ? "ВЫ ЧИТАЕТЕ · " : chapter.level == 0 ? "РАЗДЕЛ · " : "") + percent);
            detail.setTextColor(selected ? accent : muted);
            row.setContentDescription(safe(chapter.title) + (selected ? ". Текущий раздел. " : ". ") + percent);
            return row;
        }
    }

    private String foldTitle(String value) { return safe(value).toLowerCase(russian).replace('ё', 'е'); }
    private Context dialogContext() { return new ContextThemeWrapper(this, theme == 2 ? R.style.ReaderDialogNight : R.style.ReaderDialogDay); }

    private void renderPdf(int index) {
        if (current == null || !pdf || pdfCount <= 0 || pdfPage.getWidth() <= 0) return;
        final int token = ++generation;
        final int requested = Math.min(Math.max(0, index), pdfCount - 1);
        position = requested;
        previous.setEnabled(requested > 0); next.setEnabled(requested + 1 < pdfCount);
        updateProgressDisplay(); savePosition();
        // A serial queue prevents concurrent access to PdfRenderer or a page after closing it.
        worker.execute(() -> {
            Bitmap image = null;
            try {
                if (token != generation || renderer == null) return;
                try (PdfRenderer.Page page = renderer.openPage(requested)) {
                    int width = Math.min(2048, Math.max(800, getResources().getDisplayMetrics().widthPixels * 2));
                    float ratio = (float)page.getHeight() / Math.max(1, page.getWidth());
                    int height = Math.max(1, Math.round(width * ratio));
                    if (height > 4096) { width = Math.max(1, Math.round(width * 4096f / height)); height = 4096; }
                    image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    image.eraseColor(Color.WHITE);
                    page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                final Bitmap rendered = image;
                onUi(() -> {
                    if (token != generation || current == null || !pdf) { rendered.recycle(); return; }
                    pdfPage.setBitmap(rendered);
                });
            } catch (Exception | OutOfMemoryError e) {
                if (image != null && !image.isRecycled()) image.recycle();
                onUi(() -> { if (token == generation) showError("Не удалось показать страницу PDF. Попробуйте другую страницу или файл меньшего размера."); });
            }
        });
    }

    private void updateProgressDisplay() {
        if (changingProgress) return;
        if (pdf) {
            progress.setMax(Math.max(0, pdfCount - 1)); progress.setProgress(position); progress.setEnabled(pdfCount > 1);
            pageInfo.setText("Страница " + (position + 1) + " из " + pdfCount + "  ·  перейти");
        } else {
            progress.setMax(1000); progress.setEnabled(text.length() > 1);
            int amount = text.isEmpty() ? 0 : (int)((long)position * 1000 / text.length());
            progress.setProgress(amount);
            String percent = pageEnd >= text.length() ? "100 %" : String.format(russian, "%.1f %%", amount / 10f);
            pageInfo.setText(percent + "  ·  перейти");
        }
    }

    private void goToPosition() {
        if (current == null || busy) return;
        Context context = dialogContext();
        EditText input = new EditText(context);
        input.setInputType(pdf ? android.text.InputType.TYPE_CLASS_NUMBER : android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setSingleLine(true);
        input.setText(pdf ? String.valueOf(position + 1) : String.format(Locale.ROOT, "%.1f", text.isEmpty() ? 0 : position * 100f / text.length()));
        input.selectAll();
        LinearLayout container = new LinearLayout(this); container.setPadding(dp(24), dp(8), dp(24), 0); container.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(pdf ? "Номер страницы · 1–" + pdfCount : "Позиция в книге · 0–100 %")
                .setView(container).setNegativeButton("Отмена", null).setPositiveButton("Перейти", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                float value = Float.parseFloat(input.getText().toString().replace(',', '.'));
                if (!Float.isFinite(value) || value < (pdf ? 1 : 0) || value > (pdf ? pdfCount : 100)) throw new NumberFormatException();
                if (pdf) { position = (int)value - 1; renderPdf(position); }
                else seekText((int)(text.length() * (value / 100f)));
                hideKeyboard(); dialog.dismiss();
            } catch (NumberFormatException invalid) { input.setError(pdf ? "Укажите существующую страницу" : "Укажите число от 0 до 100"); }
        }));
        dialog.show();
    }

    private void showSettings() {
        Context context = dialogContext();
        LinearLayout options = new LinearLayout(context); options.setOrientation(LinearLayout.VERTICAL); options.setPadding(dp(24), dp(8), dp(24), 0);
        options.addView(label("Оформление", 14, ink));
        RadioGroup themes = new RadioGroup(context); themes.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < 3; i++) { RadioButton radio = new RadioButton(context); radio.setId(200 + i); radio.setText(new String[]{"Свет", "Сепия", "Ночь"}[i]); radio.setTextSize(13); themes.addView(radio, new RadioGroup.LayoutParams(0, -2, 1)); }
        themes.check(200 + theme); options.addView(themes);
        TextView sizeLabel = label("Размер текста · " + fontSize, 14, ink); options.addView(sizeLabel);
        SeekBar size = new SeekBar(context); size.setMax(20); size.setProgress(fontSize - 14); size.setEnabled(!pdf); options.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { sizeLabel.setText("Размер текста · " + (14 + value)); }
            public void onStartTrackingTouch(SeekBar bar) { } public void onStopTrackingTouch(SeekBar bar) { }
        });
        TextView spacingLabel = label("Межстрочный интервал", 14, ink); options.addView(spacingLabel);
        SeekBar spacing = new SeekBar(context); spacing.setMax(12); spacing.setProgress(Math.round((lineHeight - 1.1f) * 20)); spacing.setEnabled(!pdf); options.addView(spacing);
        RadioGroup fonts = new RadioGroup(context); fonts.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton bookFont = new RadioButton(context); bookFont.setId(301); bookFont.setText("Книжный"); fonts.addView(bookFont);
        RadioButton systemFont = new RadioButton(context); systemFont.setId(302); systemFont.setText("Системный"); fonts.addView(systemFont);
        fonts.check(serif ? 301 : 302); bookFont.setEnabled(!pdf); systemFont.setEnabled(!pdf); options.addView(fonts);
        options.addView(label("Выравнивание текста", 14, ink));
        RadioGroup alignment = new RadioGroup(context); alignment.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton left = new RadioButton(context); left.setId(401); left.setText("Слева"); alignment.addView(left);
        RadioButton full = new RadioButton(context); full.setId(402); full.setText("По ширине"); alignment.addView(full);
        alignment.check(justify ? 402 : 401); left.setEnabled(!pdf); full.setEnabled(!pdf); options.addView(alignment);
        TextView hint = label(pdf ? "PDF сохраняет исходное оформление. Увеличивайте страницу двумя пальцами или двойным касанием."
                : "Центр страницы скрывает и возвращает панели. Края и свайп листают книгу. Лишние пробелы убираются автоматически; для стихов удобно выравнивание слева.", 12, muted);
        hint.setPadding(0, dp(12), 0, dp(8)); options.addView(hint);
        ScrollView settingsScroll = new ScrollView(this); settingsScroll.addView(options);
        new AlertDialog.Builder(context).setTitle("Как вам удобно читать").setView(settingsScroll).setNegativeButton("Отмена", null)
                .setPositiveButton("Применить", (dialog, which) -> {
                    theme = Math.max(0, themes.getCheckedRadioButtonId() - 200);
                    if (!pdf) { fontSize = 14 + size.getProgress(); lineHeight = 1.1f + spacing.getProgress() / 20f; serif = fonts.getCheckedRadioButtonId() == 301; justify = alignment.getCheckedRadioButtonId() == 402; }
                    preferences.edit().putInt("theme", theme).putInt("font_size", fontSize).putFloat("line_height", lineHeight).putBoolean("serif", serif).putBoolean("justify", justify).apply();
                    pageHistory.clear(); applyTheme(); if (!pdf) pageText.post(this::showTextPage);
                }).show();
    }

    private void applyTheme() {
        if (theme == 2) { background = 0xFF151515; paper = 0xFF202020; ink = 0xFFDDD8CE; muted = 0xFF9C9C9C; accent = 0xFFD0D0D0; border = 0xFF343434; }
        else if (theme == 1) { background = Color.rgb(232, 220, 197); paper = Color.rgb(245, 234, 212); ink = Color.rgb(72, 57, 39); muted = Color.rgb(126, 105, 78); accent = Color.rgb(114, 85, 50); border = Color.rgb(219, 202, 171); }
        else { background = Color.rgb(246, 244, 238); paper = Color.rgb(255, 254, 250); ink = Color.rgb(32, 51, 47); muted = Color.rgb(109, 121, 115); accent = Color.rgb(57, 118, 109); border = Color.rgb(228, 232, 222); }
        root.setBackgroundColor(immersive ? paper : background); pageHolder.setBackground(shape(paper, immersive ? 0 : 12, 0));
        ((TextView)findViewById(R.id.shelf_heading)).setTextColor(ink);
        ((TextView)findViewById(R.id.shelf_subtitle)).setTextColor(muted);
        ((TextView)findViewById(R.id.eyebrow)).setTextColor(accent);
        ((TextView)findViewById(R.id.empty_title)).setTextColor(ink);
        ((TextView)findViewById(R.id.empty_message)).setTextColor(muted);
        count.setTextColor(muted); bookTitle.setTextColor(ink); bookAuthor.setTextColor(muted); pageInfo.setTextColor(muted);
        search.setTextColor(ink); search.setHintTextColor(muted); search.setBackground(shape(paper, 14, border));
        pageText.setTextColor(ink); pageText.setTextSize(fontSize); pageText.setTypeface(serif ? Typeface.SERIF : Typeface.SANS_SERIF);
        pageText.setLineSpacing(0, lineHeight); pageText.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        pageText.setTextLocale(russian);
        pageText.setJustificationMode(justify ? Layout.JUSTIFICATION_MODE_INTER_WORD : Layout.JUSTIFICATION_MODE_NONE);
        pageText.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        progress.setProgressTintList(ColorStateList.valueOf(accent)); progress.setThumbTintList(ColorStateList.valueOf(accent));
        for (int id : new int[]{R.id.back_button, R.id.contents_button, R.id.settings_button, R.id.previous_button, R.id.next_button}) {
            Button button = findViewById(id); button.setTextColor(accent); button.setBackgroundTintList(ColorStateList.valueOf(background));
        }
        Button importer = findViewById(R.id.import_button); importer.setBackgroundTintList(ColorStateList.valueOf(accent)); importer.setTextColor(theme == 2 ? background : Color.WHITE);
        busyOverlay.setBackgroundColor((background & 0x00ffffff) | 0xf0000000); busyMessage.setTextColor(ink);
        ((android.widget.ProgressBar)findViewById(R.id.loading_spinner)).setIndeterminateTintList(ColorStateList.valueOf(accent));
        getWindow().setStatusBarColor(background); getWindow().setNavigationBarColor(background);
        updateSystemBars();
        renderShelf();
    }

    private void toggleReaderChrome() {
        if (current == null || busy) return;
        setReadingChromeVisible(immersive);
    }

    /** Resize the reading surface around the same first visible character. */
    private void setReadingChromeVisible(boolean visible) {
        immersive = !visible;
        readerHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        readerFooter.setVisibility(visible ? View.VISIBLE : View.GONE);
        progress.setVisibility(visible ? View.VISIBLE : View.GONE);
        readerPanel.setPadding(0, visible ? dp(4) : 0, 0, 0);
        LinearLayout.LayoutParams pageParams = (LinearLayout.LayoutParams)pageHolder.getLayoutParams();
        pageParams.setMargins(visible ? dp(12) : 0, 0, visible ? dp(12) : 0, 0);
        pageHolder.setLayoutParams(pageParams);
        pageText.setPadding(dp(visible ? 18 : 22), dp(visible ? 18 : 16), dp(visible ? 18 : 22), dp(visible ? 18 : 16));
        if (Build.VERSION.SDK_INT >= 30) pageText.setStateDescription(visible
                ? "Касание центра скрывает панели; края листают страницы."
                : "Полный экран. Касание центра возвращает панели; края листают страницы.");
        pageHistory.clear();
        root.setBackgroundColor(visible ? background : paper);
        pageHolder.setBackground(shape(paper, visible ? 12 : 0, 0));
        updateSystemBars(); root.requestApplyInsets();
        if (!pdf && !text.isEmpty()) pageText.post(this::showTextPage);
    }

    private void updateSystemBars() {
        View decor = getWindow().getDecorView();
        int flags = theme == 2 ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (Build.VERSION.SDK_INT >= 30) {
            decor.setSystemUiVisibility(flags);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (immersive) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            if (immersive) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            decor.setSystemUiVisibility(flags);
        }
    }

    private void showGestureHint() {
        if (preferences.getBoolean("center_tap_hint", false)) return;
        preferences.edit().putBoolean("center_tap_hint", true).apply();
        Toast.makeText(this, "Касание центра — полный экран. Края и свайп — следующая или предыдущая страница.", Toast.LENGTH_LONG).show();
    }

    private void handleBack() {
        if (current != null && immersive) setReadingChromeVisible(true);
        else if (current != null) showShelf();
        else finish();
    }

    private void savePosition() {
        if (current == null) return;
        BookRepository.Book book = current; int saved = position;
        preferences.edit().putString("last_book", book.id).apply();
        if (!worker.isShutdown()) worker.execute(() -> {
            try { repository.updateProgress(book, saved); }
            catch (Exception failure) { onUi(() -> {
                if (!saveWarningShown) { saveWarningShown = true; showError("Место чтения не удалось сохранить. Проверьте свободное место на телефоне."); }
            }); }
        });
    }

    private void showShelf() {
        setReadingChromeVisible(true);
        savePosition(); generation++; current = null; text = ""; chapters.clear(); chapterLoadError = ""; pageHistory.clear();
        setBusy(false, ""); pdfPage.clear(); shelfPanel.setVisibility(View.VISIBLE); readerPanel.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!worker.isShutdown()) worker.execute(this::closePdfOnWorker);
        loadShelf(null, -1);
    }

    private void closePdfOnWorker() {
        if (renderer != null) { try { renderer.close(); } catch (Exception ignored) { } renderer = null; }
        if (pdfDescriptor != null) { try { pdfDescriptor.close(); } catch (IOException ignored) { } pdfDescriptor = null; }
    }

    private void setBusy(boolean value, String message) {
        busy = value; busyOverlay.setVisibility(value ? View.VISIBLE : View.GONE); busyMessage.setText(message);
    }
    private void showError(String message) {
        if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(dialogContext()).setTitle("Обратите внимание").setMessage(message).setPositiveButton("Понятно", null).show();
    }
    private String friendly(Throwable error) {
        if (error instanceof OutOfMemoryError) return "Не хватило памяти для этой книги. Попробуйте открыть её отдельно или выбрать файл меньшего размера.";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Файл не удалось прочитать. Проверьте, что он не повреждён и доступен на телефоне." : message;
    }
    private void onUi(Runnable action) { runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) action.run(); }); }
    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0); focus.clearFocus(); }
    }
    private TextView label(String value, int size, int color) { TextView label = new TextView(this); label.setText(value); label.setTextSize(size); label.setTextColor(color); return label; }
    private GradientDrawable shape(int fill, int radius, int stroke) { GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); if (stroke != 0) shape.setStroke(dp(1), stroke); return shape; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String text) { return text == null ? "" : text; }

    @Override protected void onPause() { savePosition(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (current != null) { state.putString("active_book", current.id); state.putInt("active_position", position); }
        state.putBoolean("immersive", immersive);
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { handleBack(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && immersive && current != null) updateSystemBars();
    }
    @Override protected void onDestroy() {
        generation++; savePosition(); pdfPage.clear();
        worker.execute(this::closePdfOnWorker); worker.shutdown(); super.onDestroy();
    }
}
