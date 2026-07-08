/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView with pinch-to-zoom, drag-to-pan, and double-tap zoom, designed to live inside a
 * ViewPager2 page: at base scale, horizontal drags are left to the pager; while zoomed (or during a
 * pinch), the parent is told not to intercept so drags pan the image instead.
 */
public class ZoomImageView extends AppCompatImageView {

  /** Maximum zoom relative to the fitted image. */
  private static final float MAX_SCALE_FACTOR = 6f;

  /** Zoom level applied by a double tap, relative to the fitted image. */
  private static final float DOUBLE_TAP_SCALE_FACTOR = 2.5f;

  private final Matrix displayMatrix = new Matrix();
  private final float[] matrixValues = new float[9];
  private final RectF contentRect = new RectF();
  private final ScaleGestureDetector scaleDetector;
  private final GestureDetector gestureDetector;

  /** Scale that makes the image fit the view exactly (computed per image/layout). */
  private float baseScale = 1f;

  private @Nullable ValueAnimator zoomAnimator;

  public ZoomImageView(Context context) {
    this(context, null);
  }

  public ZoomImageView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    scaleDetector =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                float current = getCurrentScale();
                float factor = detector.getScaleFactor();
                float target = current * factor;
                float min = baseScale;
                float max = baseScale * MAX_SCALE_FACTOR;
                if (target < min) {
                  factor = min / current;
                } else if (target > max) {
                  factor = max / current;
                }
                displayMatrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                clampTranslation();
                setImageMatrix(displayMatrix);
                return true;
              }
            });
    gestureDetector =
        new GestureDetector(
            context,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onScroll(
                  MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (isZoomed()) {
                  displayMatrix.postTranslate(-distanceX, -distanceY);
                  clampTranslation();
                  setImageMatrix(displayMatrix);
                  return true;
                }
                return false;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                float target = isZoomed() ? baseScale : baseScale * DOUBLE_TAP_SCALE_FACTOR;
                animateScaleTo(target, e.getX(), e.getY());
                return true;
              }
            });
  }

  @Override
  public void setImageDrawable(@Nullable Drawable drawable) {
    super.setImageDrawable(drawable);
    resetToBase();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    resetToBase();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // While zoomed or pinching, keep the parent (ViewPager2) from stealing the gesture
    boolean keepGesture = isZoomed() || scaleDetector.isInProgress() || event.getPointerCount() > 1;
    if (getParent() != null) {
      getParent().requestDisallowInterceptTouchEvent(keepGesture);
    }
    scaleDetector.onTouchEvent(event);
    gestureDetector.onTouchEvent(event);
    return true;
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  /** Returns true when the image is zoomed in beyond its fitted size. */
  public boolean isZoomed() {
    return getCurrentScale() > baseScale * 1.01f;
  }

  private float getCurrentScale() {
    displayMatrix.getValues(matrixValues);
    return matrixValues[Matrix.MSCALE_X];
  }

  /** Fits the image inside the view (fit-center) and resets any zoom. */
  private void resetToBase() {
    if (zoomAnimator != null) {
      zoomAnimator.cancel();
      zoomAnimator = null;
    }
    Drawable drawable = getDrawable();
    int viewWidth = getWidth();
    int viewHeight = getHeight();
    if (drawable == null || viewWidth == 0 || viewHeight == 0) {
      return;
    }
    int drawableWidth = drawable.getIntrinsicWidth();
    int drawableHeight = drawable.getIntrinsicHeight();
    if (drawableWidth <= 0 || drawableHeight <= 0) {
      return;
    }
    setScaleType(ScaleType.MATRIX);
    baseScale = Math.min((float) viewWidth / drawableWidth, (float) viewHeight / drawableHeight);
    float dx = (viewWidth - drawableWidth * baseScale) / 2f;
    float dy = (viewHeight - drawableHeight * baseScale) / 2f;
    displayMatrix.reset();
    displayMatrix.postScale(baseScale, baseScale);
    displayMatrix.postTranslate(dx, dy);
    setImageMatrix(displayMatrix);
  }

  /** Keeps the image edges glued to the view edges (or centered when smaller than the view). */
  private void clampTranslation() {
    Drawable drawable = getDrawable();
    if (drawable == null) {
      return;
    }
    contentRect.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    displayMatrix.mapRect(contentRect);

    float dx = 0f;
    float dy = 0f;
    int viewWidth = getWidth();
    int viewHeight = getHeight();

    if (contentRect.width() <= viewWidth) {
      dx = (viewWidth - contentRect.width()) / 2f - contentRect.left;
    } else if (contentRect.left > 0) {
      dx = -contentRect.left;
    } else if (contentRect.right < viewWidth) {
      dx = viewWidth - contentRect.right;
    }

    if (contentRect.height() <= viewHeight) {
      dy = (viewHeight - contentRect.height()) / 2f - contentRect.top;
    } else if (contentRect.top > 0) {
      dy = -contentRect.top;
    } else if (contentRect.bottom < viewHeight) {
      dy = viewHeight - contentRect.bottom;
    }

    displayMatrix.postTranslate(dx, dy);
  }

  private void animateScaleTo(float targetScale, float focusX, float focusY) {
    if (zoomAnimator != null) {
      zoomAnimator.cancel();
    }
    float startScale = getCurrentScale();
    zoomAnimator = ValueAnimator.ofFloat(startScale, targetScale);
    zoomAnimator.setDuration(200);
    zoomAnimator.addUpdateListener(
        animation -> {
          float animated = (float) animation.getAnimatedValue();
          float factor = animated / getCurrentScale();
          displayMatrix.postScale(factor, factor, focusX, focusY);
          clampTranslation();
          setImageMatrix(displayMatrix);
        });
    zoomAnimator.start();
  }
}
