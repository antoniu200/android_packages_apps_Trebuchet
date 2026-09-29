/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.quickstep.util.ActiveGestureLog.INTENT_EXTRA_LOG_TRACE_ID;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.PointF;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;

import androidx.annotation.BinderThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.util.RunnableList;
import com.android.quickstep.RecentsAnimationCallbacks.RecentsAnimationListener;
import com.android.quickstep.util.LauncherSplitScreenListener;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Helper class to handle various atomic commands for switching between Overview.
 */
@TargetApi(Build.VERSION_CODES.P)
public class OverviewCommandHelper {

    public static final int TYPE_SHOW = 1;
    public static final int TYPE_SHOW_NEXT_FOCUS = 2;
    public static final int TYPE_HIDE = 3;
    public static final int TYPE_TOGGLE = 4;
    public static final int TYPE_HOME = 5;

    private static final String TRANSITION_NAME = "Transition:toOverview";

    private final TouchInteractionService mService;
    private final OverviewComponentObserver mOverviewComponentObserver;
    private final TaskAnimationManager mTaskAnimationManager;
    private final ArrayList<CommandInfo> mPendingCommands = new ArrayList<>();

    public OverviewCommandHelper(TouchInteractionService service,
            OverviewComponentObserver observer,
            TaskAnimationManager taskAnimationManager) {
        mService = service;
        mOverviewComponentObserver = observer;
        mTaskAnimationManager = taskAnimationManager;
    }

    /**
     * Called when the command finishes execution.
     */
    private void scheduleNextTask(CommandInfo command) {
        if (!mPendingCommands.isEmpty() && mPendingCommands.get(0) == command) {
            mPendingCommands.remove(0);
            executeNext();
        }
    }

    /**
     * Executes the next command from the queue. If the command finishes immediately (returns true),
     * it continues to execute the next command, until the queue is empty of a command defer's its
     * completion (returns false).
     */
    @UiThread
    private void executeNext() {
        if (mPendingCommands.isEmpty()) {
            return;
        }
        CommandInfo cmd = mPendingCommands.get(0);
        if (executeCommand(cmd)) {
            scheduleNextTask(cmd);
        }
    }

    @UiThread
    private void addCommand(CommandInfo cmd) {
        if (tryPreemptOverviewTransition(cmd)) {
            return;
        }

        boolean wasEmpty = mPendingCommands.isEmpty();
        mPendingCommands.add(cmd);
        if (wasEmpty) {
            executeNext();
        }
    }

    /**
     * Replaces an in-flight Recents-button toggle with a second toggle, without waiting for the
     * first app-to-Overview transition to settle. The original swipe handler keeps ownership of
     * the running app animation, while Launcher/Overview is frozen at its current visual progress.
     *
     * The replacement command carries the already-created RecentsView because StateManager still
     * considers Launcher to be in its background-app state at this point. executeCommand() can then
     * follow its normal TYPE_TOGGLE path without pretending that Overview has fully settled.
     */
    @UiThread
    private boolean tryPreemptOverviewTransition(CommandInfo cmd) {
        if (cmd.type != TYPE_TOGGLE || mPendingCommands.size() != 1) {
            return false;
        }

        CommandInfo activeCommand = mPendingCommands.get(0);
        if (activeCommand.type != TYPE_TOGGLE) {
            return false;
        }

        BaseActivityInterface<?, ?> activityInterface =
                mOverviewComponentObserver.getActivityInterface();
        StatefulActivity<?> activity = activityInterface.getCreatedActivity();
        if (activeCommand.mActiveHandler == null) {
            if (activityInterface instanceof LauncherActivityInterface) {
                LauncherActivityInterface launcherActivityInterface =
                        (LauncherActivityInterface) activityInterface;
                if (launcherActivityInterface.isHomeToOverviewTransitionActive()) {
                    RecentsView recents = activity == null ? null : activity.getOverviewPanel();
                    if (recents == null) {
                        return false;
                    }

                    // Replace the queue head before ending the first command's state animation.
                    // Its existing completion callback can then run normally, but its
                    // scheduleNextTask() call sees that it no longer owns the queue head.
                    mPendingCommands.set(0, cmd);
                    if (!launcherActivityInterface.interruptHomeToOverviewTransition()) {
                        mPendingCommands.set(0, activeCommand);
                        return false;
                    }

                    executeNext();
                    return true;
                }
            }
            return false;
        }

        if (!mTaskAnimationManager.isRecentsAnimationRunning()) {
            return false;
        }

        RecentsView recents = activity == null ? null : activity.getOverviewPanel();
        if (recents == null || !activeCommand.mActiveHandler.interruptCurrentAnimation(
                AbsSwipeUpHandler.AnimationInterruptionMode.RECENTS_BUTTON_QUICK_SWITCH)) {
            return false;
        }

        // Replace the queue head rather than enqueueing behind it. The old handler intentionally
        // remains alive to finish the running-app animation. Its eventual scheduleNextTask() call
        // is harmless because the old command is no longer the queue head.
        cmd.mPreemptedRecentsView = recents;
        mPendingCommands.set(0, cmd);
        executeNext();
        return true;
    }

