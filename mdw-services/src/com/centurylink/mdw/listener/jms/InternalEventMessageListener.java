package com.centurylink.mdw.listener.jms;

import javax.jms.Message;
import javax.jms.TextMessage;

import com.centurylink.mdw.services.process.ProcessEngineDriver;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

/**
 * Spring Replacement for InternalEventListener
 */
public class InternalEventMessageListener implements javax.jms.MessageListener{

    public InternalEventMessageListener() {
        super();
    }


    /* (non-Javadoc)
     * @see javax.jms.MessageListener#onMessage(javax.jms.Message)
     */
    @Override
    public void onMessage(Message message) {
        StandardLogger logger = LoggerUtil.getStandardLogger();
        String logtag = "EngineDriver.T" + Thread.currentThread().getId() + " - ";

        try {
            String txt = ((TextMessage) message).getText();
            if (logger.isDebugEnabled()) {
                logger.debug("JMS Spring InternalEvent Listener receives request: " + txt);
            }
            logger.info(logtag + "starts processing");
            String messageId = message.getJMSCorrelationID();
            ProcessEngineDriver driver = new ProcessEngineDriver();
            driver.processEvents(messageId, txt);
        } catch (Throwable e) { // only possible when failed to get ProcessManager ejb
            logger.error(logtag + "process exception " + e.getMessage(), e);
        }
    }
}