package com.biliaudio.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class LibraryRows extends ListView {
    private final AudioUi.Palette palette;
    private final Rows rows = new Rows();
    private List<BookSections.Section> books = Collections.emptyList();
    private BookSections.Section book;
    private boolean selecting;
    private BookSections.Entry currentEpisode;
    private Set<AudioLibrary.Part> selected = Collections.emptySet();

    LibraryRows(Context context, AudioUi.Palette palette, Consumer<BookSections.Section> openBook,
                     Consumer<BookSections.Entry> openFile) {
        super(context);
        this.palette = palette;
        setPadding(dp(20), 0, dp(20), dp(12));
        setClipToPadding(false);
        setDivider(new ColorDrawable(palette.outline()));
        setDividerHeight(dp(1));
        setAdapter(rows);
        setOnItemClickListener((parent, view, position, id) -> {
            if (book == null && openBook != null) {
                openBook.accept(books.get(position));
            } else if (book != null) {
                openFile.accept(book.entries().get(position));
            }
        });
    }

    void showBooks(List<BookSections.Section> value, boolean resetScroll) {
        if (book != null || !books.equals(value)) {
            book = null;
            books = value;
            rows.notifyDataSetChanged();
        }
        if (resetScroll) {
            setSelection(0);
        }
    }

    void showFiles(BookSections.Section value, boolean selecting, Set<AudioLibrary.Part> selected,
                   boolean resetScroll) {
        showEntries(value, selecting, selected, null, resetScroll);
    }

    void showEpisodes(BookSections.Section value, BookSections.Entry currentEpisode, boolean resetScroll) {
        showEntries(value, false, Collections.emptySet(), currentEpisode, resetScroll);
    }

    private void showEntries(BookSections.Section value, boolean selecting, Set<AudioLibrary.Part> selected,
                             BookSections.Entry currentEpisode, boolean resetScroll) {
        if (!Objects.equals(book, value) || this.selecting != selecting || !this.selected.equals(selected)
                || !Objects.equals(this.currentEpisode, currentEpisode)) {
            book = value;
            this.selecting = selecting;
            this.currentEpisode = currentEpisode;
            this.selected = new HashSet<>(selected);
            rows.notifyDataSetChanged();
        }
        if (resetScroll) {
            setSelection(0);
        }
    }

    private int dp(int value) {
        return AudioUi.dp(getContext(), value);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return book == null ? books.size() : book.entries().size(); }
        @Override public Object getItem(int position) {
            return book == null ? books.get(position) : book.entries().get(position);
        }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return book == null ? 0 : 1; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (book == null) {
                BookRow row = recycled instanceof BookRow old ? old : new BookRow();
                row.bind(books.get(position));
                return row;
            }
            FileRow row = recycled instanceof FileRow old ? old : new FileRow();
            row.bind(book.entries().get(position));
            return row;
        }
    }

    private final class BookRow extends LinearLayout {
        private final ImageView cover = new ImageView(getContext());
        private final TextView title = text(17, palette.paper());
        private final TextView metadata = text(13, palette.muted());
        private String artwork;

        BookRow() {
            super(LibraryRows.this.getContext());
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(80));
            setPadding(0, dp(12), 0, dp(12));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(AudioUi.card(getContext(), palette.artwork(), 8));
            cover.setClipToOutline(true);
            addView(cover, new LayoutParams(dp(54), dp(54)));
            LinearLayout labels = new LinearLayout(getContext());
            labels.setOrientation(VERTICAL);
            labels.setPadding(dp(14), 0, dp(12), 0);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            metadata.setPadding(0, dp(4), 0, 0);
            labels.addView(metadata);
            addView(labels, new LayoutParams(0, -2, 1));
            ImageView arrow = new ImageView(getContext());
            arrow.setImageDrawable(AudioUi.icon(AudioUi.Icon.CHEVRON_RIGHT, palette.muted()));
            arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(arrow, new LayoutParams(dp(20), dp(20)));
        }

        void bind(BookSections.Section section) {
            title.setText(section.displayName());
            metadata.setText(section.summary());
            String image = section.book().cover();
            if (!Objects.equals(artwork, image) || cover.getDrawable() == null) {
                artwork = image;
                cover.setTag(null);
                cover.setPadding(dp(10), dp(10), dp(10), dp(10));
                cover.setImageDrawable(AudioUi.icon(AudioUi.Icon.HEADPHONES, palette.accent()));
                CoverImages.load(cover, image);
            }
        }
    }

    private final class FileRow extends LinearLayout {
        private final CheckBox check = new CheckBox(getContext());
        private final TextView title = text(15, palette.paper());
        private final TextView status = text(12, palette.muted());
        private final TextView size = text(12, palette.muted());

        FileRow() {
            super(LibraryRows.this.getContext());
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(60));
            setPadding(0, dp(8), 0, dp(8));
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            check.setButtonTintList(ColorStateList.valueOf(palette.accent()));
            addView(check, new LayoutParams(dp(36), -2));
            LinearLayout labels = new LinearLayout(getContext());
            labels.setOrientation(VERTICAL);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            status.setPadding(0, dp(3), 0, 0);
            labels.addView(status);
            addView(labels, new LayoutParams(0, -2, 1));
            size.setGravity(Gravity.END);
            size.setPadding(dp(12), 0, 0, 0);
            addView(size, new LayoutParams(-2, -2));
        }

        void bind(BookSections.Entry entry) {
            boolean checked = selecting && selected.contains(entry.part());
            check.setVisibility(selecting ? VISIBLE : GONE);
            check.setChecked(checked);
            int boundary = entry.detail().lastIndexOf(" · ");
            title.setText(entry.name());
            boolean current = entry.equals(currentEpisode);
            setBackgroundColor(current ? palette.selected() : android.graphics.Color.TRANSPARENT);
            title.setTextColor(checked || current ? palette.accent() : palette.paper());
            String state = boundary < 0 ? entry.detail() : entry.detail().substring(0, boundary);
            status.setText(current ? "当前 · " + state : state);
            size.setText(boundary < 0 ? "" : entry.detail().substring(boundary + 3));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setStateDescription(selecting ? checked ? "已选" : "未选" : null);
            }
        }
    }
}
