package org.openphone.assistant;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

final class PointerOverlayController {
    private static final int CURSOR_SIZE = 34;
    private static final int RIPPLE_SIZE = 96;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    private FrameLayout mRoot;
    private View mDot;
    private TextView mChip;
    private TextView mActionLabel;

    PointerOverlayController(Context context) {
        mContext = context.getApplicationContext();
    }

    void show(String taskId) {
        mHandler.post(() -> {
            if (mRoot != null) {
                updateChip(taskId);
                return;
            }
            mWindowManager = mContext.getSystemService(WindowManager.class);
            if (mWindowManager == null) {
                return;
            }
            mRoot = new FrameLayout(mContext);
            mRoot.setClickable(false);
            mRoot.setFocusable(false);

            mChip = new TextView(mContext);
            mChip.setTextColor(0xfff4f7f8);
            mChip.setTextSize(14);
            mChip.setTypeface(Typeface.DEFAULT_BOLD);
            mChip.setGravity(Gravity.CENTER);
            mChip.setBackground(chipBackground());
            mChip.setPadding(36, 16, 36, 16);
            updateChip(taskId);
            FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            chipParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            chipParams.topMargin = 52;
            mRoot.addView(mChip, chipParams);

            mActionLabel = new TextView(mContext);
            mActionLabel.setTextColor(0xff101418);
            mActionLabel.setTextSize(12);
            mActionLabel.setTypeface(Typeface.DEFAULT_BOLD);
            mActionLabel.setGravity(Gravity.CENTER);
            mActionLabel.setBackground(actionBackground());
            mActionLabel.setPadding(24, 10, 24, 10);
            mActionLabel.setAlpha(0f);
            FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            actionParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            actionParams.topMargin = 110;
            mRoot.addView(mActionLabel, actionParams);

            mDot = new View(mContext);
            mDot.setBackground(cursorBackground());
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    CURSOR_SIZE, CURSOR_SIZE);
            dotParams.leftMargin = 200;
            dotParams.topMargin = 400;
            mRoot.addView(mDot, dotParams);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            try {
                mWindowManager.addView(mRoot, params);
            } catch (RuntimeException ignored) {
                mRoot = null;
            }
        });
    }

    void hide() {
        mHandler.post(() -> {
            if (mRoot == null || mWindowManager == null) {
                return;
            }
            try {
                mWindowManager.removeView(mRoot);
            } catch (RuntimeException ignored) {
            }
            mRoot = null;
            mDot = null;
            mChip = null;
            mActionLabel = null;
        });
    }

    void pointerMove(float x, float y) {
        mHandler.post(() -> {
            moveDotNow(x, y);
            pulseDot();
        });
    }

    void pointerTap(float x, float y, boolean longPress) {
        mHandler.post(() -> {
            moveDotNow(x, y);
            showAction(longPress ? "Long press" : "Tap");
            pulseDot();
            addRipple(x, y, longPress);
        });
    }

    void pointerSwipe(float startX, float startY, float endX, float endY) {
        mHandler.post(() -> {
            moveDotNow(startX, startY);
            showAction("Swipe");
            addSwipeTrail(startX, startY, endX, endY);
            mHandler.postDelayed(() -> moveDotNow(endX, endY), 180);
        });
    }

    void typingIndicator() {
        mHandler.post(() -> {
            showAction("Typing");
            pulseDot();
        });
    }

    private void updateChip(String taskId) {
        if (mChip != null) {
            mChip.setText("OpenPhone is working");
        }
    }

    private void showAction(String label) {
        if (mActionLabel == null) {
            return;
        }
        mActionLabel.setText(label == null || label.isEmpty() ? "Working" : label);
        mActionLabel.animate().cancel();
        mActionLabel.setAlpha(1f);
        mActionLabel.animate().alpha(0f).setStartDelay(850).setDuration(220).start();
    }

    private void moveDotNow(float x, float y) {
        if (mDot == null) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mDot.getLayoutParams();
        params.leftMargin = Math.max(0, Math.round(x) - CURSOR_SIZE / 2);
        params.topMargin = Math.max(0, Math.round(y) - CURSOR_SIZE / 2);
        mDot.setLayoutParams(params);
    }

    private void pulseDot() {
        if (mDot == null) {
            return;
        }
        mDot.animate().cancel();
        mDot.animate().scaleX(1.35f).scaleY(1.35f).setDuration(80).withEndAction(
                () -> {
                    if (mDot != null) {
                        mDot.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }).start();
    }

    private void addRipple(float x, float y, boolean longPress) {
        if (mRoot == null) {
            return;
        }
        View ripple = new View(mContext);
        ripple.setBackground(rippleBackground(longPress));
        ripple.setAlpha(0.9f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(RIPPLE_SIZE, RIPPLE_SIZE);
        params.leftMargin = Math.max(0, Math.round(x) - RIPPLE_SIZE / 2);
        params.topMargin = Math.max(0, Math.round(y) - RIPPLE_SIZE / 2);
        mRoot.addView(ripple, params);
        ripple.setScaleX(0.25f);
        ripple.setScaleY(0.25f);
        ripple.animate().scaleX(longPress ? 1.45f : 1.15f)
                .scaleY(longPress ? 1.45f : 1.15f)
                .alpha(0f)
                .setDuration(longPress ? 520 : 320)
                .withEndAction(() -> removeTransientView(ripple))
                .start();
    }

    private void addSwipeTrail(float startX, float startY, float endX, float endY) {
        if (mRoot == null) {
            return;
        }
        float dx = endX - startX;
        float dy = endY - startY;
        int length = Math.max(48, Math.round((float) Math.hypot(dx, dy)));
        View trail = new View(mContext);
        trail.setBackground(trailBackground());
        trail.setAlpha(0.85f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(length, 10);
        params.leftMargin = Math.round((startX + endX) / 2f - length / 2f);
        params.topMargin = Math.round((startY + endY) / 2f - 5);
        mRoot.addView(trail, params);
        trail.setRotation((float) Math.toDegrees(Math.atan2(dy, dx)));
        trail.animate().alpha(0f).setStartDelay(320).setDuration(260)
                .withEndAction(() -> removeTransientView(trail)).start();
    }

    private void removeTransientView(View view) {
        if (mRoot == null || view == null) {
            return;
        }
        try {
            mRoot.removeView(view);
        } catch (RuntimeException ignored) {
        }
    }

    private static GradientDrawable chipBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xee171c20);
        drawable.setCornerRadius(42);
        drawable.setStroke(2, 0x6672e0c4);
        return drawable;
    }

    private static GradientDrawable cursorBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0xff72e0c4);
        drawable.setStroke(4, 0xdd101418);
        return drawable;
    }

    private static GradientDrawable actionBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xee72e0c4);
        drawable.setCornerRadius(28);
        drawable.setStroke(2, 0xaa101418);
        return drawable;
    }

    private static GradientDrawable rippleBackground(boolean longPress) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0x2272e0c4);
        drawable.setStroke(longPress ? 6 : 4, longPress ? 0xffffd166 : 0xff72e0c4);
        return drawable;
    }

    private static GradientDrawable trailBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xcc72e0c4);
        drawable.setCornerRadius(12);
        return drawable;
    }
}
