package com.centurylink.mdw.common.service;

import com.centurylink.mdw.model.system.Bulletin;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value="/websocket", configurator=WebSocketConfig.class)
public class WebSocketMessenger {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    private static final Map<String,List<Session>> topicSubscribers = new ConcurrentHashMap<>();

    private static WebSocketMessenger instance = null;
    /**
     * @return null until first subscriber
     */
    public synchronized static WebSocketMessenger getInstance() {
        if (instance == null)
            instance = new WebSocketMessenger();
        return instance;
    }

    public WebSocketMessenger() {}

    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
    }

    @OnClose
    public void unsubscribe(Session session, CloseReason reason) {
        synchronized(topicSubscribers) {
            // remove session
            for (List<Session> sessions : topicSubscribers.values()) {
                sessions.remove(session);
            }
            // remove topics without any sessions
            for (String topic : topicSubscribers.keySet()) {
                if (topicSubscribers.get(topic).isEmpty())
                    topicSubscribers.remove(topic);
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable t) {
        if (t instanceof EOFException && t.getMessage() == null) {
            // avoid nuisance logging when browser closes connection
            if (logger.isMdwDebugEnabled())
                logger.warn(t.getMessage());
        }
        else if (t instanceof IOException && t.getMessage() != null && t.getMessage().startsWith(
                "An established connection was aborted")) {
            // avoid nuisance logging when browser closes connection
            if (logger.isMdwDebugEnabled())
                logger.warn(t.getMessage());
        }
        else {
            logger.error(t.getMessage(), t);
        }
    }

    @OnMessage
    public void subscribe(Session session, String topic) {
       unsubscribe(session, null);
       synchronized(topicSubscribers) {
           List<Session> sessions = topicSubscribers.get(topic);
           if (sessions == null) {
               sessions = new ArrayList<>();
               topicSubscribers.put(topic, sessions);
           }
           if (!sessions.contains(session))
               sessions.add(session);
       }
       // catch me up on any active bulletins
       Map<String,Bulletin> bulletins = SystemMessages.getBulletins();
       for (Bulletin bulletin : bulletins.values()) {
           try {
               session.getBasicRemote().sendText(bulletin.getJson().toString());
           }
           catch (IOException ex) {
               logger.error(ex.getMessage(), ex);
           }
       }
    }

    /**
     * Returns true if any subscribers.
     */
    public boolean send(String topic, String message) throws IOException {
        List<Session> sessions = topicSubscribers.get(topic);
        if (sessions != null) {
            for (Session session : sessions) {
                session.getBasicRemote().sendText(message);
            }
        }
        return sessions != null;
    }
}
