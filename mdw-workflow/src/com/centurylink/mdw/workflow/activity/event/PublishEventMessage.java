package com.centurylink.mdw.workflow.activity.event;

import com.centurylink.mdw.activity.ActivityException;
import com.centurylink.mdw.annotations.Activity;
import com.centurylink.mdw.constant.OwnerType;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.model.variable.DocumentReference;
import com.centurylink.mdw.util.log.StandardLogger.LogLevel;
import com.centurylink.mdw.util.timer.Tracked;
import com.centurylink.mdw.workflow.activity.DefaultActivityImpl;

@Tracked(LogLevel.TRACE)
@Activity(value="Event Publish", icon="com.centurylink.mdw.base/send.gif",
        pagelet="com.centurylink.mdw.base/publishEvent.pagelet")
public class PublishEventMessage extends DefaultActivityImpl {

    public static final String ATTRIBUTE_EVENT_NAME = "Event Name";
    public static final String ATTRIBUTE_MESSAGE = "Message";

    @Override
    public void execute() throws ActivityException {
        try {
            signal(getEventName(), getEventMessage(), getEventDelay());
        }
        catch (Exception ex) {
            getLogger().error(ex.getMessage(), ex);
            throw new ActivityException(-1, "Failed to publish event message", ex);
        }
    }

    protected String getEventName() {
        String eventName = getAttributeValue(ATTRIBUTE_EVENT_NAME);
        return translatePlaceHolder(eventName);
    }

    protected String getEventMessage() {
        String message = this.getAttributeValue(ATTRIBUTE_MESSAGE);
        if (message == null)
              message = "Empty";
        return translatePlaceHolder(message);
    }

    protected int getEventDelay() {
        int delay = 2;
        String av = getProperty(PropertyNames.ACTIVITY_RESUME_DELAY);
        if (av!=null) {
            try {
                delay = Integer.parseInt(av);
                if (delay<0) delay = 0;
                else if (delay>300) delay = 300;
            } catch (Exception e) {
                logWarn("activity resume delay spec is not an integer");
            }
        }
        return delay;
    }

    protected final void signal(String eventName, String eventMessage, int delay) throws Exception {
        DocumentReference docref = this.createDocument(String.class.getName(),
                eventMessage, OwnerType.INTERNAL_EVENT, this.getActivityInstanceId());
        logInfo("Publish message, event=" + eventName +
                ", id=" + docref.getDocumentId() + ", message=" + eventMessage);
        getEngine().notifyProcess(eventName, docref.getDocumentId(), eventMessage, delay);
    }

}
