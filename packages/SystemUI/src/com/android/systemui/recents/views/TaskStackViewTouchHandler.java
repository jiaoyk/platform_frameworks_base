/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.MutableBoolean;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewDebug;
import android.view.ViewParent;
import android.view.animation.Animation;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.recents.Constants;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.HideRecentsEvent;
import com.android.systemui.recents.events.ui.StackViewScrolledEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.misc.RectFEvaluator;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles touch events for a TaskStackView.
 */
class TaskStackViewTouchHandler implements SwipeHelper.Callback {

    private static final int INACTIVE_POINTER_ID = -1;
    private static final Interpolator STACK_TRANSFORM_INTERPOLATOR =
            new PathInterpolator(0.73f, 0.33f, 0.42f, 0.85f);

    Context mContext;
    TaskStackView mSv;
    TaskStackViewScroller mScroller;
    VelocityTracker mVelocityTracker;
    FlingAnimationUtils mFlingAnimUtils;
    ValueAnimator mScrollFlingAnimator;

    @ViewDebug.ExportedProperty(category="recents")
    boolean mIsScrolling;
    float mDownScrollP;
    int mDownX, mDownY;
    int mLastY;
    int mActivePointerId = INACTIVE_POINTER_ID;
    int mOverscrollSize;
    TaskView mActiveTaskView = null;

    int mMinimumVelocity;
    int mMaximumVelocity;
    // The scroll touch slop is used to calculate when we start scrolling
    int mScrollTouchSlop;
    // Used to calculate when a tap is outside a task view rectangle.
    final int mWindowTouchSlop;

    private final StackViewScrolledEvent mStackViewScrolledEvent = new StackViewScrolledEvent();

    // The current and final set of task transforms, sized to match the list of tasks in the stack
    private ArrayList<Task> mCurrentTasks = new ArrayList<>();
    private ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<>();
    private ArrayList<TaskViewTransform> mFinalTaskTransforms = new ArrayList<>();
    private ArrayMap<View, Animator> mSwipeHelperAnimations = new ArrayMap<>();
    private TaskViewTransform mTmpTransform = new TaskViewTransform();
    private float mTargetStackScroll;

    SwipeHelper mSwipeHelper;
    boolean mInterceptedBySwipeHelper;

