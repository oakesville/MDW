package com.centurylink.mdw.service.data.activity;

import com.centurylink.mdw.annotations.RegisteredService;
import com.centurylink.mdw.cache.CacheService;
import com.centurylink.mdw.model.workflow.Activity;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.service.data.process.ProcessCache;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Caches activities according to their implementor.
 */
@RegisteredService(value=CacheService.class)
public class ActivityCache implements CacheService {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();
    private static Map<String,List<Activity>> activities = new HashMap<>();

    public static List<Activity> getActivities(String implementor) throws IOException {
        return getActivities(implementor, false);
    }

    /**
     * Find activities in process defs with the specified implementor class.
     */
    public static List<Activity> getActivities(String implementor, boolean withArchived) throws IOException {
        synchronized (ActivityCache.class) {
            List<Activity> matchingActivities = activities.get(implementor);
            if (matchingActivities == null) {
                matchingActivities = loadActivities(implementor, withArchived);
                activities.put(implementor, matchingActivities);
            }
            return matchingActivities;
        }
    }

    private static List<Activity> loadActivities(String implementor, boolean withArchived) throws IOException {
        List<Activity> loadedActivities = new ArrayList<>();
        long before = System.currentTimeMillis();
        List<Process> processes = ProcessCache.getProcesses(withArchived);
        for (Process process : processes) {
            process = ProcessCache.getProcess(process.getId());
            for (Activity activity : process.getActivities()) {
                if (implementor.equals(activity.getImplementor())) {
                    setProcessInfo(activity, process);
                    loadedActivities.add(activity);
                }
            }
            for (Process embedded : process.getSubprocesses()) {
                for (Activity activity : embedded.getActivities()) {
                    if (implementor.equals(activity.getImplementor())) {
                        setProcessInfo(activity, process);
                        loadedActivities.add(activity);
                    }
                }
            }
        }
        long elapsed = System.currentTimeMillis() - before;
        logger.debug(implementor + " activities loaded in " + elapsed + " ms");
        return loadedActivities;
    }

    private static void setProcessInfo(Activity activity, Process process) {
        activity.setPackageName(process.getPackageName());
        activity.setProcessName(process.getName());
        activity.setProcessVersion(process.getVersionString());
        activity.setProcessId(process.getId());
    }

    @Override
    public void refreshCache() {
        // cache is lazily loaded
        clearCache();
    }

    @Override
    public void clearCache() {
        synchronized (ActivityCache.class) {
            activities.clear();
        }
    }
}
