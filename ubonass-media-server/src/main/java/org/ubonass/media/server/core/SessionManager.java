package org.ubonass.media.server.core;


import com.hazelcast.core.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.kurento.core.KurentoCallSession;
import org.ubonass.media.server.rpc.RpcConnection;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    /**
     * key为用户远程连的客户唯一标识,Value为RpcConnection
     */
    private IMap<String, RpcConnection> onlineConnections /*= new ConcurrentHashMap<>()*/;
    /**
     * 用于管理1V1通信
     *
     * @Key:为客户端的privateId
     * @Value:为KurentoCallSession
     */
    private Map<String, KurentoCallSession> callSessions = new ConcurrentHashMap<>();

    private static SessionManager context;

    @Autowired
    private ClusterRpcService clusterRpcService;

    private SessionManager() { }

    public static SessionManager getContext() {
        return context;
    }

    @PostConstruct
    public void init() {
        this.onlineConnections =
                clusterRpcService.getHazelcastInstance().getMap("onlineConnections");
        context = this;
    }

    /**
     * @return
     */
    public IMap<String, RpcConnection> getOnlineConnections() {
        return onlineConnections;
    }

    /**
     * @param clientId
     * @param rpcConnection
     * 返回null表示插入成功,否则表示集合中已经有了
     */
    public RpcConnection addOnlineConnection(String clientId, RpcConnection rpcConnection) {
        RpcConnection oldSession =
                onlineConnections.putIfAbsent(clientId, rpcConnection);
        if (oldSession != null)
            logger.warn("Session '{}' has just been created by another thread", clientId);
        return oldSession;
    }

    /**
     * @param clientId
     * @return
     */
    public RpcConnection getOnlineConnection(String clientId) {
        if (!onlineConnections.containsKey(clientId)) {
            logger.error("onlineConnections not have {} value", clientId);
            return null;
        }
        return onlineConnections.get(clientId);
    }

    /**
     * @param clientId
     * @return
     */
    public RpcConnection removeOnlineConnection(String clientId) {
        RpcConnection remove = null;
        if (onlineConnections.containsKey(clientId))
            remove = onlineConnections.remove(clientId);
        return remove;
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