    public TaskStackViewTouchHandler(Context context, TaskStackView sv,
            TaskStackViewScroller scroller) {
        Resources res = context.getResources();
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mContext = context;
        mSv = sv;
        mScroller = scroller;
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mScrollTouchSlop = configuration.getScaledTouchSlop();
        mWindowTouchSlop = configuration.getScaledWindowTouchSlop();
        mFlingAnimUtils = new FlingAnimationUtils(context, 0.2f);
        mOverscrollSize = res.getDimensionPixelSize(R.dimen.recents_stack_overscroll);
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, context) {
            @Override
            protected float getSize(View v) {
                return mSv.getWidth();
            }

            @Override
            protected void prepareDismissAnimation(View v, Animator anim) {
                mSwipeHelperAnimations.put(v, anim);
            }

            @Override
            protected void prepareSnapBackAnimation(View v, Animator anim) {
                anim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
                mSwipeHelperAnimations.put(v, anim);
            }
        };
        mSwipeHelper.setDisableHardwareLayers(true);
    }

    /** Velocity tracker helpers */
    void initOrResetVelocityTracker() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        } else {
            mVelocityTracker.clear();
        }
    }
    void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /** Touch preprocessing for handling below */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Pass through to swipe helper if we are swiping
        mInterceptedBySwipeHelper = mSwipeHelper.onInterceptTouchEvent(ev);
        if (mInterceptedBySwipeHelper) {
            return true;
        }

        return handleTouchEvent(ev);
    }

    /** Handles touch events once we have intercepted them */
    public boolean onTouchEvent(MotionEvent ev) {
        // Pass through to swipe helper if we are swiping
        if (mInterceptedBySwipeHelper && mSwipeHelper.onTouchEvent(ev)) {
            return true;
        }

        handleTouchEvent(ev);
        return true;
    }

    private boolean handleTouchEvent(MotionEvent ev) {
        // Short circuit if we have no children
        if (mSv.getTaskViews().size() == 0) {
            return false;
        }

        final TaskStackLayoutAlgorithm layoutAlgorithm = mSv.mLayoutAlgorithm;
        int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                // Save the touch down info
                mDownX = (int) ev.getX();
                mDownY = (int) ev.getY();
                mLastY = mDownY;
                mDownScrollP = mScroller.getStackScroll();
                mActivePointerId = ev.getPointerId(0);
                mActiveTaskView = findViewAtPoint(mDownX, mDownY);

                // Stop the current scroll if it is still flinging
                mScroller.stopScroller();
                mScroller.stopBoundScrollAnimation();
                Utilities.cancelAnimationWithoutCallbacks(mScrollFlingAnimator);

                // Finish any existing task animations from the delete
                mSv.cancelAllTaskViewAnimations();
                // Finish any of the swipe helper animations
                ArrayMap<View, Animator> existingAnimators = new ArrayMap<>(mSwipeHelperAnimations);
                for (int i = 0; i < existingAnimators.size(); i++) {
                    existingAnimators.get(existingAnimators.keyAt(i)).end();
                }
                mSwipeHelperAnimations.clear();

                // Initialize the velocity tracker
                initOrResetVelocityTracker();
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                mActivePointerId = ev.getPointerId(index);
                mDownX = (int) ev.getX(index);
                mDownY = (int) ev.getY(index);
                mLastY = mDownY;
                mDownScrollP = mScroller.getStackScroll();
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int x = (int) ev.getX(activePointerIndex);
                if (!mIsScrolling) {
                    int yDiff = Math.abs(y - mDownY);
                    int xDiff = Math.abs(x - mDownX);
                    if (Math.abs(y - mDownY) > mScrollTouchSlop && yDiff > xDiff) {
                        mIsScrolling = true;

                        // Disallow parents from intercepting touch events
                        final ViewParent parent = mSv.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }

                        MetricsLogger.action(mSv.getContext(), MetricsEvent.OVERVIEW_SCROLL);
                    }
                }
                if (mIsScrolling) {
                    // If we just move linearly on the screen, then that would map to 1/arclength
                    // of the curve, so just move the scroll proportional to that
                    float deltaP = layoutAlgorithm.getDeltaPForY(mDownY, y);
                    float curScrollP = mDownScrollP + deltaP;
                    mScroller.setStackScroll(curScrollP);
                    mStackViewScrolledEvent.updateY(y - mLastY);
                    EventBus.getDefault().send(mStackViewScrolledEvent);
                }

                mLastY = y;
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = ev.getActionIndex();
                int pointerId = ev.getPointerId(pointerIndex);
                if (pointerId == mActivePointerId) {
                    // Select a new active pointer id and reset the motion state
                    final int newPointerIndex = (pointerIndex == 0) ? 1 : 0;
                    mActivePointerId = ev.getPointerId(newPointerIndex);
                    mDownX = (int) ev.getX(pointerIndex);
                    mDownY = (int) ev.getY(pointerIndex);
                    mLastY = mDownY;
                    mDownScrollP = mScroller.getStackScroll();
                }
                mVelocityTracker.addMovement(ev);
                break;
            }
            case MotionEvent.ACTION_UP: {
                mVelocityTracker.addMovement(ev);
                mVelocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                int y = (int) ev.getY(activePointerIndex);
                int velocity = (int) mVelocityTracker.getYVelocity(mActivePointerId);
                if (mIsScrolling) {
                    if (mScroller.isScrollOutOfBounds()) {
                        mScroller.animateBoundScroll();
                    } else if (Math.abs(velocity) > mMinimumVelocity) {
                        float minY = mDownY + layoutAlgorithm.getYForDeltaP(mDownScrollP,
                                layoutAlgorithm.mMaxScrollP);
                        float maxY = mDownY + layoutAlgorithm.getYForDeltaP(mDownScrollP,
                                layoutAlgorithm.mMinScrollP);
                        mScroller.fling(mDownScrollP, mDownY, y, velocity, (int) minY, (int) maxY,
                                mOverscrollSize);
                        mSv.invalidate();
                    }
                } else if (mActiveTaskView == null) {
                    // This tap didn't start on a task.
                    maybeHideRecentsFromBackgroundTap((int) ev.getX(), (int) ev.getY());
                }

                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                recycleVelocityTracker();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                mActivePointerId = INACTIVE_POINTER_ID;
                mIsScrolling = false;
                recycleVelocityTracker();
                break;
            }
        }
        return mIsScrolling;
    }

    /** Hides recents if the up event at (x, y) is a tap on the background area. */
    void maybeHideRecentsFromBackgroundTap(int x, int y) {
        // Ignore the up event if it's too far from its start position. The user might have been
        // trying to scroll or swipe.
        int dx = Math.abs(mDownX - x);
        int dy = Math.abs(mDownY - y);
        if (dx > mScrollTouchSlop || dy > mScrollTouchSlop) {
            return;
        }

        // Shift the tap position toward the center of the task stack and check to see if it would
        // have hit a view. The user might have tried to tap on a task and missed slightly.
        int shiftedX = x;
        if (x > (mSv.getRight() - mSv.getLeft()) / 2) {
            shiftedX -= mWindowTouchSlop;
        } else {
            shiftedX += mWindowTouchSlop;
        }
        if (findViewAtPoint(shiftedX, y) != null) {
            return;
        }

        // If tapping on the freeform workspace background, just launch the first freeform task
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            Rect freeformRect = mSv.mLayoutAlgorithm.mFreeformRect;
            if (freeformRect.top <= y && y <= freeformRect.bottom) {
                if (mSv.launchFreeformTasks()) {
                    // TODO: Animate Recents away as we launch the freeform tasks
                    return;
                }
            }
        }

        // The user intentionally tapped on the background, which is like a tap on the "desktop".
        // Hide recents and transition to the launcher.
        EventBus.getDefault().send(new HideRecentsEvent(false, true));
    }

    /** Handles generic motion events */
    public boolean onGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) ==
                InputDevice.SOURCE_CLASS_POINTER) {
            int action = ev.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_SCROLL:
                    // Find the front most task and scroll the next task to the front
                    float vScroll = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vScroll > 0) {
                        mSv.setRelativeFocusedTask(true, true /* stackTasksOnly */,
                                false /* animated */);
                    } else {
                        mSv.setRelativeFocusedTask(false, true /* stackTasksOnly */,
                                false /* animated */);
                    }
                    return true;
            }
        }
        return false;
    }

    /**** SwipeHelper Implementation ****/

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        TaskView tv = findViewAtPoint((int) ev.getX(), (int) ev.getY());
        if (tv != null && canChildBeDismissed(tv)) {
            return tv;
        }
        return null;
    }

    @Override
    public boolean canChildBeDismissed(View v) {
        // Disallow dismissing an already dismissed task
        TaskView tv = (TaskView) v;
        return !mSwipeHelperAnimations.containsKey(v) &&
                (mSv.getStack().indexOfStackTask(tv.getTask()) != -1);
    }

    @Override
    public void onBeginDrag(View v) {
        TaskView tv = (TaskView) v;

        // Disable clipping with the stack while we are swiping
        tv.setClipViewInStack(false);
        // Disallow touch events from this task view
        tv.setTouchEnabled(false);
        // Disallow parents from intercepting touch events
        final ViewParent parent = mSv.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        // Add this task to the set of tasks we are deleting
        mSv.addIgnoreTask(tv.getTask());

        // Determine if we are animating the other tasks while dismissing this task
        mCurrentTasks = mSv.getStack().getStackTasks();
        MutableBoolean isFrontMostTask = new MutableBoolean(false);
        Task anchorTask = mSv.findAnchorTask(mCurrentTasks, isFrontMostTask);
        TaskStackViewScroller stackScroller = mSv.getScroller();
        if (anchorTask != null) {
            // Get the current set of task transforms
            mSv.getCurrentTaskTransforms(mCurrentTasks, mCurrentTaskTransforms);

            // Get the stack scroll of the task to anchor to (since we are removing something, the
            // front most task will be our anchor task)
            float prevAnchorTaskScroll = 0;
            boolean pullStackForward = mCurrentTasks.size() > 0;
            if (pullStackForward) {
                prevAnchorTaskScroll = mSv.getStackAlgorithm().getStackScrollForTask(anchorTask);
            }

            // Calculate where the views would be without the deleting tasks
            mSv.updateLayoutAlgorithm(false /* boundScroll */);

            float newStackScroll = stackScroller.getStackScroll();
            if (isFrontMostTask.value) {
                // Bound the stack scroll to pull tasks forward if necessary
                newStackScroll = stackScroller.getBoundedStackScroll(newStackScroll);
            } else if (pullStackForward) {
                // Otherwise, offset the scroll by the movement of the anchor task
                float anchorTaskScroll = mSv.getStackAlgorithm().getStackScrollForTask(anchorTask);
                float stackScrollOffset = (anchorTaskScroll - prevAnchorTaskScroll);
                if (mSv.getStackAlgorithm().getFocusState() !=
                        TaskStackLayoutAlgorithm.STATE_FOCUSED) {
                    // If we are focused, we don't want the front task to move, but otherwise, we
                    // allow the back task to move up, and the front task to move back
                    stackScrollOffset /= 2;
                }
                newStackScroll = stackScroller.getBoundedStackScroll(stackScroller.getStackScroll()
                        + stackScrollOffset);
            }

            // Pick up the newly visible views, not including the deleting tasks
            mSv.bindVisibleTaskViews(newStackScroll);

            // Get the final set of task transforms (with task removed)
            mSv.getLayoutTaskTransforms(newStackScroll, mCurrentTasks, mFinalTaskTransforms);

            // Set the target to scroll towards upon dismissal
            mTargetStackScroll = newStackScroll;

            /*
             * Post condition: All views that will be visible as a part of the gesture are retrieved
             *                 and at their initial positions.  The stack is still at the current
             *                 scroll, but the layout is updated without the task currently being
             *                 dismissed.
             */
        }
    }

    @Override
    public boolean updateSwipeProgress(View v, boolean dismissable, float swipeProgress) {
        updateTaskViewTransforms(getDismissFraction(v));
        return true;
    }

    /**
     * Called after the {@link TaskView} is finished animating away.
     */
    @Override
    public void onChildDismissed(View v) {
        TaskView tv = (TaskView) v;

        // Re-enable clipping with the stack (we will reuse this view)
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        tv.setTouchEnabled(true);
        // Update the scroll to the final scroll position from onBeginDrag()
        mSv.getScroller().setStackScroll(mTargetStackScroll, null);
        // Remove the task view from the stack
        EventBus.getDefault().send(new TaskViewDismissedEvent(tv.getTask(), tv));
        // Stop tracking this deletion animation
        mSwipeHelperAnimations.remove(v);
        // Keep track of deletions by keyboard
        MetricsLogger.histogram(tv.getContext(), "overview_task_dismissed_source",
                Constants.Metrics.DismissSourceSwipeGesture);
    }

    /**
     * Called after the {@link TaskView} is finished animating back into the list.
     * onChildDismissed() calls.
     */
    @Override
    public void onChildSnappedBack(View v) {
        TaskView tv = (TaskView) v;

        // Re-enable clipping with the stack
        tv.setClipViewInStack(true);
        // Re-enable touch events from this task view
        tv.setTouchEnabled(true);

        // Stop tracking this deleting task, and update the layout to include this task again.  The
        // stack scroll does not need to be reset, since the scroll has not actually changed in
        // onBeginDrag().
        mSv.removeIgnoreTask(tv.getTask());
        mSv.updateLayoutAlgorithm(false /* boundScroll */);
        mSv.relayoutTaskViews(AnimationProps.IMMEDIATE);
        mSwipeHelperAnimations.remove(v);
    }

    @Override
    public void onDragCancelled(View v) {
        // Do nothing
    }

    @Override
    public View getChildContentView(View v) {
        return v;
    }

    @Override
    public boolean isAntiFalsingNeeded() {
        return false;
    }

    @Override
    public float getFalsingThresholdFactor() {
        return 0;
    }

    /**
     * Interpolates the non-deleting tasks to their final transforms from their current transforms.
     */
    private void updateTaskViewTransforms(float dismissFraction) {
        List<TaskView> taskViews = mSv.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();

            if (mSv.isIgnoredTask(task)) {
                continue;
            }

            int taskIndex = mCurrentTasks.indexOf(task);
            TaskViewTransform fromTransform = mCurrentTaskTransforms.get(taskIndex);
            TaskViewTransform toTransform = mFinalTaskTransforms.get(taskIndex);

            mTmpTransform.copyFrom(fromTransform);
            // We only really need to interpolate the bounds, progress and translation
            mTmpTransform.rect.set(Utilities.RECTF_EVALUATOR.evaluate(dismissFraction,
                    fromTransform.rect, toTransform.rect));
            mTmpTransform.p = fromTransform.p + (toTransform.p - fromTransform.p) * dismissFraction;
            mTmpTransform.translationZ = fromTransform.translationZ +
                    (toTransform.translationZ - fromTransform.translationZ) * dismissFraction;

            mSv.updateTaskViewToTransform(tv, mTmpTransform, AnimationProps.IMMEDIATE);
        }
    }

    /** Returns the view at the specified coordinates */
    private TaskView findViewAtPoint(int x, int y) {
        List<Task> tasks = mSv.getStack().getStackTasks();
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            TaskView tv = mSv.getChildViewForTask(tasks.get(i));
            if (tv != null && tv.getVisibility() == View.VISIBLE) {
                if (mSv.isTouchPointInView(x, y, tv)) {
                    return tv;
                }
            }
        }
        return null;
    }

    /**
     * Returns the fraction which we should interpolate the other task views based on the dismissal
     * of this given task.
     *
     * TODO: We can interpolate this to adjust when the other tasks should respond to the dismissal
     */
    private float getDismissFraction(View v) {
        float fraction = Math.min(1f, Math.abs(v.getTranslationX() / mSv.getWidth()));
        return STACK_TRANSFORM_INTERPOLATOR.getInterpolation(fraction);
    }
}
