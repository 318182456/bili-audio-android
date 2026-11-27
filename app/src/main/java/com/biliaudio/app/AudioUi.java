package com.biliaudio.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;

final class AudioUi {
    enum Icon { SEARCH, BOOKMARK, DOWNLOAD, LIBRARY, HEADPHONES, PLAY, PAUSE, LINK, MANAGE, ACCOUNT,
        BACK, PREVIOUS, NEXT, SETTINGS }

    record Palette(int paper, int muted, int surface, int subtle, int outline, int accent, int field,
                   int artwork, int page, int gradient, int selected) {}

    private AudioUi() {}

    static Palette palette(Context context) {
        return new Palette(context.getColor(R.color.text_primary), context.getColor(R.color.text_secondary),
                context.getColor(R.color.card_surface), context.getColor(R.color.card_subtle),
                context.getColor(R.color.card_outline), context.getColor(R.color.action_accent),
                context.getColor(R.color.field_surface), context.getColor(R.color.artwork_tint),
                context.getColor(R.color.page_background), context.getColor(R.color.page_gradient),
                context.getColor(R.color.navigation_selected));
    }

    static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static GradientDrawable card(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    static GradientDrawable gradient(Context context, int top, int bottom, int radius) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{top, bottom});
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    static RippleDrawable clickable(Context context, int color, int radius) {
        int ripple = isNight(context) && color != context.getColor(R.color.action_accent)
                ? 0x26ffffff : 0x261b222b;
        return new RippleDrawable(ColorStateList.valueOf(ripple), card(context, color, radius),
                card(context, Color.WHITE, radius));
    }

    static void styleButton(Button button, boolean primary) {
        Context context = button.getContext();
        button.setAllCaps(false);
        int text = primary ? R.color.action_foreground : R.color.text_primary;
        int fill = primary ? R.color.action_accent : R.color.card_subtle;
        button.setTextColor(ColorStateList.valueOf(context.getColor(text)));
        button.setBackground(clickable(context, context.getColor(fill), 12));
        button.setPadding(dp(context, 11), dp(context, 3), dp(context, 11), dp(context, 3));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Drawable icon(Icon type, int color) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();
                switch (type) {
                    case SEARCH -> {
                        canvas.drawCircle(10, 10, 5.8f, paint);
                        canvas.drawLine(14.3f, 14.3f, 20, 20, paint);
                    }
                    case BOOKMARK -> {
                        path.moveTo(6, 20);
                        path.lineTo(6, 5);
                        path.quadTo(6, 4, 7, 4);
                        path.lineTo(17, 4);
                        path.quadTo(18, 4, 18, 5);
                        path.lineTo(18, 20);
                        path.lineTo(12, 16);
                        path.close();
                    }
                    case DOWNLOAD -> {
                        canvas.drawLine(12, 4, 12, 15, paint);
                        path.moveTo(8, 11);
                        path.lineTo(12, 15);
                        path.lineTo(16, 11);
                        path.moveTo(5, 16);
                        path.lineTo(5, 20);
                        path.lineTo(19, 20);
                        path.lineTo(19, 16);
                    }
                    case LIBRARY -> {
                        canvas.drawLine(5, 6, 17, 6, paint);
                        canvas.drawLine(5, 11, 14, 11, paint);
                        canvas.drawLine(5, 16, 10, 16, paint);
                        canvas.drawLine(18, 10, 18, 17, paint);
                        canvas.drawOval(13.6f, 16, 18, 20, paint);
                        canvas.drawLine(18, 10, 21, 11, paint);
                    }
                    case HEADPHONES -> {
                        canvas.drawArc(4, 4, 20, 20, 180, 180, false, paint);
                        canvas.drawRoundRect(4, 11, 8, 19, 2, 2, paint);
                        canvas.drawRoundRect(16, 11, 20, 19, 2, 2, paint);
                        canvas.drawLine(12, 13, 12, 18, paint);
                    }
                    case PLAY -> {
                        paint.setStyle(Paint.Style.FILL);
                        path.moveTo(8, 5);
                        path.lineTo(19, 12);
                        path.lineTo(8, 19);
                        path.close();
                    }
                    case PAUSE -> {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawRoundRect(6, 5, 10, 19, 1, 1, paint);
                        canvas.drawRoundRect(14, 5, 18, 19, 1, 1, paint);
                    }
                    case LINK -> {
                        canvas.rotate(-42, 12, 12);
                        canvas.drawRoundRect(3, 8.5f, 12, 15.5f, 3.5f, 3.5f, paint);
                        canvas.drawRoundRect(12, 8.5f, 21, 15.5f, 3.5f, 3.5f, paint);
                        canvas.drawLine(9, 12, 15, 12, paint);
                    }
                    case MANAGE -> {
                        canvas.drawRoundRect(4, 5, 20, 19, 2, 2, paint);
                        canvas.drawLine(4, 10, 20, 10, paint);
                        canvas.drawLine(8, 15, 11, 15, paint);
                        canvas.drawLine(14, 15, 16, 15, paint);
                    }
                    case ACCOUNT -> {
                        canvas.drawCircle(12, 7.5f, 3.1f, paint);
                        path.moveTo(5, 20);
                        path.lineTo(5, 18.8f);
                        path.cubicTo(5, 13, 19, 13, 19, 18.8f);
                        path.lineTo(19, 20);
                        path.close();
                    }
                    case BACK -> {
                        path.moveTo(13, 5);
                        path.lineTo(6, 12);
                        path.lineTo(13, 19);
                        canvas.drawLine(6, 12, 20, 12, paint);
                    }
                    case PREVIOUS, NEXT -> {
                        if (type == Icon.PREVIOUS) {
                            canvas.scale(-1, 1, 12, 12);
                        }
                        path.moveTo(6, 5.5f);
                        path.lineTo(16, 12);
                        path.lineTo(6, 18.5f);
                        path.close();
                        canvas.drawLine(18, 6, 18, 18, paint);
                    }
                    case SETTINGS -> {
                        canvas.drawLine(4, 6, 20, 6, paint);
                        canvas.drawLine(4, 12, 20, 12, paint);
                        canvas.drawLine(4, 18, 20, 18, paint);
                        canvas.drawLine(9, 4, 9, 8, paint);
                        canvas.drawLine(15, 10, 15, 14, paint);
                        canvas.drawLine(10, 16, 10, 20, paint);
                    }
                }
                canvas.drawPath(path, paint);
                canvas.restore();
            }

            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(ColorFilter filter) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
