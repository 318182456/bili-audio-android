package com.biliaudio.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

final class CaptionScrubber extends LinearLayout {
    private static final int TIME_STEP = 10_000;

    private final AudioUi.Palette palette;
    private final IntConsumer commitSeek;
    private final Runnable updateTimeline;
    private final int rowHeight;
    private final TextView hint;
    private final TranscriptList list;
    private final CueRows rows = new CueRows();
    private List<AudioLibrary.Cue> cues = Collections.emptyList();
    private PlaybackService.CaptionTrack track;
    private int duration;
    private int position;
    private int focused = -1;
    private int preview = -1;
    private boolean captionsVisible;

    CaptionScrubber(Context context, AudioUi.Palette palette, IntConsumer commitSeek,
                    Runnable updateTimeline) {
        super(context);
        this.palette = palette;
        this.commitSeek = commitSeek;
        this.updateTimeline = updateTimeline;
        TextView measure = new TextView(context);
        measure.setTextSize(14);
        rowHeight = Math.max(AudioUi.dp(context, 39), 2 * measure.getLineHeight() + AudioUi.dp(context, 3));
        setOrientation(VERTICAL);
        setPadding(AudioUi.dp(context, 10), 0, AudioUi.dp(context, 10), 0);
        setBackground(AudioUi.card(context, palette.field(), 16));

        FrameLayout viewport = new FrameLayout(context);
        View selection = new View(context);
        selection.setBackground(AudioUi.card(context, palette.selected(), 11));
        viewport.addView(selection, new FrameLayout.LayoutParams(-1, rowHeight, Gravity.CENTER));
        list = new TranscriptList(context);
        viewport.addView(list, new FrameLayout.LayoutParams(-1, -1));
        addView(viewport, new LayoutParams(-1, 0, 1));

        hint = new TextView(context);
        hint.setTextColor(palette.muted());
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, AudioUi.dp(context, 3), 0, AudioUi.dp(context, 4));
        addView(hint, new LayoutParams(-1, -2));
    }

    void update(PlaybackService.CaptionTrack source, int position, int duration, boolean show) {
        if (list.touching && track != null && source != null
                && track.part().equals(source.part()) && track.start() == source.start()
                && track.end() == source.end() && track.version() == source.version()) {
            source = track;
        }
        boolean changed = track != source || captionsVisible != show;
        int oldCount = rows.getCount();
        track = source;
        this.position = position;
        this.duration = duration;
        captionsVisible = show;
        if (changed) {
            preview = -1;
            cues = show && source != null ? source.cues() : Collections.emptyList();
        }
        boolean rowsChanged = changed || oldCount != rows.getCount();
        if (rowsChanged) {
            rows.notifyDataSetChanged();
        }
        if (!list.touching || changed) {
            followPlayback(rowsChanged);
        }
        updateHint();
    }

    int displayedPosition() {
        return preview >= 0 ? preview : position;
    }

    private void followPlayback(boolean force) {
        int row = cues.isEmpty() ? position / TIME_STEP : CaptionText.closestCue(cues, position + track.start());
        row = Math.max(0, Math.min(rows.getCount() - 1, row));
        if (force || row != focused) {
            focusRow(row);
            list.setSelectionFromTop(row + 1, list.centerOffset());
        }
    }

    private int timestamp(int row) {
        int time = cues.isEmpty() ? row * TIME_STEP : cues.get(row).start() - track.start();
        return Math.max(0, Math.min(duration - 1, time));
    }

    private void focusRow(int row) {
        focused = row;
        for (int index = 0; index < list.getChildCount(); index++) {
            if (list.getChildAt(index) instanceof TextView label) {
                styleRow(label, list.getFirstVisiblePosition() + index - 1 == row);
            }
        }
    }

    private void styleRow(TextView label, boolean selected) {
        label.setTextColor(selected ? palette.paper() : palette.muted());
        label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateHint() {
        String text;
        if (preview >= 0) {
            text = "松手跳转 · " + AudioLibrary.time(preview);
        } else if (cues.isEmpty()) {
            text = (captionsVisible ? "本集暂无字幕" : "字幕已隐藏") + " · 滑动时间轴";
        } else {
            text = "上下拖动或点按字幕跳转";
        }
        if (!TextUtils.equals(hint.getText(), text)) {
            hint.setText(text);
        }
    }

    private void seekToRow(int row) {
        position = timestamp(row);
        followPlayback(true);
        updateHint();
        commitSeek.accept(position);
    }

    private final class TranscriptList extends ListView {
        private final View header;
        private final View footer;
        private PlaybackService.CaptionTrack gestureTrack;
        private boolean gestureCaptions;
        private boolean touching;
        private boolean scrolling;

        TranscriptList(Context context) {
            super(context);
            setDivider(null);
            setCacheColorHint(Color.TRANSPARENT);
            setSelector(android.R.color.transparent);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            header = new View(context);
            footer = new View(context);
            header.setLayoutParams(new AbsListView.LayoutParams(-1, 0));
            footer.setLayoutParams(new AbsListView.LayoutParams(-1, 0));
            addHeaderView(header, null, false);
            addFooterView(footer, null, false);
            setAdapter(rows);
            setOnItemClickListener((parent, view, index, id) -> seekToRow(index - 1));
            setOnScrollListener(new OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) {
                    if (state == SCROLL_STATE_TOUCH_SCROLL && sameGesture()) {
                        scrolling = true;
                        previewCenter();
                    }
                }

                @Override public void onScroll(AbsListView view, int first, int visible, int count) {
                    if (scrolling && sameGesture()) {
                        previewCenter();
                    }
                }
            });
        }

        int centerOffset() {
            return Math.max(0, (getHeight() - rowHeight) / 2);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            header.setLayoutParams(new AbsListView.LayoutParams(-1, centerOffset()));
            footer.setLayoutParams(new AbsListView.LayoutParams(-1, centerOffset()));
            post(() -> followPlayback(true));
        }

        private boolean sameGesture() {
            return touching && gestureTrack == track && gestureCaptions == captionsVisible;
        }

        private int centerRow() {
            int best = -1;
            int distance = Integer.MAX_VALUE;
            for (int index = 0; index < getChildCount(); index++) {
                int row = getFirstVisiblePosition() + index - 1;
                if (row >= 0 && row < rows.getCount()) {
                    View child = getChildAt(index);
                    int delta = Math.abs(child.getTop() + child.getBottom() - getHeight());
                    if (delta < distance) {
                        distance = delta;
                        best = row;
                    }
                }
            }
            return best;
        }

        private void previewCenter() {
            int row = centerRow();
            if (row >= 0 && (row != focused || preview < 0)) {
                focusRow(row);
                preview = timestamp(row);
                updateHint();
                updateTimeline.run();
            }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                touching = true;
                gestureTrack = track;
                gestureCaptions = captionsVisible;
            }
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touching) {
                boolean valid = sameGesture();
                boolean wasScrolling = scrolling;
                int row = action == MotionEvent.ACTION_UP && valid && wasScrolling ? centerRow() : -1;
                touching = false;
                scrolling = false;
                preview = -1;
                if (wasScrolling || !valid) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    if (row >= 0) {
                        seekToRow(row);
                    } else {
                        followPlayback(true);
                        updateHint();
                        updateTimeline.run();
                    }
                    return true;
                }
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN && !handled) {
                touching = false;
            }
            return handled;
        }
    }

    private final class CueRows extends BaseAdapter {
        @Override public int getCount() {
            return cues.isEmpty() ? Math.max(1, (duration - 1) / TIME_STEP + 1) : cues.size();
        }

        @Override public Object getItem(int row) {
            return cues.isEmpty() ? AudioLibrary.time(timestamp(row)) : cues.get(row).text();
        }

        @Override public long getItemId(int row) { return row; }

        @Override public View getView(int row, View recycled, ViewGroup parent) {
            TextView label = recycled instanceof TextView text ? text : new TextView(getContext());
            label.setLayoutParams(new AbsListView.LayoutParams(-1, rowHeight));
            label.setPadding(AudioUi.dp(getContext(), 10), 0, AudioUi.dp(getContext(), 10), 0);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(14);
            label.setIncludeFontPadding(false);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText((String) getItem(row));
            styleRow(label, row == focused);
            return label;
        }
    }
}
