package org.openphone.assistant;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

final class PointerOverlayController {
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private WindowManager mWindowManager;
    private FrameLayout mRoot;
    private View mDot;
    private TextView mChip;

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
            mChip.setBackgroundColor(0xcc101418);
            mChip.setPadding(24, 12, 24, 12);
            updateChip(taskId);
            FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            chipParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            chipParams.topMargin = 72;
            mRoot.addView(mChip, chipParams);

            mDot = new View(mContext);
            mDot.setBackgroundColor(0xff00d1b2);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(28, 28);
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
        });
    }

    void pointerMove(float x, float y) {
        mHandler.post(() -> {
            if (mDot == null) {
                return;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mDot.getLayoutParams();
            params.leftMargin = Math.max(0, Math.round(x) - 14);
            params.topMargin = Math.max(0, Math.round(y) - 14);
            mDot.setLayoutParams(params);
            mDot.animate().scaleX(1.35f).scaleY(1.35f).setDuration(80).withEndAction(
                    () -> mDot.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
        });
    }

    private void updateChip(String taskId) {
        if (mChip != null) {
            mChip.setText(taskId == null ? "OpenPhone agent active" : "OpenPhone agent " + taskId);
        }
    }
}
