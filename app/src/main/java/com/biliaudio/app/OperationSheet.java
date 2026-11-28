package com.biliaudio.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class OperationSheet {
    private final Activity activity;
    private final AudioUi.Palette palette;
    private final Dialog dialog;
    private final LinearLayout surface;
    private final LinearLayout body;

    OperationSheet(Activity activity, String title, String summary) {
        this.activity = activity;
        palette = AudioUi.palette(activity);
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout frame = column();
        frame.setPadding(dp(8), dp(8), dp(8), dp(8));
        surface = column();
        surface.setBackground(AudioUi.card(activity, palette.surface(), 24));
        frame.addView(surface, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout header = column();
        header.setPadding(dp(20), dp(18), dp(20), dp(10));
        TextView heading = text(title, 22, palette.paper());
        heading.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) {
            surface.setAccessibilityPaneTitle(title);
            heading.setAccessibilityHeading(true);
        }
        header.addView(heading);
        if (summary != null) {
            TextView scope = text(summary, 14, palette.muted());
            scope.setPadding(0, dp(3), 0, 0);
            header.addView(scope);
        }
        surface.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        body = column();
        body.setPadding(dp(12), 0, dp(12), dp(8));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        surface.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        dialog.setContentView(frame);
    }

    void section(String title) {
        if (body.getChildCount() > 0) {
            View divider = new View(activity);
            divider.setBackgroundColor(palette.outline());
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
            line.setMargins(dp(8), dp(7), dp(8), 0);
            body.addView(divider, line);
        }
        TextView heading = text(title, 13, palette.muted());
        heading.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        heading.setPadding(dp(8), dp(8), dp(8), dp(4));
        body.addView(heading);
    }

    void action(AudioUi.Icon icon, String title, String detail, String count, Runnable action) {
        int color = icon == AudioUi.Icon.DELETE || icon == AudioUi.Icon.LOGOUT
                ? activity.getColor(R.color.action_danger) : palette.paper();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        row.setBackground(AudioUi.clickable(activity, palette.surface(), 12));
        row.setFocusable(true);
        row.setContentDescription(title + (detail == null ? "" : "，" + detail)
                + (count == null ? "" : "，" + count));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(view -> run(action));
        ImageView image = new ImageView(activity);
        image.setImageDrawable(AudioUi.icon(icon, color));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(image, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout labels = column();
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = text(title, 16, color);
        name.setTypeface(null, Typeface.BOLD);
        labels.addView(name);
        if (detail != null) {
            labels.addView(text(detail, 13, palette.muted()));
        }
        LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(0, -2, 1);
        labelSpace.setMarginStart(dp(12));
        row.addView(labels, labelSpace);
        if (count != null) {
            TextView quantity = text(count, 13, palette.muted());
            quantity.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            quantity.setPaddingRelative(dp(8), 0, 0, 0);
            row.addView(quantity);
        }
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    void show() {
        addFooter("关闭");
        open();
    }

    void confirm(String explanation, String label, boolean destructive, Runnable action) {
        TextView message = text(explanation, 15, palette.muted());
        message.setPadding(dp(8), dp(3), dp(8), dp(6));
        message.setLineSpacing(dp(4), 1f);
        body.addView(message);
        LinearLayout footer = addFooter("取消");
        Button confirm = button(label, true, () -> run(action));
        if (destructive) {
            confirm.setBackground(AudioUi.clickable(activity, activity.getColor(R.color.action_danger), 12));
            confirm.setTextColor(activity.getColor(R.color.action_danger_foreground));
        }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 2);
        size.setMarginStart(dp(8));
        footer.addView(confirm, size);
        open();
    }

    private LinearLayout addFooter(String label) {
        LinearLayout footer = new LinearLayout(activity);
        footer.setPadding(dp(20), dp(4), dp(20), dp(16));
        footer.addView(button(label, false, dialog::dismiss), new LinearLayout.LayoutParams(0, -2, 1));
        surface.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        return footer;
    }

    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(15);
        AudioUi.styleButton(button, primary);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void run(Runnable action) {
        dialog.dismiss();
        action.run();
    }

    private void open() {
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.46f);
        int width = Math.min(activity.getWindow().getDecorView().getWidth(), dp(500));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        dialog.show();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int size) {
        return AudioUi.dp(activity, size);
    }
}
