package com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.surface;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.leanback.app.PlaybackSupportFragment;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout.ResizeMode;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerEngine;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/**
 * Subclass of {@link PlaybackSupportFragment} that is responsible for providing a {@link SurfaceView}
 * and rendering video.
 */
public class SurfacePlaybackFragment extends PlaybackSupportFragment {
    private SurfaceWrapper mVideoSurfaceWrapper;
    private AspectRatioFrameLayout mVideoSurfaceRoot;
    private SubtitleView mLeanbackSubtitles;
    private int mSubtitlesPadding;
    private int mBackgroundResId;
    private float mAspectRatio;
    private float mPixelRatio = 1.0f;
    private float mVideoAspectRatio;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup root = (ViewGroup) super.onCreateView(inflater, container, savedInstanceState);
        if (root == null) {
            throw new IllegalStateException("Can't create root of SurfacePlaybackFragment");
        }
        mVideoSurfaceWrapper = (PlayerTweaksData.instance(getContext()).isTextureViewEnabled() ||
                PlayerData.instance(getContext()).getRotationAngle() != 0) ?
                new TextureViewWrapper(getContext(), root) : new SurfaceViewWrapper(getContext(), root);
        mVideoSurfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
        mVideoSurfaceRoot.setAspectRatioListener((targetAspectRatio, naturalAspectRatio, aspectRatioMismatch) -> scaleIfNeeded());
        mLeanbackSubtitles = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.leanback_subtitles);
        mSubtitlesPadding = mLeanbackSubtitles.getPaddingLeft();
        setBackgroundType(PlaybackSupportFragment.BG_LIGHT);
        return root;
    }

    /**
     * Adds {@link SurfaceHolder.Callback} to {@link SurfaceView}.
     */
    public void setSurfaceHolderCallback(SurfaceHolder.Callback callback) {
        if (mVideoSurfaceWrapper != null) {
            mVideoSurfaceWrapper.setSurfaceHolderCallback(callback);
        }
    }

    @Override
    protected void onVideoSizeChanged(int width, int height) {
        mVideoAspectRatio = ((float) width) / height;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());

        // Some Android TV devices distort portrait video when MediaCodec renders through a
        // SurfaceView. SmartTube's existing manual-rotation path already avoids the same device
        // behavior by switching to TextureView. Do the same automatically for portrait video,
        // while keeping the cheaper SurfaceView path for normal landscape playback.
        if (mVideoAspectRatio > 0 && mVideoAspectRatio < 1 &&
                mVideoSurfaceWrapper instanceof SurfaceViewWrapper) {
            if (switchToTextureView()) {
                ((PlayerEngine) this).restartEngine();
            }
        }
    }

    /**
     * Returns the surface view.
     */
    public View getSurfaceView() {
        return mVideoSurfaceWrapper.getSurfaceView();
    }

    @Override
    public void onDestroyView() {
        mVideoSurfaceWrapper = null;
        super.onDestroyView();
    }

    /** Returns the {@link ResizeMode}. */
    protected @ResizeMode int getResize() {
        return mVideoSurfaceRoot.getResizeMode();
    }

    /**
     * Sets the {@link ResizeMode}.
     *
     * @param resizeMode The {@link ResizeMode}.
     */
    protected void setResize(@ResizeMode int resizeMode) {
        mVideoSurfaceRoot.setResizeMode(resizeMode);
    }

    protected void setZoom(int percents) {
        mVideoSurfaceRoot.setZoom(percents);
    }

    protected void setAspect(float aspectRatio) {
        mAspectRatio = aspectRatio;
        mVideoSurfaceRoot.setAspectRatio(calculateAspectRatio());
    }

    protected void setRotation(int angle) {
        if (Helpers.floatEquals(mVideoSurfaceRoot.getRotation(), angle) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setRotation(angle);
        } else if (switchToTextureView()) {
            mVideoSurfaceRoot.setRotation(angle);
            ((PlayerEngine) this).restartEngine();
        }
    }

    protected void setFlipEnabled(boolean enabled) {
        float scaleX = enabled ? -1f : 1f;

        if (Helpers.floatEquals(mVideoSurfaceRoot.getScaleX(), scaleX) || mVideoSurfaceWrapper == null) {
            return;
        }

        if (mVideoSurfaceWrapper instanceof TextureViewWrapper) {
            mVideoSurfaceRoot.setScaleX(scaleX);
        } else if (switchToTextureView()) {
            mVideoSurfaceRoot.setScaleX(scaleX);
            ((PlayerEngine) this).restartEngine();
        }
    }

    private boolean switchToTextureView() {
        if (mVideoSurfaceWrapper == null || mVideoSurfaceWrapper instanceof TextureViewWrapper ||
                getView() == null) {
            return false;
        }

        mVideoSurfaceRoot.removeView(mVideoSurfaceWrapper.getSurfaceView());
        mVideoSurfaceWrapper = new TextureViewWrapper(getContext(), (ViewGroup) getView());
        mVideoSurfaceRoot.addView(mVideoSurfaceWrapper.getSurfaceView(), 0);
        return true;
    }

    private void scaleIfNeeded() {
        if (mVideoSurfaceWrapper == null ||
                mVideoSurfaceRoot.getWidth() == 0 || mVideoSurfaceRoot.getHeight() == 0) {
            return;
        }

        float angle = mVideoSurfaceRoot.getRotation();

        int width = mVideoSurfaceRoot.getWidth();
        int height = mVideoSurfaceRoot.getHeight();

        // TextureView needs its unrotated bounds adjusted when the user explicitly rotates video.
        // SurfaceView doesn't support that transform path and is replaced with TextureView by
        // setRotation(), so for the normal TV SurfaceView path the measured aspect-root bounds are
        // exactly the bounds the video surface must use.
        if (mVideoSurfaceWrapper instanceof TextureViewWrapper &&
                (Helpers.floatEquals(angle, 90) || Helpers.floatEquals(angle, 270))) {
            float ratio = width / ((float) height);

            width = height;
            height = (int) (height / ratio);
        }

        // Keep the actual rendering surface in lockstep with AspectRatioFrameLayout. In particular,
        // SurfaceView can otherwise retain the full-screen bounds even after the aspect container
        // has been narrowed for portrait video, stretching a 9:16 Short across the TV's 16:9 width.
        View surfaceView = mVideoSurfaceWrapper.getSurfaceView();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();

        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
            params.width = width;
            params.height = height;
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
        }
    }

    protected void setPixelRatio(float pixelRatio) {
        mPixelRatio = pixelRatio;
    }

    /**
     * Setup player's background used when controls are showed.
     * @param resId background
     */
    protected void setBackgroundResource(int resId) {
        if (resId <= 0 || mBackgroundResId == resId) {
            return;
        }

        View backgroundView = (View) Helpers.getField(this, "mBackgroundView");

        if (backgroundView != null) {
            backgroundView.setBackgroundResource(resId);
            mBackgroundResId = resId;
        }
    }

    protected void setGravity(int gravity) {
        ViewUtil.setGravity(mVideoSurfaceRoot, gravity);

        scaleSubtitles(gravity);
    }

    private void scaleSubtitles(int gravity) {
        if ((gravity & Gravity.START) == Gravity.START) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.START);
        } else if ((gravity & Gravity.END) == Gravity.END) {
            scaleSubtitles(scaleSubsWidth(), scaleSubsPadding(), Gravity.END);
        } else if ((gravity & Gravity.CENTER) == Gravity.CENTER) {
            scaleSubtitles(ViewGroup.LayoutParams.MATCH_PARENT, mSubtitlesPadding, Gravity.CENTER);
        }
    }

    private void scaleSubtitles(int width, int padding, int gravity) {
        ViewUtil.setWidth(mLeanbackSubtitles, width);
        ViewUtil.setPadding(mLeanbackSubtitles, padding);
        ViewUtil.setGravity(mLeanbackSubtitles, gravity);
    }

    private int scaleSubsWidth() {
        View parent = (View) mLeanbackSubtitles.getParent();
        return parent.getWidth() / 100 * calculateZoom();
    }

    private int scaleSubsPadding() {
        return mSubtitlesPadding / 100 * calculateZoom();
    }

    private int calculateZoom() {
        View parent = (View) mLeanbackSubtitles.getParent();
        int widthRatio = mVideoSurfaceRoot.getWidth() * 100 / parent.getWidth();
        return mVideoSurfaceRoot.getZoom() * widthRatio / 100;
    }

    private float calculateAspectRatio() {
        return (mAspectRatio == 0 ? mVideoAspectRatio : mAspectRatio) * mPixelRatio;
    }
}
