package org.ubonass.media.server.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ubonass.media.server.kurento.core.KurentoCallSession;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    /**
     * 用于管理1V1通信
     *
     * @Key:为客户端的privateId
     * @Value:为KurentoCallSession
     */
    private Map<String, KurentoCallSession> callSessions = new ConcurrentHashMap<>();

    private static SessionManager context;

    private SessionManager() { }

    public static SessionManager getContext() {
        return context;
    }

    @PostConstruct
    public void init() {
        context = this;
    }

    /**
     * @return
     */
    public Map<String, KurentoCallSession> getCallSessions() {
        return callSessions;
    }

    /**
     * @param sessionId
     * @param callSession
     */
    public KurentoCallSession addCallSession(String sessionId, KurentoCallSession callSession) {
        KurentoCallSession oldCallSession =
                callSessions.putIfAbsent(sessionId, callSession);
        if (oldCallSession != null)
            logger.warn("callSession '{}' has just been added by another thread", sessionId);
        return oldCallSession;
    }

    /**
     * @param sessionId
     * @return
     */
    public KurentoCallSession removeCallSession(String sessionId) {
        KurentoCallSession remove = null;
        if (callSessions.containsKey(sessionId))
            remove = callSessions.remove(sessionId);
        return remove;
    }

    /**
     * @param sessionId
     * @return
     */
    public KurentoCallSession getCallSession(String sessionId) {
        if (!callSessions.containsKey(sessionId)) {
            logger.error("callSessions not have {} value", sessionId);
            return null;
        }
        return callSessions.get(sessionId);
    }
}
