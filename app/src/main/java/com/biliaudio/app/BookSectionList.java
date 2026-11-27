package com.biliaudio.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.BaseExpandableListAdapter;
import android.widget.CheckBox;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class BookSectionList extends ExpandableListView {
    private final int groupColumns;
    private final int fileColumns;

    record Entry(AudioLibrary.Part part, int segment, String name, String detail,
                 boolean stored, int start, int end, boolean hasSubtitles) {
        Entry(AudioLibrary.Part part, int segment, String name, String detail, boolean stored) {
            this(part, segment, name, detail, stored, 0, part.duration(), false);
        }
    }

    record EpisodeGroup(int first, int last, int pending, List<Entry> entries) {
        String range() { return first == last ? String.valueOf(first) : first + "–" + last; }
        String name() { return "第 " + range() + " 集"; }
        String detail() {
            int playable = entries.size() - pending;
            return pending > 0 ? playable + " 集 · " + pending + " 待分" : playable + " 集可播放";
        }

        int episodeAt(long cid, int position) {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                int at = Math.min(position, entry.part().duration() - 1);
                if (entry.part().cid() == cid && at >= entry.start() && at < entry.end()) {
                    return index;
                }
            }
            return -1;
        }
    }

    record Section(AudioLibrary.Title book, String displayName, String summary,
                   List<Entry> entries, List<EpisodeGroup> groups) {}

    private final AudioUi.Palette palette;
    private final Consumer<AudioLibrary.Title> rename;
    private final BiConsumer<AudioLibrary.Title, Entry> onEntry;
    private final BiConsumer<AudioLibrary.Title, EpisodeGroup> onEpisodeGroup;
    private final boolean fileGrid;
    private final Rows adapter = new Rows();
    private final Set<String> expanded = new HashSet<>();
    private List<Section> sections = Collections.emptyList();
    private Set<AudioLibrary.Part> checked;
    private boolean initialized;
    private boolean restoring;
    private String activeBook;
    private int activeGroup;

    BookSectionList(Context context, AudioUi.Palette palette, boolean fileGrid,
                    BiConsumer<AudioLibrary.Title, Entry> onEntry,
                    BiConsumer<AudioLibrary.Title, EpisodeGroup> onEpisodeGroup,
                    Consumer<AudioLibrary.Title> rename) {
        super(context);
        this.palette = palette;
        this.fileGrid = fileGrid;
        this.rename = rename;
        this.onEntry = onEntry;
        this.onEpisodeGroup = onEpisodeGroup;
        var configuration = context.getResources().getConfiguration();
        float effectiveWidth = (configuration.screenWidthDp - 32) / Math.max(1, configuration.fontScale);
        groupColumns = effectiveWidth >= 300 ? 3 : effectiveWidth >= 186 ? 2 : 1;
        fileColumns = effectiveWidth >= 296 ? 2 : 1;
        setPadding(dp(16), dp(2), dp(16), dp(12));
        setClipToPadding(false);
        setCacheColorHint(Color.TRANSPARENT);
        setDivider(null);
        setGroupIndicator(null);
        setItemsCanFocus(true);
        setAdapter(adapter);
        setOnGroupExpandListener(index -> {
            if (!restoring) {
                expanded.add(id(sections.get(index)));
            }
        });
        setOnGroupCollapseListener(index -> {
            if (!restoring) {
                expanded.remove(id(sections.get(index)));
            }
        });
        setOnChildClickListener((parent, view, group, child, key) -> {
            Section section = sections.get(group);
            if (fileGrid || !section.groups().isEmpty()) {
                return false;
            }
            onEntry.accept(section.book(), section.entries().get(child));
            return true;
        });
    }

    void restoreExpansion(ArrayList<String> books) {
        if (books != null) {
            expanded.addAll(books);
            initialized = true;
        }
    }

    ArrayList<String> expandedBooks() {
        return initialized ? new ArrayList<>(expanded) : null;
    }

    void showSelection(Set<AudioLibrary.Part> selection) {
        if (!Objects.equals(checked, selection)) {
            checked = selection == null ? null : new HashSet<>(selection);
            adapter.notifyDataSetChanged();
        }
    }

    void submit(List<Section> updated) {
        if (updated.equals(sections)) {
            return;
        }
        boolean changed = !sameOrder(updated);
        int first = getFirstVisiblePosition();
        View top = getChildAt(0);
        int offset = top == null ? 0 : top.getTop() - getPaddingTop();
        restoring = true;
        try {
            if (changed) {
                for (int index = 0; index < sections.size(); index++) {
                    collapseGroup(index);
                }
            }
            sections = updated;
            adapter.notifyDataSetChanged();
            if (!initialized && !sections.isEmpty()) {
                expanded.add(id(sections.get(0)));
                initialized = true;
            }
            if (changed) {
                for (int index = 0; index < sections.size(); index++) {
                    if (expanded.contains(id(sections.get(index)))) {
                        expandGroup(index);
                    }
                }
                if (first > 0) {
                    setSelectionFromTop(Math.min(first, getCount() - 1), offset);
                }
            }
        } finally {
            restoring = false;
        }
    }

    void openEpisodeGroup(String bvid, long cid, int position) {
        activeBook = bvid;
        activeGroup = 0;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (!id(section).equals(bvid)) {
                continue;
            }
            expandGroup(index);
            for (int cell = 0; cell < section.groups().size(); cell++) {
                EpisodeGroup group = section.groups().get(cell);
                if (group.episodeAt(cid, position) >= 0) {
                    activeGroup = group.first();
                    adapter.notifyDataSetChanged();
                    int row = cell / groupColumns;
                    long packed = row == 0 ? getPackedPositionForGroup(index) : getPackedPositionForChild(index, row);
                    setSelectionFromTop(getFlatListPosition(packed), dp(4));
                    return;
                }
            }
            setSelectedGroup(index);
            break;
        }
        adapter.notifyDataSetChanged();
    }

    BaseAdapter episodeRows(EpisodeGroup group, int active) {
        return new BaseAdapter() {
            @Override public int getCount() { return group.entries().size(); }
            @Override public Entry getItem(int row) { return group.entries().get(row); }
            @Override public long getItemId(int row) { return row; }

            @Override public View getView(int row, View recycled, ViewGroup parent) {
                EntryView view = recycled instanceof EntryView entry ? entry : new EntryView();
                view.bind(getItem(row));
                view.setBackground(AudioUi.clickable(getContext(), row == active ? palette.selected() : palette.field(), 9));
                return view;
            }
        };
    }

    private boolean sameOrder(List<Section> updated) {
        if (updated.size() != sections.size()) {
            return false;
        }
        for (int index = 0; index < updated.size(); index++) {
            if (!id(updated.get(index)).equals(id(sections.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private static String id(Section section) {
        return section.book().bvid();
    }

    private int dp(int value) {
        return AudioUi.dp(getContext(), value);
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private final class BookView extends LinearLayout {
        final ImageView cover = new ImageView(getContext());
        final TextView name = text(16, palette.paper());
        final TextView detail = text(12, palette.muted());
        final TextView state = text(12, palette.accent());
        final TextView edit = text(13, palette.accent());
        String artwork;

        BookView() {
            super(BookSectionList.this.getContext());
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(10), dp(12), dp(10));
            setBackground(AudioUi.clickable(getContext(), palette.surface(), 15));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(AudioUi.card(getContext(), palette.artwork(), 9));
            cover.setClipToOutline(true);
            addView(cover, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout labels = new LinearLayout(getContext());
            labels.setOrientation(VERTICAL);
            labels.setPadding(dp(7), 0, dp(7), 0);
            name.setTypeface(null, Typeface.BOLD);
            name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            detail.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
            detail.setPadding(0, dp(3), 0, 0);
            labels.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            addView(state, new LinearLayout.LayoutParams(dp(33), -2));
            edit.setText("改名");
            edit.setBackground(AudioUi.clickable(getContext(), palette.field(), 9));
            LinearLayout.LayoutParams editSpace = new LinearLayout.LayoutParams(dp(48), dp(48));
            editSpace.setMarginStart(dp(5));
            addView(edit, editSpace);
        }

        void bind(Section section, boolean open) {
            name.setText(section.displayName());
            detail.setText(section.summary());
            state.setText(open ? "收起" : "展开");
            edit.setContentDescription("重命名 " + section.displayName());
            edit.setOnClickListener(view -> rename.accept(section.book()));
            String image = section.book().cover();
            if (!Objects.equals(artwork, image)) {
                artwork = image;
                cover.setTag(null);
                cover.setPadding(dp(8), dp(8), dp(8), dp(8));
                cover.setImageDrawable(AudioUi.icon(AudioUi.Icon.HEADPHONES, palette.accent()));
                CoverImages.load(cover, image);
            }
        }
    }

    private final class EntryView extends LinearLayout {
        final TextView name = text(15, palette.paper());
        final TextView detail = text(12, palette.muted());
        final CheckBox checkbox = new CheckBox(getContext());
        final View balance = new View(getContext());
        final boolean compact;

        EntryView() { this(false); }

        EntryView(boolean compact) {
            super(BookSectionList.this.getContext());
            this.compact = compact;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(9), dp(12), dp(9));
            setMinimumHeight(dp(48));
            setBackground(AudioUi.clickable(getContext(), palette.field(), 9));
            if (compact) {
                setPadding(dp(10), dp(9), dp(10), dp(9));
                setMinimumHeight(dp(66));
                name.setTextSize(14);
                name.setTypeface(null, Typeface.BOLD);
                detail.setTextSize(12);
                detail.setMaxLines(3);
                detail.setEllipsize(TextUtils.TruncateAt.END);
            }
            name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            detail.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            checkbox.setButtonTintList(ColorStateList.valueOf(palette.accent()));
            checkbox.setClickable(false);
            checkbox.setFocusable(false);
            addView(checkbox, new LinearLayout.LayoutParams(dp(36), -2));
            LinearLayout labels = new LinearLayout(getContext());
            labels.setOrientation(VERTICAL);
            labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
            detail.setPadding(0, dp(3), 0, 0);
            labels.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            addView(balance, new LinearLayout.LayoutParams(dp(36), 1));
        }

        void bind(Entry entry) {
            name.setText(entry.name());
            detail.setText(entry.detail());
            boolean marked = checked != null && checked.contains(entry.part());
            checkbox.setVisibility(checked == null || compact ? View.GONE : View.VISIBLE);
            balance.setVisibility(checked == null || compact ? View.GONE : View.VISIBLE);
            checkbox.setChecked(marked);
            if (compact) {
                setSelected(marked);
                setBackground(AudioUi.clickable(getContext(), marked ? palette.selected() : palette.surface(), 12));
                name.setTextColor(marked ? palette.accent() : palette.paper());
                detail.setText(marked ? "已选 · " + entry.detail() : entry.detail());
                setContentDescription(entry.name() + " · " + entry.detail()
                        + (checked == null ? "" : marked ? " · 已选" : " · 未选"));
            }
        }
    }

    private final class ManagedFilesView extends LinearLayout {
        ManagedFilesView() {
            super(BookSectionList.this.getContext());
            setOrientation(HORIZONTAL);
            setPadding(0, dp(4), 0, dp(4));
            for (int column = 0; column < fileColumns; column++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                cell.setMargins(dp(3), 0, dp(3), 0);
                addView(new EntryView(true), cell);
            }
        }

        void bind(Section section, int row) {
            for (int column = 0; column < fileColumns; column++) {
                EntryView tile = (EntryView) getChildAt(column);
                int index = row * fileColumns + column;
                if (index >= section.entries().size()) {
                    tile.setVisibility(View.INVISIBLE);
                    continue;
                }
                Entry entry = section.entries().get(index);
                tile.setVisibility(View.VISIBLE);
                tile.bind(entry);
                tile.setOnClickListener(view -> onEntry.accept(section.book(), entry));
            }
        }
    }

    private final class EpisodeGroupsView extends LinearLayout {
        EpisodeGroupsView() {
            super(BookSectionList.this.getContext());
            setOrientation(HORIZONTAL);
            setPadding(0, dp(4), 0, dp(4));
            for (int column = 0; column < groupColumns; column++) {
                LinearLayout tile = new LinearLayout(getContext());
                tile.setOrientation(VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setPadding(dp(2), dp(6), dp(2), dp(6));
                tile.setMinimumHeight(dp(60));
                tile.addView(text(15, palette.paper()), new LinearLayout.LayoutParams(-1, -2));
                tile.addView(text(12, palette.muted()), new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                cell.setMargins(dp(3), 0, dp(3), 0);
                addView(tile, cell);
            }
        }

        void bind(Section section, int row) {
            for (int column = 0; column < groupColumns; column++) {
                LinearLayout tile = (LinearLayout) getChildAt(column);
                int index = row * groupColumns + column;
                if (index >= section.groups().size()) {
                    tile.setVisibility(View.INVISIBLE);
                    continue;
                }
                EpisodeGroup group = section.groups().get(index);
                boolean current = id(section).equals(activeBook) && group.first() == activeGroup;
                tile.setVisibility(View.VISIBLE);
                tile.setSelected(current);
                tile.setBackground(AudioUi.clickable(getContext(), current ? palette.selected() : palette.surface(), 12));
                tile.setContentDescription(group.name() + " · " + group.detail() + (current ? " · 当前分组" : ""));
                tile.setOnClickListener(view -> onEpisodeGroup.accept(section.book(), group));
                TextView range = (TextView) tile.getChildAt(0);
                range.setText(group.range());
                range.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                range.setTextColor(current ? palette.accent() : palette.paper());
                TextView detail = (TextView) tile.getChildAt(1);
                detail.setText(current ? "当前 · " + group.detail() : group.detail());
            }
        }
    }

    private final class Rows extends BaseExpandableListAdapter {
        @Override public int getGroupCount() { return sections.size(); }
        @Override public int getChildrenCount(int group) {
            Section section = getGroup(group);
            if (fileGrid) {
                return (section.entries().size() + fileColumns - 1) / fileColumns;
            }
            return section.groups().isEmpty() ? section.entries().size()
                    : (section.groups().size() + groupColumns - 1) / groupColumns;
        }
        @Override public Section getGroup(int group) { return sections.get(group); }
        @Override public Object getChild(int group, int child) {
            Section section = getGroup(group);
            return section.groups().isEmpty() ? section.entries().get(child * (fileGrid ? fileColumns : 1))
                    : section.groups().get(child * groupColumns);
        }
        @Override public long getGroupId(int group) { return group; }
        @Override public long getChildId(int group, int child) { return child; }
        @Override public boolean hasStableIds() { return false; }
        @Override public boolean isChildSelectable(int group, int child) { return true; }

        @Override public View getGroupView(int group, boolean open, View recycled, ViewGroup parent) {
            BookView view = recycled instanceof BookView book ? book : new BookView();
            view.bind(getGroup(group), open);
            return view;
        }

        @Override public View getChildView(int group, int child, boolean last, View recycled, ViewGroup parent) {
            Section section = getGroup(group);
            if (fileGrid) {
                ManagedFilesView view = recycled instanceof ManagedFilesView grid ? grid : new ManagedFilesView();
                view.bind(section, child);
                return view;
            }
            if (!section.groups().isEmpty()) {
                EpisodeGroupsView view = recycled instanceof EpisodeGroupsView grid ? grid : new EpisodeGroupsView();
                view.bind(section, child);
                return view;
            }
            EntryView view = recycled instanceof EntryView entry ? entry : new EntryView();
            view.bind(section.entries().get(child));
            return view;
        }
    }
}
