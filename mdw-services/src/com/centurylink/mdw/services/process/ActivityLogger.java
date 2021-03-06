package com.centurylink.mdw.services.process;

import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.model.workflow.ActivityRuntimeContext;
import com.centurylink.mdw.service.data.WorkflowDataAccess;
import com.centurylink.mdw.test.MockRuntimeContext;
import com.centurylink.mdw.util.log.AbstractStandardLoggerBase;
import com.centurylink.mdw.util.log.LoggerUtil;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

public class ActivityLogger extends AbstractStandardLoggerBase {

    public static final int MAX_MESSAGE_LENGTH = 3997;
    public static final int MAX_THREAD_LENGTH = 29;

    private ActivityRuntimeContext runtimeContext;

    static {
        if (!PropertyManager.getBooleanProperty(PropertyNames.MDW_LOGGING_ACTIVITY_ENABLED, true))
            LoggerUtil.getStandardLogger().info("Activity logging is disabled");
    }

    public ActivityLogger(ActivityRuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
        if (runtimeContext.getPerformanceLevel() < 9 && !(runtimeContext instanceof MockRuntimeContext))
            this.runtimeContext.setLogPersister(ActivityLogger::persist);
    }

    @Override
    public void info(String msg) {
        runtimeContext.logInfo(msg);
    }

    @Override
    public void warn(String msg) {
        runtimeContext.logWarn(msg);
    }

    @Override
    public void error(String msg) {
        runtimeContext.logError(msg);
    }

    @Override
    public void severe(String msg) {
        runtimeContext.logSevere(msg);
    }

    @Override
    public void debug(String msg) {
        runtimeContext.logDebug(msg);
    }

    /**
     * does not persist
     */
    @Override
    public void trace(String msg) {
        runtimeContext.logTrace(msg);
    }

    /**
     * does not persist
     */
    @Override
    public void mdwDebug(String msg) {
        trace(msg);
    }

    @Override
    public void info(String msg, Throwable t) {
        if (isInfoEnabled()) {
            error(msg, t);
        }
    }

    @Override
    public void infoException(String msg, Throwable t) {
        info(msg, t);
    }

    @Override
    public void warn(String msg, Throwable t) {
        if (isEnabledFor(LogLevel.WARN))
            error(msg, t);
    }

    @Override
    public void warnException(String msg, Throwable t) {
        warn(msg, t);
    }

    @Override
    public void error(String msg, Throwable t) {
        runtimeContext.logError(msg, t);
    }

    @Override
    public void severeException(String msg, Throwable t) {
        runtimeContext.logException(msg, t);
    }

    @Override
    public void debug(String msg, Throwable t) {
        if (isDebugEnabled())
            error(msg, t);
    }

    @Override
    public void debugException(String msg, Throwable t) {
        debug(msg, t);
    }

    @Override
    public void trace(String msg, Throwable t) {
        if (isTraceEnabled())
            error(msg, t);
    }

    @Override
    public void traceException(String msg, Throwable t) {
        trace(msg, t);
    }

    @Override
    public boolean isEnabledFor(LogLevel level) {
        if (level == LogLevel.INFO)
            return runtimeContext.isLogInfoEnabled();
        else if (level == LogLevel.DEBUG)
            return runtimeContext.isLogDebugEnabled();
        else if (level == LogLevel.TRACE)
            return runtimeContext.isLogTraceEnabled();
        return true;
    }

    @Override
    public void log(LogLevel level, String message) {
        if (level == LogLevel.INFO)
            info(message);
        else if (level == LogLevel.DEBUG)
            debug(message);
        else if (level == LogLevel.TRACE)
            trace(message);
        else if (level == LogLevel.WARN)
            warn(message);
        else
            error(message);
    }

    public static void persist(Long processInstanceId, Long activityInstanceId, LogLevel level, String message) {
        persist(processInstanceId, activityInstanceId, level, message, null);
    }

    public static void persist(Long processInstanceId, Long activityInstanceId, LogLevel level, String message, Throwable t) {
        boolean isLogging = PropertyManager.getBooleanProperty(PropertyNames.MDW_LOGGING_ACTIVITY_ENABLED, true);
        if (isLogging && message != null) {
            if (message.length() > MAX_MESSAGE_LENGTH)
                message = message.substring(0, MAX_MESSAGE_LENGTH) + "...";
            String thread = Thread.currentThread().getName();
            if (thread.length() > MAX_THREAD_LENGTH)
                thread = thread.substring(0, MAX_THREAD_LENGTH) + "...";

            try {
                WorkflowDataAccess dataAccess = new WorkflowDataAccess();
                dataAccess.addActivityLog(processInstanceId, activityInstanceId, level.toString(), thread, message);
                if (t != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    t.printStackTrace(new PrintStream(baos));
                    String stackTrace = new String(baos.toByteArray());
                    if (stackTrace.length() > MAX_MESSAGE_LENGTH) {
                        stackTrace = stackTrace.substring(0, MAX_MESSAGE_LENGTH) + "...";
                    }
                    dataAccess.addActivityLog(processInstanceId, activityInstanceId, level.toString(), thread, stackTrace);
                }
            }
            catch (DataAccessException ex) {
                // don't try and use this logger
                LoggerUtil.getStandardLogger().error(ex.getMessage(), ex);
            }
        }
    }
}