    /**
     * Adds a command to be executed next, after all pending tasks are completed
     */
    @BinderThread
    public void addCommand(int type) {
        CommandInfo cmd = new CommandInfo(type);
        MAIN_EXECUTOR.execute(() -> addCommand(cmd));
    }

    @UiThread
    public void clearPendingCommands() {
        mPendingCommands.clear();
    }

    @Nullable
    private TaskView getNextTask(RecentsView view) {
        final TaskView runningTaskView = view.getRunningTaskView();

        if (runningTaskView == null) {
            return view.getTaskViewAt(0);
        } else {
            final TaskView nextTask = view.getNextTaskView();
            return nextTask != null ? nextTask : runningTaskView;
        }
    }

    private boolean launchTask(RecentsView recents, @Nullable TaskView taskView, CommandInfo cmd) {
        RunnableList callbackList = null;
        if (taskView != null) {
            taskView.setEndQuickswitchCuj(true);
            callbackList = taskView.launchTaskAnimated();
        }

        if (callbackList != null) {
            callbackList.add(() -> scheduleNextTask(cmd));
            return false;
        } else {
            recents.startHome();
            return true;
        }
    }

    /**
     * Executes the task and returns true if next task can be executed. If false, then the next
     * task is deferred until {@link #scheduleNextTask} is called
     */
    private <T extends StatefulActivity<?>> boolean executeCommand(CommandInfo cmd) {
        BaseActivityInterface<?, T> activityInterface =
                mOverviewComponentObserver.getActivityInterface();
        boolean usingPreemptedRecents = cmd.mPreemptedRecentsView != null;
        RecentsView recents = cmd.mPreemptedRecentsView;
        cmd.mPreemptedRecentsView = null;
        if (recents == null) {
            recents = activityInterface.getVisibleRecentsView();
        }
        if (recents == null) {
            if (cmd.type == TYPE_HIDE) {
                // already hidden
                return true;
            }
            if (cmd.type == TYPE_HOME) {
                mService.startActivity(mOverviewComponentObserver.getHomeIntent());
                LauncherSplitScreenListener.INSTANCE.getNoCreate().notifySwipingToHome();
                return true;
            }
        } else {
            switch (cmd.type) {
                case TYPE_SHOW:
                    // already visible
                    return true;
                case TYPE_HIDE: {
                    int currentPage = recents.getNextPage();
                    TaskView tv = (currentPage >= 0 && currentPage < recents.getTaskViewCount())
                            ? (TaskView) recents.getPageAt(currentPage)
                            : null;
                    return launchTask(recents, tv, cmd);
                }
                case TYPE_TOGGLE: {
                    TaskView nextTask = getNextTask(recents);
                    int quickSwitchTaskId = -1;
                    if (usingPreemptedRecents && nextTask != null && nextTask.getTask() != null) {
                        quickSwitchTaskId = nextTask.getTask().key.id;
                        mTaskAnimationManager.setPendingQuickSwitchTaskId(quickSwitchTaskId);
                    }
                    boolean canExecuteNext = launchTask(recents, nextTask, cmd);
                    if (canExecuteNext && quickSwitchTaskId != -1) {
                        mTaskAnimationManager.clearPendingQuickSwitchTaskId(quickSwitchTaskId);
                    }
                    return canExecuteNext;
                }
                case TYPE_HOME:
                    recents.startHome();
                    LauncherSplitScreenListener.INSTANCE.getNoCreate().notifySwipingToHome();
                    return true;
            }
        }

        if (activityInterface.switchToRecentsIfVisible(() -> scheduleNextTask(cmd))) {
            // If successfully switched, wait until animation finishes
            return false;
        }

        final T activity = activityInterface.getCreatedActivity();
        if (activity != null) {
            InteractionJankMonitorWrapper.begin(
                    activity.getRootView(),
                    InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH);
        }

        GestureState gestureState = mService.createGestureState(GestureState.DEFAULT_STATE);
        gestureState.setHandlingAtomicEvent(true);
        AbsSwipeUpHandler interactionHandler = mService.getSwipeUpHandlerFactory()
                .newHandler(gestureState, cmd.createTime);
        cmd.mActiveHandler = interactionHandler;
        interactionHandler.setGestureEndCallback(
                () -> onTransitionComplete(cmd, interactionHandler));
        interactionHandler.initWhenReady();

        RecentsAnimationListener recentAnimListener = new RecentsAnimationListener() {
            @Override
            public void onRecentsAnimationStart(RecentsAnimationController controller,
                    RecentsAnimationTargets targets) {
                interactionHandler.onGestureEnded(0, new PointF(), new PointF());
                cmd.removeListener(this);
            }

            @Override
            public void onRecentsAnimationCanceled(HashMap<Integer, ThumbnailData> thumbnailDatas) {
                interactionHandler.onGestureCancelled();
                cmd.removeListener(this);

                RecentsView createdRecents =
                        activityInterface.getCreatedActivity().getOverviewPanel();
                if (createdRecents != null) {
                    createdRecents.onRecentsAnimationComplete();
                }
            }
        };

        if (mTaskAnimationManager.isRecentsAnimationRunning()) {
            cmd.mActiveCallbacks = mTaskAnimationManager.continueRecentsAnimation(gestureState);
            cmd.mActiveCallbacks.addListener(interactionHandler);
            mTaskAnimationManager.notifyRecentsAnimationState(interactionHandler);
            interactionHandler.onGestureStarted(true /*isLikelyToStartNewTask*/);

            cmd.mActiveCallbacks.addListener(recentAnimListener);
            mTaskAnimationManager.notifyRecentsAnimationState(recentAnimListener);
        } else {
            Intent intent = new Intent(interactionHandler.getLaunchIntent());
            intent.putExtra(INTENT_EXTRA_LOG_TRACE_ID, gestureState.getGestureId());
            cmd.mActiveCallbacks = mTaskAnimationManager.startRecentsAnimation(
                    gestureState, intent, interactionHandler);
            interactionHandler.onGestureStarted(false /*isLikelyToStartNewTask*/);
            cmd.mActiveCallbacks.addListener(recentAnimListener);
        }

        Trace.beginAsyncSection(TRANSITION_NAME, 0);
        return false;
    }

