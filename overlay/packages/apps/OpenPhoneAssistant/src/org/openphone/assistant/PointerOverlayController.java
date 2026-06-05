package org.openphone.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PointerOverlayController {
    private static final int CURSOR_SIZE = 34;
    private static final int RIPPLE_SIZE = 96;
    private static final int ISLAND_WIDTH = 286;
    private static final int ISLAND_HEIGHT = 58;
    private static final int CAMERA_RESERVED_WIDTH = 70;
    private static final int CAMERA_ISLAND_FALLBACK_TOP = 14;
    private static final int ACTION_LABEL_GAP = 12;
    private static final long MAX_VISIBLE_MS = 5 * 60 * 1000;
    private static final long DONE_VISIBLE_MS = 2200;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdogHide = this::hide;
    private WindowManager mWindowManager;
    private FrameLayout mRoot;
    private FrameLayout mIslandRoot;
    private WindowManager.LayoutParams mIslandParams;
    private View mDot;
    private TextView mLeftIslandText;
    private TextView mRightIslandText;
    private TextView mActionLabel;
    private String mMode = "mic";

    PointerOverlayController(Context context) {
        mContext = context.getApplicationContext();
    }

    void show(String taskId) {
        mHandler.post(() -> {
            mMode = "working";
            if (mRoot != null) {
                ensureIslandWindow();
                updateIslandViews();
                showPointerDot();
                armWatchdog();
                return;
            }
            mWindowManager = mContext.getSystemService(WindowManager.class);
            if (mWindowManager == null) {
                return;
            }
            mRoot = new FrameLayout(mContext);
            mRoot.setClickable(false);
            mRoot.setFocusable(false);

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
            actionParams.topMargin = ISLAND_HEIGHT + ACTION_LABEL_GAP + CAMERA_ISLAND_FALLBACK_TOP;
            mRoot.addView(mActionLabel, actionParams);

            mDot = new View(mContext);
            mDot.setBackground(cursorBackground());
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    CURSOR_SIZE, CURSOR_SIZE);
            dotParams.leftMargin = 200;
            dotParams.topMargin = 400;
            mRoot.addView(mDot, dotParams);
            showPointerDot();

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            try {
                mWindowManager.addView(mRoot, params);
                ensureIslandWindow();
                armWatchdog();
            } catch (RuntimeException ignored) {
                mRoot = null;
            }
        });
    }

    void hide() {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mWatchdogHide);
            if (mWindowManager == null) {
                return;
            }
            removePointerLayer();
            if (mIslandRoot != null) {
                try {
                    mWindowManager.removeView(mIslandRoot);
                } catch (RuntimeException ignored) {
                }
            }
            mRoot = null;
            mIslandRoot = null;
            mIslandParams = null;
            mDot = null;
            mLeftIslandText = null;
            mRightIslandText = null;
            mActionLabel = null;
        });
    }

    void setStatus(String text) {
        mHandler.post(() -> {
            if ("Done".equals(text)) {
                showDoneThenMic();
            } else if ("Agent is working".equals(text) || "Starting".equals(text)
                    || "Listening...".equals(text) || "Waiting for task".equals(text)
                    || "Continuing".equals(text)) {
                mMode = "working";
                ensureIslandWindow();
                updateIslandViews();
            }
        });
    }

    void showMicButton() {
        mHandler.post(() -> {
            mMode = "mic";
            removePointerLayer();
            ensureIslandWindow();
            updateIslandViews();
            mHandler.removeCallbacks(mWatchdogHide);
        });
    }

    void showDoneThenMic() {
        mHandler.post(() -> {
            mMode = "done";
            removePointerLayer();
            ensureIslandWindow();
            updateIslandViews();
            mHandler.removeCallbacks(mWatchdogHide);
            mHandler.postDelayed(this::showMicButton, DONE_VISIBLE_MS);
        });
    }

    void pointerMove(float x, float y) {
        mHandler.post(() -> {
            armWatchdog();
            moveDotNow(x, y);
            pulseDot();
        });
    }

    void pointerTap(float x, float y, boolean longPress) {
        mHandler.post(() -> {
            armWatchdog();
            moveDotNow(x, y);
            showAction(longPress ? "Long press" : "Tap");
            pulseDot();
            addRipple(x, y, longPress);
        });
    }

    void pointerSwipe(float startX, float startY, float endX, float endY) {
        mHandler.post(() -> {
            armWatchdog();
            moveDotNow(startX, startY);
            showAction("Swipe");
            addSwipeTrail(startX, startY, endX, endY);
            mHandler.postDelayed(() -> moveDotNow(endX, endY), 180);
        });
    }

    void typingIndicator() {
        mHandler.post(() -> {
            armWatchdog();
            showAction("Typing");
            pulseDot();
        });
    }

    private void armWatchdog() {
        mHandler.removeCallbacks(mWatchdogHide);
        mHandler.postDelayed(mWatchdogHide, MAX_VISIBLE_MS);
    }

    private void ensureIslandWindow() {
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
        if (mWindowManager == null || mIslandRoot != null) {
            return;
        }
        mIslandRoot = new FrameLayout(mContext);
        mIslandRoot.setClickable(true);
        mIslandRoot.setFocusable(false);
        mIslandRoot.setBackground(chipBackground());
        mIslandRoot.setPadding(10, 0, 10, 0);
        mIslandRoot.setOnClickListener(view -> launchVoiceCapture());
        mIslandRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            positionCameraIsland(insets);
            return insets;
        });

        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        mIslandRoot.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        mLeftIslandText = islandText();
        row.addView(mLeftIslandText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        View cameraReserved = new View(mContext);
        row.addView(cameraReserved, new LinearLayout.LayoutParams(CAMERA_RESERVED_WIDTH,
                LinearLayout.LayoutParams.MATCH_PARENT));

        mRightIslandText = islandText();
        row.addView(mRightIslandText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        mIslandParams = new WindowManager.LayoutParams(
                ISLAND_WIDTH,
                ISLAND_HEIGHT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mIslandParams.gravity = Gravity.TOP | Gravity.LEFT;
        mIslandParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mIslandParams.x = Math.max(0, (displayWidth() - ISLAND_WIDTH) / 2);
        mIslandParams.y = CAMERA_ISLAND_FALLBACK_TOP;
        try {
            mWindowManager.addView(mIslandRoot, mIslandParams);
            updateIslandViews();
            mIslandRoot.post(() -> positionCameraIsland(mIslandRoot.getRootWindowInsets()));
        } catch (RuntimeException ignored) {
            mIslandRoot = null;
            mIslandParams = null;
            mLeftIslandText = null;
            mRightIslandText = null;
        }
    }

    private TextView islandText() {
        TextView view = new TextView(mContext);
        view.setTextColor(0xfff4f7f8);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        return view;
    }

    private void updateIslandViews() {
        if (mLeftIslandText == null || mRightIslandText == null) {
            return;
        }
        if ("done".equals(mMode)) {
            mLeftIslandText.setText("Done");
            mRightIslandText.setText("✓");
            mRightIslandText.setTextColor(0xff20e36a);
        } else if ("working".equals(mMode)) {
            mLeftIslandText.setText("AI");
            mRightIslandText.setText("Working");
            mRightIslandText.setTextColor(0xfff4f7f8);
        } else {
            mLeftIslandText.setText("Ask");
            mRightIslandText.setText("Mic");
            mRightIslandText.setTextColor(0xff72e0c4);
        }
    }

    private void positionCameraIsland(WindowInsets insets) {
        if (mWindowManager == null || mIslandRoot == null || mIslandParams == null) {
            return;
        }
        Rect cutout = centeredCutout(insets);
        int displayWidth = displayWidth();
        if (isCenteredCutout(cutout, displayWidth)) {
            mIslandParams.x = Math.max(0, cutout.centerX() - ISLAND_WIDTH / 2);
            mIslandParams.y = Math.max(0, cutout.centerY() - ISLAND_HEIGHT / 2);
        } else {
            mIslandParams.x = Math.max(0, (displayWidth - ISLAND_WIDTH) / 2);
            mIslandParams.y = CAMERA_ISLAND_FALLBACK_TOP;
        }
        try {
            mWindowManager.updateViewLayout(mIslandRoot, mIslandParams);
        } catch (RuntimeException ignored) {
        }
    }

    private void positionActionLabel() {
        if (mRoot == null || mActionLabel == null) {
            return;
        }
        int actionWidth = measuredWidth(mActionLabel);
        FrameLayout.LayoutParams actionParams =
                (FrameLayout.LayoutParams) mActionLabel.getLayoutParams();
        actionParams.gravity = Gravity.TOP | Gravity.LEFT;
        int rootWidth = mRoot.getWidth();
        actionParams.leftMargin = rootWidth > 0 && actionWidth > 0
                ? Math.max(0, (rootWidth - actionWidth) / 2) : 0;
        actionParams.topMargin = (mIslandParams == null
                ? CAMERA_ISLAND_FALLBACK_TOP : mIslandParams.y)
                + ISLAND_HEIGHT + ACTION_LABEL_GAP;
        mActionLabel.setLayoutParams(actionParams);
    }

    private int displayWidth() {
        if (mRoot != null && mRoot.getWidth() > 0) {
            return mRoot.getWidth();
        }
        if (mWindowManager != null) {
            try {
                return mWindowManager.getCurrentWindowMetrics().getBounds().width();
            } catch (RuntimeException ignored) {
            }
        }
        return mContext.getResources().getDisplayMetrics().widthPixels;
    }

    private static boolean isCenteredCutout(Rect cutout, int displayWidth) {
        if (cutout == null || displayWidth <= 0) {
            return false;
        }
        int displayCenter = displayWidth / 2;
        return Math.abs(cutout.centerX() - displayCenter) < displayWidth / 4;
    }

    private static Rect centeredCutout(WindowInsets insets) {
        if (insets == null) {
            return null;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout == null || cutout.getBoundingRects().isEmpty()) {
            return null;
        }
        Rect best = null;
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect == null || rect.isEmpty() || rect.top > 160) {
                continue;
            }
            if (best == null || rect.width() > best.width()) {
                best = rect;
            }
        }
        return best;
    }

    private static int measuredWidth(View view) {
        if (view.getWidth() > 0) {
            return view.getWidth();
        }
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return view.getMeasuredWidth();
    }

    private static int measuredHeight(View view) {
        if (view.getHeight() > 0) {
            return view.getHeight();
        }
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return view.getMeasuredHeight();
    }

    private void showAction(String label) {
        if (mActionLabel == null) {
            return;
        }
        positionActionLabel();
        mActionLabel.setText(label == null || label.isEmpty() ? "Working" : label);
        mActionLabel.animate().cancel();
        mActionLabel.setAlpha(1f);
        mActionLabel.animate().alpha(0f).setStartDelay(850).setDuration(220).start();
    }

    private void moveDotNow(float x, float y) {
        if (mDot == null) {
            return;
        }
        showPointerDot();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mDot.getLayoutParams();
        params.leftMargin = Math.max(0, Math.round(x) - CURSOR_SIZE / 2);
        params.topMargin = Math.max(0, Math.round(y) - CURSOR_SIZE / 2);
        mDot.setLayoutParams(params);
    }

    private void pulseDot() {
        if (mDot == null) {
            return;
        }
        showPointerDot();
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

    private void clearTransientViews() {
        if (mRoot == null) {
            return;
        }
        for (int i = mRoot.getChildCount() - 1; i >= 0; i--) {
            View child = mRoot.getChildAt(i);
            if (child != mActionLabel && child != mDot) {
                child.animate().cancel();
                mRoot.removeViewAt(i);
            }
        }
        if (mActionLabel != null) {
            mActionLabel.animate().cancel();
            mActionLabel.setAlpha(0f);
        }
        if (mDot != null) {
            mDot.animate().cancel();
            mDot.setScaleX(1f);
            mDot.setScaleY(1f);
        }
    }

    private void removePointerLayer() {
        if (mRoot == null || mWindowManager == null) {
            return;
        }
        clearTransientViews();
        try {
            mWindowManager.removeView(mRoot);
        } catch (RuntimeException ignored) {
        }
        mRoot = null;
        mDot = null;
        mActionLabel = null;
    }

    private void showPointerDot() {
        if (mDot != null) {
            mDot.setVisibility(View.VISIBLE);
        }
    }

    private void hidePointerDot() {
        if (mDot != null) {
            mDot.animate().cancel();
            mDot.setVisibility(View.GONE);
        }
    }

    private void launchVoiceCapture() {
        Intent intent = new Intent(mContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_START_VOICE, true);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private static GradientDrawable chipBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xff000000);
        drawable.setCornerRadius(ISLAND_HEIGHT / 2f);
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
