package org.openphone.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.view.animation.PathInterpolator;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class PointerOverlayController {
    interface ScreenAnswerProvider {
        void answerScreen(String prompt, ScreenAnswerCallback callback);
    }

    interface ScreenAnswerCallback {
        void onAnswer(String answer);
    }

    interface ConfirmationHandler {
        void approve();
        void deny();
    }

    private static final int CURSOR_SIZE = 34;
    private static final int RIPPLE_SIZE = 96;
    private static final int ISLAND_WIDTH = 620;
    private static final int ISLAND_HEIGHT = 96;
    // Expanded reply island grows downward from below the camera punch-hole,
    // so wrapped multi-line text never paints behind the camera.
    private static final int ISLAND_EXPANDED_WIDTH = 940;
    private static final int ISLAND_EXPANDED_MIN_HEIGHT = 140;
    private static final int ISLAND_EXPANDED_MAX_HEIGHT = 520;
    private static final int ISLAND_EXPANDED_PADDING_VERTICAL = 28;
    private static final int ISLAND_EXPANDED_PADDING_HORIZONTAL = 36;
    private static final int ISLAND_EXPANDED_TOP_OFFSET = 168;
    private static final long ISLAND_RESIZE_MS = 220L;
    private static final long REPLY_AUTO_COLLAPSE_MS = 7000L;
    private static final int REPLY_MAX_LINES = 8;
    private static final int CAMERA_RESERVED_WIDTH = 134;
    private static final int CAMERA_ISLAND_FALLBACK_TOP = 8;
    private static final int ACTION_LABEL_GAP = 12;
    private static final int SHEET_WIDTH_MARGIN = 42;
    private static final int SHEET_TOP_GAP = 18;
    private static final int SHEET_SWIPE_THRESHOLD = 54;
    private static final int GLOW_STROKE_WIDTH = 76;
    private static final int GLOW_BLUR_RADIUS = 118;
    private static final int GLOW_CORE_STROKE_WIDTH = 8;
    private static final int GLOW_EDGE_INSET = 1;
    private static final long OPEN_APP_HOLD_MS = 5000;
    private static final long MAX_VISIBLE_MS = 5 * 60 * 1000;
    private static final long DONE_VISIBLE_MS = 2200;
    private static final Set<PointerOverlayController> sControllers =
            Collections.newSetFromMap(new WeakHashMap<>());

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mWatchdogHide = this::hide;
    private final ScreenAnswerProvider mScreenAnswerProvider;
    private ConfirmationHandler mConfirmationHandler;
    private WindowManager mWindowManager;
    private FrameLayout mRoot;
    private FrameLayout mIslandRoot;
    private FrameLayout mSheetRoot;
    private WindowManager.LayoutParams mIslandParams;
    private WindowManager.LayoutParams mSheetParams;
    private GlowBorderView mGlowView;
    private View mDot;
    private TextView mLeftIslandText;
    private TextView mRightIslandText;
    private LinearLayout mIslandCompactRow;
    private LinearLayout mIslandExpandedColumn;
    private TextView mIslandBodyText;
    private LinearLayout mIslandActionRow;
    private TextView mApproveButton;
    private TextView mDenyButton;
    private TextView mSheetStatusText;
    private LinearLayout mSheetActionRows;
    private TextView mActionLabel;
    private boolean mOpenAppHoldTriggered;
    private boolean mIslandSheetGesture;
    private boolean mSheetExpanded;
    private String mMode = "idle";
    private String mStateDetail = "";
    private String mTranscriptText = "";
    private String mReplyText = "";
    private boolean mYoloActive;
    private int mWatchingCount;
    private final Runnable mReplyAutoCollapse = () -> {
        if ("reply".equals(mMode) || "transcript".equals(mMode)) {
            showMicButtonNow();
        }
    };
    private ValueAnimator mIslandResizeAnimator;

    PointerOverlayController(Context context) {
        this(context, null);
    }

    PointerOverlayController(Context context, ScreenAnswerProvider screenAnswerProvider) {
        mContext = context.getApplicationContext();
        mScreenAnswerProvider = screenAnswerProvider;
        synchronized (sControllers) {
            sControllers.add(this);
        }
    }

    /** Wire the inline Approve/Deny buttons in the expanded needs_review island. */
    void setConfirmationHandler(ConfirmationHandler handler) {
        mConfirmationHandler = handler;
    }

    void show(String taskId) {
        mHandler.post(() -> {
            mMode = "action_running";
            ensurePointerLayer();
            showPointerDot();
            ensureIslandWindow();
            updateIslandViews();
            armWatchdog();
        });
    }

    void hide() {
        mHandler.post(() -> {
            mHandler.removeCallbacks(mWatchdogHide);
            mHandler.removeCallbacks(mReplyAutoCollapse);
            if (mIslandResizeAnimator != null) {
                mIslandResizeAnimator.cancel();
                mIslandResizeAnimator = null;
            }
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
            removeAiSheet();
            mRoot = null;
            mIslandRoot = null;
            mSheetRoot = null;
            mIslandParams = null;
            mSheetParams = null;
            mDot = null;
            mGlowView = null;
            mLeftIslandText = null;
            mRightIslandText = null;
            mIslandCompactRow = null;
            mIslandBodyText = null;
            mSheetStatusText = null;
            mSheetActionRows = null;
            mActionLabel = null;
        });
    }

    void setIslandState(String state, String detail) {
        mHandler.post(() -> {
            String clean = state == null ? "" : state.trim();
            mStateDetail = detail == null ? "" : detail.trim();
            switch (clean) {
                case "idle":
                    showMicButtonNow();
                    return;
                case "listening":
                    showListeningNow();
                    return;
                case "thinking":
                case "answer_ready":
                case "needs_review":
                case "error":
                    mMode = clean;
                    removeAllPointerLayers();
                    ensureIslandWindow();
                    updateIslandViews();
                    mHandler.removeCallbacks(mWatchdogHide);
                    if ("answer_ready".equals(clean)) {
                        mHandler.postDelayed(this::showMicButtonNow, DONE_VISIBLE_MS);
                    }
                    return;
                case "action_running":
                    mMode = clean;
                    ensurePointerLayer();
                    showPointerDot();
                    ensureIslandWindow();
                    updateIslandViews();
                    armWatchdog();
                    return;
                case "watching":
                    // Watching is an idle variant: the agent is not running but
                    // background watchers are. Stay interactive like idle.
                    mMode = "watching";
                    removeAllPointerLayers();
                    ensureIslandWindow();
                    updateIslandViews();
                    mHandler.removeCallbacks(mWatchdogHide);
                    return;
                default:
            }
        });
    }

    void setYoloActive(boolean yoloActive) {
        mHandler.post(() -> {
            if (mYoloActive == yoloActive) {
                return;
            }
            mYoloActive = yoloActive;
            if (mIslandRoot != null) {
                mIslandRoot.setBackground(chipBackground(mYoloActive));
            }
            updateIslandViews();
        });
    }

    void setWatchingCount(int count) {
        mHandler.post(() -> {
            int bounded = Math.max(0, count);
            if (mWatchingCount == bounded) {
                return;
            }
            mWatchingCount = bounded;
            if ("idle".equals(mMode) || "watching".equals(mMode)) {
                mMode = bounded > 0 ? "watching" : "idle";
            }
            updateIslandViews();
        });
    }

    void showListening() {
        mHandler.post(this::showListeningNow);
    }

    private void showListeningNow() {
        mMode = "listening";
        mTranscriptText = "";
        ensurePointerLayer();
        hidePointerDot();
        ensureIslandWindow();
        updateIslandViews();
    }

    void showTranscript(String transcript) {
        mHandler.post(() -> {
            mMode = "transcript";
            mTranscriptText = transcript == null ? "" : transcript.trim();
            mReplyText = "";
            ensurePointerLayer();
            hidePointerDot();
            ensureIslandWindow();
            updateIslandViews();
        });
    }

    /**
     * Show the assistant's reply text in an expanded multi-line island.
     * Auto-collapses to mic after {@link #REPLY_AUTO_COLLAPSE_MS}; tap the
     * island to extend (the existing tap handler launches the full chat).
     */
    void showReply(String reply) {
        final String clean = reply == null ? "" : reply.trim();
        if (clean.isEmpty()) {
            return;
        }
        mHandler.post(() -> {
            mMode = "reply";
            mReplyText = clean;
            removeAllPointerLayers();
            ensureIslandWindow();
            updateIslandViews();
            mHandler.removeCallbacks(mReplyAutoCollapse);
            mHandler.postDelayed(mReplyAutoCollapse, REPLY_AUTO_COLLAPSE_MS);
            mHandler.removeCallbacks(mWatchdogHide);
        });
    }

    void showMicButton() {
        mHandler.post(this::showMicButtonNow);
    }

    private void showMicButtonNow() {
        mMode = mWatchingCount > 0 ? "watching" : "idle";
        mTranscriptText = "";
        mReplyText = "";
        mStateDetail = "";
        removeAllPointerLayers();
        ensureIslandWindow();
        updateIslandViews();
        mHandler.removeCallbacks(mWatchdogHide);
        mHandler.removeCallbacks(mReplyAutoCollapse);
    }

    public static void publishWatchingCount(int count) {
        ArrayList<PointerOverlayController> controllers;
        synchronized (sControllers) {
            controllers = new ArrayList<>(sControllers);
        }
        for (PointerOverlayController controller : controllers) {
            controller.setWatchingCount(count);
        }
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

    private void ensurePointerLayer() {
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
        if (mWindowManager == null) {
            return;
        }
        if (mRoot == null) {
            mRoot = new FrameLayout(mContext);
            mRoot.setClickable(false);
            mRoot.setFocusable(false);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            try {
                mWindowManager.addView(mRoot, params);
            } catch (RuntimeException ignored) {
                mRoot = null;
                return;
            }
        }

        if (mGlowView == null) {
            mGlowView = new GlowBorderView(mContext);
            mRoot.addView(mGlowView, 0, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        if (mActionLabel == null) {
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
            actionParams.topMargin = ISLAND_HEIGHT + ACTION_LABEL_GAP
                    + CAMERA_ISLAND_FALLBACK_TOP;
            mRoot.addView(mActionLabel, actionParams);
        }
        if (mDot == null) {
            mDot = new View(mContext);
            mDot.setBackground(cursorBackground());
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    CURSOR_SIZE, CURSOR_SIZE);
            dotParams.leftMargin = 200;
            dotParams.topMargin = 400;
            mRoot.addView(mDot, dotParams);
        }
    }

    private void ensureIslandWindow() {
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
        if (mWindowManager == null) {
            return;
        }
        // Only one island may exist across all controller instances
        // (service + activities); the latest claimant wins.
        ArrayList<PointerOverlayController> controllers;
        synchronized (sControllers) {
            controllers = new ArrayList<>(sControllers);
        }
        for (PointerOverlayController other : controllers) {
            if (other != this) {
                other.removeIslandNow();
            }
        }
        if (mIslandRoot != null) {
            return;
        }
        mIslandRoot = new FrameLayout(mContext);
        mIslandRoot.setClickable(true);
        mIslandRoot.setFocusable(false);
        mIslandRoot.setBackground(chipBackground(mYoloActive));
        mIslandRoot.setPadding(10, 0, 10, 0);
        mIslandRoot.setOnTouchListener(new View.OnTouchListener() {
            private final Runnable mOpenApp = new Runnable() {
                @Override
                public void run() {
                    mOpenAppHoldTriggered = true;
                    launchFullAssistant();
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mOpenAppHoldTriggered = false;
                        mIslandSheetGesture = false;
                        mHandler.postDelayed(mOpenApp, OPEN_APP_HOLD_MS);
                        view.setTag(Float.valueOf(event.getRawY()));
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        Object downY = view.getTag();
                        if (downY instanceof Float
                                && event.getRawY() - ((Float) downY).floatValue()
                                > SHEET_SWIPE_THRESHOLD) {
                            mHandler.removeCallbacks(mOpenApp);
                            showAiSheet(true);
                            mIslandSheetGesture = true;
                            view.setTag(null);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        mHandler.removeCallbacks(mOpenApp);
                        Object startY = view.getTag();
                        view.setTag(null);
                        if (mIslandSheetGesture) {
                            mIslandSheetGesture = false;
                            return true;
                        }
                        if (!mOpenAppHoldTriggered && startY instanceof Float
                                && event.getRawY() - ((Float) startY).floatValue()
                                > SHEET_SWIPE_THRESHOLD) {
                            showAiSheet(true);
                        } else if (!mOpenAppHoldTriggered) {
                            if ("action_running".equals(mMode) || "thinking".equals(mMode)) {
                                if (event.getX() < view.getWidth() / 2f) {
                                    launchVoiceCapture();
                                } else {
                                    launchStopAgent();
                                }
                            } else if ("listening".equals(mMode)) {
                                launchStopAgent();
                            } else if ("needs_review".equals(mMode)
                                    || "error".equals(mMode)) {
                                launchFullAssistant();
                            } else {
                                if (event.getX() < view.getWidth() / 2f) {
                                    showAiSheet(false);
                                } else {
                                    launchVoiceCapture();
                                }
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        mHandler.removeCallbacks(mOpenApp);
                        mIslandSheetGesture = false;
                        view.setTag(null);
                        return true;
                    default:
                        return true;
                }
            }
        });
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        mIslandRoot.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mIslandCompactRow = row;

        mLeftIslandText = islandText();
        row.addView(mLeftIslandText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        View cameraReserved = new View(mContext);
        row.addView(cameraReserved, new LinearLayout.LayoutParams(CAMERA_RESERVED_WIDTH,
                LinearLayout.LayoutParams.MATCH_PARENT));

        mRightIslandText = islandText();
        row.addView(mRightIslandText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // Expanded mode: a vertical column with the wrapped body text and an
        // optional action row (Approve/Deny in needs_review). Sits on top of
        // the compact row, hidden in compact modes.
        mIslandExpandedColumn = new LinearLayout(mContext);
        mIslandExpandedColumn.setOrientation(LinearLayout.VERTICAL);
        mIslandExpandedColumn.setGravity(Gravity.CENTER_VERTICAL);
        mIslandExpandedColumn.setVisibility(View.GONE);

        mIslandBodyText = new TextView(mContext);
        mIslandBodyText.setTextColor(0xfff4f7f8);
        mIslandBodyText.setTextSize(15);
        mIslandBodyText.setTypeface(Typeface.DEFAULT);
        mIslandBodyText.setLineSpacing(2f, 1.12f);
        mIslandBodyText.setMaxLines(REPLY_MAX_LINES);
        mIslandBodyText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mIslandBodyText.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        mIslandExpandedColumn.addView(mIslandBodyText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mIslandActionRow = new LinearLayout(mContext);
        mIslandActionRow.setOrientation(LinearLayout.HORIZONTAL);
        mIslandActionRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mIslandActionRow.setVisibility(View.GONE);
        mDenyButton = islandActionButton("Deny", 0xfff4f7f8, 0x33ffffff);
        mDenyButton.setOnClickListener(v -> {
            if (mConfirmationHandler != null) {
                mConfirmationHandler.deny();
            }
        });
        mApproveButton = islandActionButton("Approve", 0xff101418, 0xff20e36a);
        mApproveButton.setOnClickListener(v -> {
            if (mConfirmationHandler != null) {
                mConfirmationHandler.approve();
            }
        });
        LinearLayout.LayoutParams denyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        denyLp.rightMargin = 12;
        mIslandActionRow.addView(mDenyButton, denyLp);
        mIslandActionRow.addView(mApproveButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionRowLp.topMargin = 14;
        mIslandExpandedColumn.addView(mIslandActionRow, actionRowLp);

        FrameLayout.LayoutParams expandedColumnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        mIslandRoot.addView(mIslandExpandedColumn, expandedColumnParams);

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
        } catch (RuntimeException ignored) {
            mIslandRoot = null;
            mIslandParams = null;
            mLeftIslandText = null;
            mRightIslandText = null;
            mIslandCompactRow = null;
            mIslandBodyText = null;
        }
    }

    private void showAiSheet(boolean expanded) {
        ensureIslandWindow();
        ensureAiSheetWindow(expanded);
        updateAiSheetViews(expanded);
    }

    private void ensureAiSheetWindow(boolean expanded) {
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
        if (mWindowManager == null) {
            return;
        }
        mSheetExpanded = expanded;
        if (mSheetRoot != null) {
            return;
        }
        mSheetRoot = new FrameLayout(mContext);
        mSheetRoot.setClickable(true);
        mSheetRoot.setFocusable(false);
        mSheetRoot.setBackground(sheetBackground());
        mSheetRoot.setPadding(28, 22, 28, 22);

        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        mSheetRoot.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(mContext);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = sheetTitle("Ask OpenPhone");
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = sheetButton("Close");
        close.setOnClickListener(view -> removeAiSheet());
        header.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mSheetStatusText = sheetStatus();
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = 12;
        content.addView(mSheetStatusText, statusParams);

        mSheetActionRows = new LinearLayout(mContext);
        mSheetActionRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = 16;
        content.addView(mSheetActionRows, actionsParams);

        mSheetParams = new WindowManager.LayoutParams(
                Math.max(1, displayWidth() - SHEET_WIDTH_MARGIN * 2),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mSheetParams.gravity = Gravity.TOP | Gravity.LEFT;
        mSheetParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mSheetParams.x = SHEET_WIDTH_MARGIN;
        mSheetParams.y = CAMERA_ISLAND_FALLBACK_TOP + ISLAND_HEIGHT + SHEET_TOP_GAP;
        try {
            mWindowManager.addView(mSheetRoot, mSheetParams);
        } catch (RuntimeException ignored) {
            mSheetRoot = null;
            mSheetParams = null;
            mSheetStatusText = null;
            mSheetActionRows = null;
        }
    }

    private void updateAiSheetViews(boolean expanded) {
        if (mSheetRoot == null || mSheetActionRows == null) {
            return;
        }
        mSheetExpanded = expanded;
        if (mSheetStatusText != null) {
            mSheetStatusText.setText(sheetStatusText());
        }
        mSheetActionRows.removeAllViews();
        addSheetRow(sheetButton("Talk", view -> {
            removeAiSheet();
            launchVoiceCapture();
        }), sheetButton("Stop", view -> {
            removeAiSheet();
            launchStopAgent();
        }));
        addSheetRow(sheetButton("Chat", view -> {
            removeAiSheet();
            launchFullAssistant();
        }), sheetButton("Screen", view -> {
            requestScreenAnswer("What's on my screen?");
        }));
        if (expanded) {
            addSheetRow(sheetButton("Summarize", view -> {
                requestScreenAnswer("Summarize what is visible on my screen.");
            }), sheetButton("Search", view -> {
                removeAiSheet();
                launchGoal("Search the web for what I ask next.");
            }));
            addSheetRow(sheetButton("Notifications", view -> {
                removeAiSheet();
                launchGoal("Review my current notifications and tell me what needs attention.");
            }), sheetButton("Settings", view -> {
                removeAiSheet();
                launchFullAssistant();
            }));
        } else {
            TextView expand = sheetButton("More");
            expand.setOnClickListener(view -> updateAiSheetViews(true));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = 10;
            mSheetActionRows.addView(expand, params);
        }
    }

    private void requestScreenAnswer(String prompt) {
        if (mScreenAnswerProvider == null) {
            removeAiSheet();
            launchGoal(prompt);
            return;
        }
        ensureAiSheetWindow(true);
        mSheetExpanded = true;
        if (mSheetStatusText != null) {
            mSheetStatusText.setText("Reading screen");
        }
        if (mSheetActionRows != null) {
            mSheetActionRows.removeAllViews();
            mSheetActionRows.addView(sheetAnswerCard("Looking at the current screen..."),
                    fullWidthSheetParams(0));
        }
        mScreenAnswerProvider.answerScreen(prompt, answer ->
                mHandler.post(() -> showScreenAnswer(prompt, answer)));
    }

    private void showScreenAnswer(String prompt, String answer) {
        ensureAiSheetWindow(true);
        if (mSheetStatusText != null) {
            mSheetStatusText.setText("Screen answer");
        }
        if (mSheetActionRows == null) {
            return;
        }
        mSheetActionRows.removeAllViews();
        mSheetActionRows.addView(sheetAnswerCard(answer == null || answer.trim().isEmpty()
                ? "I could not read enough from this screen." : answer.trim()),
                fullWidthSheetParams(0));
        addSheetRow(sheetButton("Refresh", view -> requestScreenAnswer(prompt)),
                sheetButton("Chat", view -> {
                    removeAiSheet();
                    launchFullAssistant();
                }));
        TextView close = sheetButton("Close");
        close.setOnClickListener(view -> removeAiSheet());
        mSheetActionRows.addView(close, fullWidthSheetParams(10));
    }

    private void addSheetRow(TextView first, TextView second) {
        if (mSheetActionRows == null) {
            return;
        }
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = mSheetActionRows.getChildCount() == 0 ? 0 : 10;
        mSheetActionRows.addView(row, rowParams);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(first, buttonParams);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        secondParams.leftMargin = 10;
        row.addView(second, secondParams);
    }

    private static LinearLayout.LayoutParams fullWidthSheetParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private void removeAiSheet() {
        if (mWindowManager == null || mSheetRoot == null) {
            mSheetRoot = null;
            mSheetParams = null;
            mSheetStatusText = null;
            mSheetActionRows = null;
            return;
        }
        try {
            mWindowManager.removeView(mSheetRoot);
        } catch (RuntimeException ignored) {
        }
        mSheetRoot = null;
        mSheetParams = null;
        mSheetStatusText = null;
        mSheetActionRows = null;
    }

    private void removeIslandNow() {
        mHandler.removeCallbacks(mWatchdogHide);
        if (mIslandResizeAnimator != null) {
            mIslandResizeAnimator.cancel();
            mIslandResizeAnimator = null;
        }
        if (mWindowManager == null) {
            mWindowManager = mContext.getSystemService(WindowManager.class);
        }
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
        removeAiSheet();
        mRoot = null;
        mIslandRoot = null;
        mIslandParams = null;
        mDot = null;
        mGlowView = null;
        mLeftIslandText = null;
        mRightIslandText = null;
        mIslandCompactRow = null;
        mIslandBodyText = null;
        mActionLabel = null;
    }

    private String sheetStatusText() {
        if ("action_running".equals(mMode)) {
            return "Agent running";
        }
        if ("thinking".equals(mMode)) {
            return "Thinking";
        }
        if ("listening".equals(mMode)) {
            return "Listening";
        }
        if ("transcript".equals(mMode)) {
            return mTranscriptText == null || mTranscriptText.isEmpty()
                    ? "Heard your request" : mTranscriptText;
        }
        if ("answer_ready".equals(mMode)) {
            return "Done";
        }
        if ("needs_review".equals(mMode)) {
            return mStateDetail == null || mStateDetail.isEmpty()
                    ? "Approval needed" : mStateDetail;
        }
        if ("error".equals(mMode)) {
            return mStateDetail == null || mStateDetail.isEmpty()
                    ? "Something failed" : mStateDetail;
        }
        if ("watching".equals(mMode)) {
            return mWatchingCount > 1
                    ? mWatchingCount + " watchers active" : "Watching in background";
        }
        return "Ready";
    }

    private TextView islandText() {
        TextView view = new TextView(mContext);
        view.setTextColor(0xfff4f7f8);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private void updateIslandViews() {
        if (mLeftIslandText == null || mRightIslandText == null
                || mIslandBodyText == null) {
            return;
        }
        boolean expanded = "reply".equals(mMode)
                || "needs_review".equals(mMode)
                || ("transcript".equals(mMode) && hasMultiLineTranscript());
        if (expanded) {
            applyExpandedIslandLayout();
        } else {
            applyCompactIslandLayout();
        }
        if ("answer_ready".equals(mMode)) {
            mLeftIslandText.setText("OK");
            mRightIslandText.setText("✓");
            mRightIslandText.setTextColor(0xff20e36a);
        } else if ("listening".equals(mMode)) {
            mLeftIslandText.setText("Listening");
            mRightIslandText.setText("Stop");
            mRightIslandText.setTextColor(0xffff6b6b);
        } else if ("transcript".equals(mMode)) {
            if (expanded) {
                mIslandBodyText.setText(mTranscriptText == null ? "" : mTranscriptText);
            } else {
                mLeftIslandText.setText("You said");
                mRightIslandText.setText(mTranscriptText == null ? "" : mTranscriptText);
                mRightIslandText.setTextColor(0xfff4f7f8);
            }
        } else if ("reply".equals(mMode)) {
            mIslandBodyText.setText(mReplyText == null ? "" : mReplyText);
        } else if ("thinking".equals(mMode)) {
            mLeftIslandText.setText(yoloPrefix() + "Thinking");
            mRightIslandText.setText("…");
            mRightIslandText.setTextColor(0xff9ab8ff);
        } else if ("action_running".equals(mMode)) {
            mLeftIslandText.setText(yoloPrefix() + "Talk");
            mRightIslandText.setText("Stop");
            mRightIslandText.setTextColor(0xffff6b6b);
        } else if ("needs_review".equals(mMode)) {
            String body = mStateDetail == null || mStateDetail.isEmpty()
                    ? "Approval needed" : mStateDetail;
            mIslandBodyText.setText(body);
        } else if ("error".equals(mMode)) {
            mLeftIslandText.setText("Error");
            mRightIslandText.setText("!");
            mRightIslandText.setTextColor(0xffff6b6b);
        } else if ("watching".equals(mMode)) {
            mLeftIslandText.setText(yoloPrefix() + "AI");
            mRightIslandText.setText(mWatchingCount > 1
                    ? "◎ " + mWatchingCount : "◎");
            mRightIslandText.setTextColor(0xff9ab8ff);
        } else {
            mLeftIslandText.setText(yoloPrefix() + "AI");
            mRightIslandText.setText("◉");
            mRightIslandText.setTextColor(0xff72e0c4);
        }
        if (mSheetRoot != null) {
            updateAiSheetViews(mSheetExpanded);
        }
    }

    private boolean hasMultiLineTranscript() {
        return mTranscriptText != null && mTranscriptText.length() > 28;
    }

    private void applyCompactIslandLayout() {
        if (mIslandCompactRow == null || mIslandExpandedColumn == null) {
            return;
        }
        mIslandCompactRow.setVisibility(View.VISIBLE);
        mIslandExpandedColumn.setVisibility(View.GONE);
        if (mIslandActionRow != null) {
            mIslandActionRow.setVisibility(View.GONE);
        }
        if (mIslandRoot != null) {
            mIslandRoot.setPadding(10, 0, 10, 0);
        }
        animateIslandTo(ISLAND_WIDTH, ISLAND_HEIGHT, CAMERA_ISLAND_FALLBACK_TOP);
    }

    private void applyExpandedIslandLayout() {
        if (mIslandCompactRow == null || mIslandExpandedColumn == null) {
            return;
        }
        mIslandCompactRow.setVisibility(View.GONE);
        mIslandExpandedColumn.setVisibility(View.VISIBLE);
        if (mIslandActionRow != null) {
            mIslandActionRow.setVisibility(
                    "needs_review".equals(mMode) ? View.VISIBLE : View.GONE);
        }
        if (mIslandRoot != null) {
            mIslandRoot.setPadding(ISLAND_EXPANDED_PADDING_HORIZONTAL,
                    ISLAND_EXPANDED_PADDING_VERTICAL,
                    ISLAND_EXPANDED_PADDING_HORIZONTAL,
                    ISLAND_EXPANDED_PADDING_VERTICAL);
        }
        int target = measureExpandedHeight();
        animateIslandTo(ISLAND_EXPANDED_WIDTH, target, ISLAND_EXPANDED_TOP_OFFSET);
    }

    private int measureExpandedHeight() {
        if (mIslandExpandedColumn == null) {
            return ISLAND_EXPANDED_MIN_HEIGHT;
        }
        int contentWidth = ISLAND_EXPANDED_WIDTH - ISLAND_EXPANDED_PADDING_HORIZONTAL * 2;
        mIslandExpandedColumn.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int columnHeight = mIslandExpandedColumn.getMeasuredHeight();
        int target = columnHeight + ISLAND_EXPANDED_PADDING_VERTICAL * 2;
        return Math.max(ISLAND_EXPANDED_MIN_HEIGHT,
                Math.min(ISLAND_EXPANDED_MAX_HEIGHT, target));
    }

    private TextView islandActionButton(String label, int textColor, int backgroundColor) {
        TextView button = new TextView(mContext);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(28, 14, 28, 14);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(28f);
        bg.setColor(backgroundColor);
        button.setBackground(bg);
        return button;
    }

    private void animateIslandTo(int targetWidth, int targetHeight, int targetTop) {
        if (mWindowManager == null || mIslandRoot == null || mIslandParams == null) {
            return;
        }
        if (mIslandResizeAnimator != null) {
            mIslandResizeAnimator.cancel();
            mIslandResizeAnimator = null;
        }
        final int startWidth = mIslandParams.width;
        final int startHeight = mIslandParams.height;
        final int startTop = mIslandParams.y;
        final int startX = mIslandParams.x;
        final int targetX = Math.max(0, (displayWidth() - targetWidth) / 2);
        if (startWidth == targetWidth && startHeight == targetHeight
                && startTop == targetTop && startX == targetX) {
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ISLAND_RESIZE_MS);
        // Material standard easing: starts a bit fast, settles softly.
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(a -> {
            if (mWindowManager == null || mIslandRoot == null
                    || mIslandParams == null) {
                return;
            }
            float t = (float) a.getAnimatedValue();
            mIslandParams.width = (int) (startWidth + (targetWidth - startWidth) * t);
            mIslandParams.height = (int) (startHeight + (targetHeight - startHeight) * t);
            mIslandParams.x = (int) (startX + (targetX - startX) * t);
            mIslandParams.y = (int) (startTop + (targetTop - startTop) * t);
            try {
                mWindowManager.updateViewLayout(mIslandRoot, mIslandParams);
            } catch (RuntimeException ignored) {
            }
        });
        mIslandResizeAnimator = animator;
        animator.start();
    }

    private String yoloPrefix() {
        return mYoloActive ? "⚡ " : "";
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
            if (child != mGlowView && child != mActionLabel && child != mDot) {
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
        mGlowView = null;
        mActionLabel = null;
    }

    private static void removeAllPointerLayers() {
        ArrayList<PointerOverlayController> controllers;
        synchronized (sControllers) {
            controllers = new ArrayList<>(sControllers);
        }
        for (PointerOverlayController controller : controllers) {
            controller.mHandler.post(controller::removePointerLayer);
        }
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
        Intent intent = new Intent(mContext, AgentControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(AgentControlActivity.EXTRA_START_VOICE, true);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private void launchStopAgent() {
        Intent intent = new Intent(mContext, AgentControlActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(AgentControlActivity.EXTRA_STOP_AGENT, true);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private void launchFullAssistant() {
        Intent intent = new Intent(mContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private void launchGoal(String goal) {
        Intent intent = new Intent(mContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("org.openphone.assistant.extra.GOAL_BASE64",
                Base64.encodeToString(goal.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Base64.NO_WRAP));
        intent.putExtra("org.openphone.assistant.extra.RUN", true);
        try {
            mContext.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private TextView sheetTitle(String text) {
        TextView view = new TextView(mContext);
        view.setText(text);
        view.setTextColor(0xfff4f7f8);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private TextView sheetStatus() {
        TextView view = new TextView(mContext);
        view.setTextColor(0xccf4f7f8);
        view.setTextSize(13);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }

    private TextView sheetButton(String text) {
        return sheetButton(text, null);
    }

    private TextView sheetButton(String text, View.OnClickListener listener) {
        TextView view = new TextView(mContext);
        view.setText(text);
        view.setTextColor(0xfff4f7f8);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setPadding(18, 14, 18, 14);
        view.setBackground(sheetButtonBackground());
        if (listener != null) {
            view.setOnClickListener(listener);
        }
        return view;
    }

    private TextView sheetAnswerCard(String text) {
        TextView view = new TextView(mContext);
        view.setText(text == null ? "" : text);
        view.setTextColor(0xfff4f7f8);
        view.setTextSize(14);
        view.setLineSpacing(2f, 1.05f);
        view.setPadding(20, 18, 20, 18);
        view.setBackground(sheetAnswerBackground());
        return view;
    }

    private static GradientDrawable chipBackground(boolean yoloActive) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xff000000);
        drawable.setCornerRadius(ISLAND_HEIGHT / 2f);
        if (yoloActive) {
            drawable.setStroke(3, 0xffffd166);
        }
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

    private static GradientDrawable sheetBackground() {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xee11161d, 0xee1b2330, 0xee101418});
        drawable.setCornerRadius(34);
        drawable.setStroke(2, 0x5572e0c4);
        return drawable;
    }

    private static GradientDrawable sheetButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0x332f3946);
        drawable.setCornerRadius(24);
        drawable.setStroke(1, 0x4472e0c4);
        return drawable;
    }

    private static GradientDrawable sheetAnswerBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0x22364352);
        drawable.setCornerRadius(24);
        drawable.setStroke(1, 0x3372e0c4);
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

    private static final class GlowBorderView extends View {
        private final Paint mBlurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBounds = new RectF();
        private float mShift;

        GlowBorderView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            mBlurPaint.setStyle(Paint.Style.STROKE);
            mBlurPaint.setStrokeWidth(GLOW_STROKE_WIDTH);
            mBlurPaint.setStrokeCap(Paint.Cap.ROUND);
            mBlurPaint.setStrokeJoin(Paint.Join.ROUND);
            mBlurPaint.setMaskFilter(new BlurMaskFilter(GLOW_BLUR_RADIUS,
                    BlurMaskFilter.Blur.NORMAL));

            mCorePaint.setStyle(Paint.Style.STROKE);
            mCorePaint.setStrokeWidth(GLOW_CORE_STROKE_WIDTH);
            mCorePaint.setStrokeCap(Paint.Cap.ROUND);
            mCorePaint.setStrokeJoin(Paint.Join.ROUND);
            mCorePaint.setAlpha(255);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            Shader shader = gradient(width, height);
            mBlurPaint.setShader(shader);
            mCorePaint.setShader(shader);
            float inset = GLOW_EDGE_INSET;
            mBounds.set(inset, inset, width - inset, height - inset);
            float radius = displayCornerRadius(width, height);
            canvas.drawRoundRect(mBounds, radius, radius, mBlurPaint);
            canvas.drawRoundRect(mBounds, radius, radius, mCorePaint);

            mShift = (mShift + 0.008f) % 1f;
            postInvalidateDelayed(16);
        }

        private Shader gradient(int width, int height) {
            int[] colors = {
                    Color.argb(255, 93, 220, 255),
                    Color.argb(255, 148, 108, 255),
                    Color.argb(255, 255, 86, 168),
                    Color.argb(255, 255, 204, 108),
                    Color.argb(255, 93, 220, 255)
            };
            float startX = -width * 0.4f + width * 1.8f * mShift;
            float endX = startX + width * 1.2f;
            return new LinearGradient(startX, 0, endX, height, colors, null,
                    Shader.TileMode.MIRROR);
        }

        private float displayCornerRadius(int width, int height) {
            float fallback = Math.min(145, Math.min(width, height) * 0.135f);
            if (getRootWindowInsets() == null) {
                return Math.max(1f, fallback - GLOW_CORE_STROKE_WIDTH / 2f);
            }
            float radius = 0f;
            int[] positions = {
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT
            };
            for (int position : positions) {
                RoundedCorner corner = getRootWindowInsets().getRoundedCorner(position);
                if (corner != null) {
                    radius = Math.max(radius, corner.getRadius());
                }
            }
            if (radius <= 0f) {
                radius = fallback;
            }
            return Math.max(1f, (radius * 1.40f) - GLOW_CORE_STROKE_WIDTH / 2f);
        }
    }
}