    private void onTransitionComplete(CommandInfo cmd, AbsSwipeUpHandler handler) {
        cmd.removeListener(handler);
        cmd.mActiveHandler = null;
        Trace.endAsyncSection(TRANSITION_NAME, 0);

        if (cmd.type == TYPE_SHOW_NEXT_FOCUS) {
            RecentsView rv =
                    mOverviewComponentObserver.getActivityInterface().getVisibleRecentsView();
            if (rv != null) {
                // Ensure that recents view has focus so that it receives the followup key inputs
                TaskView taskView = rv.getNextTaskView();
                if (taskView == null) {
                    taskView = rv.getTaskViewAt(0);
                    if (taskView != null) {
                        taskView.requestFocus();
                    } else {
                        rv.requestFocus();
                    }
                } else {
                    taskView.requestFocus();
                }
            }
        }
        scheduleNextTask(cmd);
    }

    public void dump(PrintWriter pw) {
        pw.println("OverviewCommandHelper:");
        pw.println("  mPendingCommands=" + mPendingCommands.size());
        if (!mPendingCommands.isEmpty()) {
            pw.println("    pendingCommandType=" + mPendingCommands.get(0).type);
        }
    }

    private static class CommandInfo {
        public final long createTime = SystemClock.elapsedRealtime();
        public final int type;
        RecentsAnimationCallbacks mActiveCallbacks;
        AbsSwipeUpHandler<?, ?, ?> mActiveHandler;
        RecentsView mPreemptedRecentsView;

        CommandInfo(int type) {
            this.type = type;
        }

        void removeListener(RecentsAnimationListener listener) {
            if (mActiveCallbacks != null) {
                mActiveCallbacks.removeListener(listener);
            }
        }
    }
}
