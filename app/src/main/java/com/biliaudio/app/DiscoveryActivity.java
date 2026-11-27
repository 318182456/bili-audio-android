package com.biliaudio.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class DiscoveryActivity extends AudioActivity {
    static final String EXTRA_FAVORITES = "favorites";
    static final String EXTRA_BVID = "bvid";
    static final String EXTRA_DOWNLOAD = "download";


    private final BilibiliClient client = new BilibiliClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AudioUi.Palette palette;
    private boolean favorites;
    private boolean busy;
    private int page;
    private String keyword = "";
    private BilibiliClient.Folder currentFolder;
    private List<BilibiliClient.Folder> folders = new ArrayList<>();

    private EditText query;
    private Button folderButton;
    private Button pinFolder;
    private Button searchButton;
    private TextView status;
    private TextView pageLabel;
    private Button previous;
    private Button next;
    private LinearLayout results;
    private ScrollView list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        palette = AudioUi.palette(this);
        favorites = getIntent().getBooleanExtra(EXTRA_FAVORITES, false);
        LinearLayout root = column();
        root.setFitsSystemWindows(true);
        root.setBackground(AudioUi.gradient(this, palette.gradient(), palette.page(), 0));
        root.setPadding(dp(16), dp(15), dp(16), dp(12));

        LinearLayout header = row();
        button(header, "返回", this::finish);
        TextView heading = label(favorites ? "B 站收藏夹" : "搜索 B 站视频", 24, palette.paper());
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading);
        root.addView(header);
        if (favorites) {
            LinearLayout folderActions = row();
            folderButton = button(folderActions, "选择收藏夹", this::requestFolders);
            folderButton.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            folderButton.setMaxLines(1);
            folderButton.setEllipsize(TextUtils.TruncateAt.END);
            pinFolder = button(folderActions, "置顶", this::toggleFolderPin);
            pinFolder.setEnabled(false);
            root.addView(folderActions);
        }
        LinearLayout search = row();
        query = new EditText(this);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setHint(favorites ? "搜索当前收藏夹" : "输入视频标题或关键词");
        query.setHintTextColor(palette.muted());
        query.setTextColor(palette.paper());
        query.setTextSize(15);
        query.setBackground(AudioUi.card(this, palette.surface(), 13));
        query.setPadding(dp(12), dp(12), dp(12), dp(12));
        query.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(0, -2, 1);
        inputLayout.rightMargin = dp(8);
        search.addView(query, inputLayout);
        searchButton = button(search, "搜索", this::search);
        AudioUi.styleButton(searchButton, true);
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(-1, -2);
        searchLayout.topMargin = dp(8);
        root.addView(search, searchLayout);
        status = label("", 14, palette.muted());
        status.setPadding(dp(6), dp(4), dp(6), dp(6));
        root.addView(status);

        list = new ScrollView(this);
        results = column();
        list.addView(results);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pagination = row();
        pagination.setGravity(Gravity.CENTER);
        previous = button(pagination, "上一页", () -> fetch(page - 1, keyword));
        pageLabel = label("", 14, palette.muted());
        pagination.addView(pageLabel);
        next = button(pagination, "下一页", () -> fetch(page + 1, keyword));
        root.addView(pagination);
        setContentView(root);

        root.setFocusableInTouchMode(true);
        root.requestFocus();
        previous.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        if (state != null) {
            query.setText(state.getString("query", ""));
        }
        if (favorites) {
            int restoredPage = state == null ? 1 : state.getInt("page", 1);
            long folderId = state == null ? 0 : state.getLong("folder", 0);
            loadFolders(folderId, restoredPage);
        } else if (state != null && state.getInt("page") > 0) {
            fetch(state.getInt("page"), state.getString("keyword", ""));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query.getText().toString());
        state.putString("keyword", keyword);
        state.putInt("page", page);
        state.putLong("folder", currentFolder == null ? 0 : currentFolder.id());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (!favorites && value.isEmpty()) {
            status.setText("请输入搜索关键词。");
            return;
        }
        if (value.length() > 120) {
            status.setText("请输入 120 字以内的关键词。");
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        query.clearFocus();
        if (!favorites || currentFolder != null) {
            fetch(1, value);
        }
    }

    private void loadFolders(long folderId, int restoredPage) {
        request("正在读取收藏夹…", client::favoriteFolders, loaded -> {
            folders = loaded;
            for (BilibiliClient.Folder folder : folders) {
                if (folder.id() == folderId) {
                    setFolder(folder, Math.max(1, restoredPage));
                    return;
                }
            }
            if (folders.isEmpty()) {
                status.setText("账号里还没有收藏夹。");
            } else {
                long pinned = folderPreferences().getLong("pinned", 0);
                long recent = folderPreferences().getLong("recent", 0);
                BilibiliClient.Folder selected = folders.stream().filter(f -> f.id() == pinned).findFirst()
                        .orElseGet(() -> folders.stream().filter(f -> f.id() == recent).findFirst().orElse(folders.get(0)));
                setFolder(selected, 1);
            }
        });
    }

    private void requestFolders() {
        if (folders.isEmpty()) {
            loadFolders(0, 1);
        } else {
            chooseFolder("选择收藏夹", folders, folder -> {
                query.setText("");
                setFolder(folder, 1);
            });
        }
    }

    private void setFolder(BilibiliClient.Folder folder, int page) {
        currentFolder = folder;
        folderPreferences().edit().putLong("recent", folder.id()).apply();
        folderButton.setText(folder.title() + "  ·  切换");
        updatePin();
        results.removeAllViews();
        pageLabel.setText("");
        previous.setVisibility(View.GONE);
        next.setVisibility(View.GONE);
        fetch(page, query.getText().toString().trim());
    }

    private SharedPreferences folderPreferences() {
        return getSharedPreferences("favorite_folders", MODE_PRIVATE);
    }

    private void toggleFolderPin() {
        if (currentFolder == null) {
            return;
        }
        long pinned = folderPreferences().getLong("pinned", 0);
        folderPreferences().edit().putLong("pinned", pinned == currentFolder.id() ? 0 : currentFolder.id()).apply();
        updatePin();
    }

    private void updatePin() {
        boolean pinned = currentFolder != null && folderPreferences().getLong("pinned", 0) == currentFolder.id();
        pinFolder.setText(pinned ? "取消置顶" : "置顶");
        pinFolder.setEnabled(currentFolder != null && !busy);
        AudioUi.styleButton(pinFolder, pinned);
    }

    private void fetch(int number, String value) {
        BilibiliClient.Folder folder = currentFolder;
        request(favorites ? "正在读取收藏视频…" : "正在搜索视频…",
                () -> favorites ? client.favorites(folder, value, number) : client.search(value, number),
                result -> {
                    page = number;
                    keyword = value;
                    render(result);
                });
    }

    private void render(BilibiliClient.VideoPage loaded) {
        results.removeAllViews();
        List<BilibiliClient.Video> entries = loaded.entries();
        status.setText(entries.isEmpty() ? "这一页没有可下载的视频。" : "");
        for (BilibiliClient.Video video : entries) {
            LinearLayout card = column();
            GradientDrawable background = AudioUi.card(this, palette.surface(), 17);
            background.setStroke(dp(1), palette.outline());
            card.setBackground(background);
            card.setPadding(dp(12), dp(12), dp(12), dp(6));
            LinearLayout headline = row();
            ImageView thumbnail = new ImageView(this);
            thumbnail.setImageDrawable(AudioUi.icon(AudioUi.Icon.HEADPHONES, palette.accent()));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(AudioUi.gradient(this, palette.artwork(), palette.subtle(), 11));
            thumbnail.setPadding(dp(15), dp(12), dp(15), dp(12));
            thumbnail.setClipToOutline(true);
            headline.addView(thumbnail, new LinearLayout.LayoutParams(dp(91), dp(67)));
            CoverImages.load(thumbnail, video.cover());
            LinearLayout information = column();
            TextView title = label(video.title(), 17, palette.paper());
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(3);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setOnClickListener(view -> chooseVideo(video, false));
            information.addView(title);
            String details = video.author().isEmpty() ? video.duration()
                    : video.author() + (video.duration().isEmpty() ? "" : "  ·  " + video.duration());
            if (!details.isEmpty()) {
                TextView metadata = label(details, 13, palette.muted());
                metadata.setPadding(0, dp(4), 0, 0);
                metadata.setMaxLines(1);
                metadata.setEllipsize(TextUtils.TruncateAt.END);
                information.addView(metadata);
            }
            LinearLayout.LayoutParams informationLayout = new LinearLayout.LayoutParams(0, -2, 1);
            informationLayout.leftMargin = dp(12);
            headline.addView(information, informationLayout);
            card.addView(headline);
            LinearLayout actions = row();
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            button(actions, "打开作品", () -> chooseVideo(video, false));
            button(actions, "收藏", () -> addFavorite(video));
            AudioUi.styleButton(button(actions, "下载", () -> chooseVideo(video, true)), true);
            LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(-1, -2);
            actionLayout.topMargin = dp(7);
            card.addView(actions, actionLayout);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.bottomMargin = dp(9);
            results.addView(card, layout);
        }
        list.scrollTo(0, 0);
        previous.setVisibility(page > 1 ? View.VISIBLE : View.GONE);
        next.setVisibility(loaded.hasMore() ? View.VISIBLE : View.GONE);
        pageLabel.setText("第 " + page + " 页");
    }

    private void chooseVideo(BilibiliClient.Video video, boolean download) {
        if (!busy) {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_BVID, video.bvid())
                    .putExtra(EXTRA_DOWNLOAD, download));
            finish();
        }
    }

    private void addFavorite(BilibiliClient.Video video) {
        request("正在读取收藏夹…", client::favoriteFolders, available -> {
            status.setText("");
            chooseFolder("收藏到 B 站", available, folder ->
                    request("正在收藏…", () -> {
                        client.addFavorite(video, folder);
                        return folder;
                    }, saved -> {
                        status.setText("已收藏到「" + saved.title() + "」");
                        Toast.makeText(this, "收藏成功", Toast.LENGTH_SHORT).show();
                    }));
        });
    }

    private void chooseFolder(String title, List<BilibiliClient.Folder> choices,
                              Consumer<BilibiliClient.Folder> selected) {
        if (choices.isEmpty()) {
            status.setText("账号里还没有收藏夹。");
            return;
        }
        long pinned = folderPreferences().getLong("pinned", 0);
        List<BilibiliClient.Folder> ordered = new ArrayList<>(choices);
        ordered.sort(java.util.Comparator.comparing(folder -> folder.id() != pinned));
        CharSequence[] names = ordered.stream().map(folder -> (folder.id() == pinned ? "置顶 · " : "")
                + folder.title() + " · " + folder.count())
                .toArray(CharSequence[]::new);
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, (dialog, index) -> selected.accept(ordered.get(index)))
                .setNegativeButton("取消", null).show();
    }

    private <T> void request(String message, Callable<T> operation, Consumer<T> completed) {
        if (busy || isFinishing()) {
            return;
        }
        setBusy(true);
        status.setText(message);
        worker.execute(() -> {
            try {
                T value = operation.call();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setBusy(false);
                        completed.accept(value);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setBusy(false);
                        String details = error.getMessage();
                        status.setText(details != null && details.length() <= 120 && !details.contains("://")
                                ? details : "读取失败，请重试。");
                    }
                });
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        searchButton.setEnabled(!value);
        previous.setEnabled(!value);
        next.setEnabled(!value);
        if (folderButton != null) {
            folderButton.setEnabled(!value);
            updatePin();
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button button(LinearLayout parent, String value, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextColor(palette.paper());
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
