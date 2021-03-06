package com.centurylink.mdw.monitor;

import java.util.Map;

import com.centurylink.mdw.common.service.RegisteredService;
import com.centurylink.mdw.model.task.TaskRuntimeContext;

/**
 * Activity monitors can be registered through @Monitor annotations to get
 * (optionally) called whenever an MDW workflow activity is invoked.
 */
public interface TaskMonitor extends RegisteredService, Monitor {

    /**
     * Called when a task instance is created.
     * @param context the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onCreate(TaskRuntimeContext context) {
        return null;
    }

    /**
     * Called when a task instance is assigned.
     * @param context the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onAssign(TaskRuntimeContext context) {
        return null;
    }

    /**
     * Called when a task instance assumes the optional state of in-progress
     * (meaning the assignee has begun work on the task).
     * @param context the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onInProgress(TaskRuntimeContext context) {
        return null;
    }

    /**
     * Called when a task instance reaches alert status (scheduled
     * completion date is drawing near).
     * @param runtimeContext the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onAlert(TaskRuntimeContext runtimeContext) {
        return null;
    }

    /**
     * Called when a task instance reaches jeopardy status (scheduled
     * completion date has passed).
     * @param context the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onJeopardy(TaskRuntimeContext context) {
        return null;
    }

    /**
     * Called when a task instance is forwarded from one workgroup to another.
     * @param context the task runtime context
     * @return optional map containing new or updated values
     */
    default Map<String,Object> onForward(TaskRuntimeContext context) {
        return null;
    }

    /**
     * Called when a task instance is completed.
     * @param context the task runtime context
     */
    default void onComplete(TaskRuntimeContext context) {
    }

    /**
     * Called when a task instance is cancelled.
     * @param context the task runtime context
     */
    default void onCancel(TaskRuntimeContext context) {
    }
}
