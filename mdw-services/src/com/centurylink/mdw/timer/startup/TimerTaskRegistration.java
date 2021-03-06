package com.centurylink.mdw.timer.startup;

import com.centurylink.mdw.common.service.MdwServiceRegistry;
import com.centurylink.mdw.config.PropertyException;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.model.event.EventInstance;
import com.centurylink.mdw.model.monitor.ScheduledEvent;
import com.centurylink.mdw.model.monitor.ScheduledJob;
import com.centurylink.mdw.services.EventServices;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.services.event.ScheduledEventQueue;
import com.centurylink.mdw.startup.StartupException;
import com.centurylink.mdw.startup.StartupService;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

import java.util.*;

/**
 * Registers all the application timers.
 */
public class TimerTaskRegistration implements StartupService {

    private static final String PROPERTY_TIMER_CLASS = "TimerClass";
    private static final String PROPERTY_SCHEDULE = "Schedule";  // cron expression, e.g. 0 30 14 * * = 2:30pm

    private static final String SCHEDULED_JOB = "ScheduledJob";
    private static final String UNSCHEDULED_EVENTS = "UnscheduledEventsTimer";

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    private static Collection<Timer> _timers;

    /**
     * Invoked when the server starts up.
     */
    public void onStartup() throws StartupException {
        _timers = new ArrayList<>();
        configureTimers();
    }

    private void configureTimers() throws StartupException {
        try {
            String v = PropertyManager.getProperty(PropertyNames.MDW_CONTAINER_MESSENGER);
            int period_default = (v == null || "jms".equalsIgnoreCase(v)) ? 60 : 5;
            int delay = PropertyManager.getIntegerProperty(PropertyNames.MDW_TIMER_INITIAL_DELAY, 180);
            int period = PropertyManager.getIntegerProperty(PropertyNames.MDW_TIMER_CHECK_INTERVAL, period_default);
            int unschedDelay = PropertyManager.getIntegerProperty(PropertyNames.UNSCHEDULED_EVENTS_CHECK_DELAY, 180);
            int unschedPeriod = PropertyManager.getIntegerProperty(PropertyNames.UNSCHEDULED_EVENTS_CHECK_INTERVAL, 300);
            Map<String,Properties> timers = getAllScheduledEvents();

            ScheduledEventMonitor scheduler = new ScheduledEventMonitor();
            Timer aTimer = new Timer(SCHEDULED_JOB);
            aTimer.schedule(scheduler, delay * 1000L, period * 1000L);
            _timers.add(aTimer);


            UnscheduledEventMonitor unscheduledEventMonitor = new UnscheduledEventMonitor();
            Timer unscheduledTimer = new Timer(UNSCHEDULED_EVENTS);
            unscheduledTimer.schedule(unscheduledEventMonitor, unschedDelay * 1000L, unschedPeriod * 1000L);
            _timers.add(unscheduledTimer);

            for (String name : timers.keySet()) {
                Properties timerProps = timers.get(name);
                String schedule = timerProps.getProperty(PROPERTY_SCHEDULE);
                String clsnameAndArgs = timerProps.getProperty(PROPERTY_TIMER_CLASS);
                if (schedule==null || schedule.trim().length()==0)
                    continue;
                ScheduledEventQueue queue = ScheduledEventQueue.getSingleton();
                queue.scheduleCronJob(ScheduledEvent.SCHEDULED_JOB_PREFIX + clsnameAndArgs, schedule);
            }
        }
        catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
    }

    public void onShutdown() {
        if (_timers == null || _timers.isEmpty()) {
            return;
        }
        Iterator<Timer> it = _timers.iterator();
        while (it.hasNext()) {
            Timer aTimer = it.next();
            aTimer.cancel();
        }
    }

    private Map<String, Properties> getAllScheduledEvents() throws PropertyException {

        Map<String, Properties> timerTasks = new HashMap<>();

        // old-style property driven
        Properties timerTasksProperties = PropertyManager.getInstance().getProperties(PropertyNames.MDW_TIMER_TASK);
        for (String pn : timerTasksProperties.stringPropertyNames()) {
            String[] pnParsed = pn.split("\\.");
            if (pnParsed.length == 5) {
                String name = pnParsed[3];
                String attrname = pnParsed[4];
                Properties spec = timerTasks.get(name);
                if (spec == null) {
                    spec = new Properties();
                    timerTasks.put(name, spec);
                }
                String value = timerTasksProperties.getProperty(pn);
                spec.put(attrname, value);
            }
        }

        // new-style @ScheduledJob annotations -- allows update of existing event_instance in db to modify cron schedule
        for (ScheduledJob scheduledJob : MdwServiceRegistry.getInstance().getDynamicServices(ScheduledJob.class)) {
            com.centurylink.mdw.annotations.ScheduledJob scheduledJobAnnotation =
                    scheduledJob.getClass().getAnnotation(com.centurylink.mdw.annotations.ScheduledJob.class);
            if (scheduledJobAnnotation != null) {
                boolean enabled = scheduledJobAnnotation.defaultEnabled();
                String enabledProp = scheduledJobAnnotation.enabled();
                if (enabledProp.startsWith("${props['") && enabledProp.endsWith("']}")) {
                    enabledProp = enabledProp.substring(9, enabledProp.length() - 3);
                    enabled = PropertyManager.getBooleanProperty(enabledProp, enabled);
                }
                else if (!enabledProp.isEmpty()) {
                    enabled = "true".equalsIgnoreCase(enabledProp);
                }
                String name = scheduledJobAnnotation.value();
                if (!timerTasks.containsKey(name)) {
                    Properties spec = new Properties();
                    spec.put("TimerClass", scheduledJob.getClass().getName());
                    String eventName = SCHEDULED_JOB + "." + scheduledJob.getClass().getName();
                    String schedule = scheduledJobAnnotation.schedule();
                    if (schedule != null && schedule.startsWith("${props['") && schedule.endsWith("']}")) {
                        schedule = PropertyManager.getProperty(schedule.substring(9, schedule.length() - 3));
                    }
                    if (schedule == null)
                        enabled = false;
                    else
                        spec.put("Schedule", schedule);
                    try {
                        EventServices eventServices = ServiceLocator.getEventServices();
                        EventInstance eventInstance = eventServices.getEventInstance(eventName);
                        // update in case schedule has changed, job is disabled, or status shows job running
                        if (eventInstance != null) {
                            logger.info("Updating schedule for " + scheduledJob.getClass() + ": " + schedule);
                            eventServices.updateEventInstance(eventName, eventInstance.getDocumentId(),
                                    EventInstance.STATUS_SCHEDULED_JOB, eventInstance.getConsumeDate(), eventInstance.getAuxdata(),
                                    eventInstance.getReference(), eventInstance.getPreserveSeconds());
                            if (enabled)
                                ScheduledEventQueue.getSingleton().rescheduleCronJob(eventName, schedule);
                            else
                                eventServices.deleteEventInstance(eventInstance.getEventName());
                        }
                    }
                    catch (Exception ex) {
                        logger.error("Unable to retrieve/update scheduled event: " + eventName, ex);
                    }
                    if (enabled)
                        timerTasks.put(name, spec);
                }
            }
        }

        return timerTasks;
    }
}
