package org.ubonass.media.server.core;


import com.hazelcast.core.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.kurento.core.KurentoCallSession;
import org.ubonass.media.server.rpc.ClusterConnection;
import org.ubonass.media.server.rpc.RpcConnection;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    /**
     * key为用户远程连的客户唯一标识,Value为RpcConnection
     * 只针对本机的连接
     */
    private Map<String, RpcConnection> rpcConnections = new ConcurrentHashMap<>();
    /**
     * key为用户远程连的客户唯一标识,Value为RpcConnection,针对所有集群
     */
    private IMap<String, ClusterConnection> clusterConnections;/* = new ConcurrentHashMap<>();*/
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

    private SessionManager() {
    }

    public static SessionManager getContext() {
        return context;
    }

    @PostConstruct
    public void init() {
        this.clusterConnections =
                clusterRpcService.getHazelcastInstance().getMap("clusterConnections");
        context = this;
    }

    /**
     * @return
     */
    public IMap<String, ClusterConnection> getClusterConnections() {
        return clusterConnections;
    }

    /**
     * @param clientId
     * @param onlineConnection 返回null表示插入成功,否则表示集合中已经有了
     */
    public ClusterConnection addClusterConnection(
            String clientId, ClusterConnection onlineConnection) {
        ClusterConnection oldSession =
                clusterConnections.putIfAbsent(clientId, onlineConnection);
        if (oldSession != null)
            logger.warn("Session '{}' has just been created by another thread", clientId);
        return oldSession;
    }

    /**
     * @param clientId
     * @return
     */
    public ClusterConnection getCluserConnection(String clientId) {
        if (!clusterConnections.containsKey(clientId)) {
            logger.error("clusterConnections not have {} value", clientId);
            return null;
        }
        return clusterConnections.get(clientId);
    }

    /**
     * @param clientId
     * @return
     */
    public ClusterConnection removeClusterConnection(String clientId) {
        ClusterConnection remove = null;
        if (clusterConnections.containsKey(clientId))
            remove = clusterConnections.remove(clientId);
        return remove;
    }

    ////////////////////////////////////////////////////////////////////////

    /**
     * @return
     */
    public Map<String, RpcConnection> getRpcConnections() {
        return rpcConnections;
    }

    /**
     * @param clientId
     * @param rpcConnection 返回null表示插入成功,否则表示集合中已经有了
     */
    public RpcConnection addRpcConnection(
            String clientId, RpcConnection rpcConnection) {
        RpcConnection oldSession =
                rpcConnections.putIfAbsent(clientId, rpcConnection);
        if (oldSession != null)
            logger.warn("Session '{}' has just been created by another thread", clientId);
        return oldSession;
    }

    /**
     * @param clientId
     * @return
     */
    public RpcConnection getRpcConnection(String clientId) {
        if (!rpcConnections.containsKey(clientId)) {
            logger.error("rpcConnections not have {} value", clientId);
            return null;
        }
        return rpcConnections.get(clientId);
    }

    /**
     * 根据集群ClusterConnection获取本地rpcConnections中所管理的RpcConnection
     *
     * @param clusterConnection
     * @return
     */
    public RpcConnection getRpcConnection(ClusterConnection clusterConnection) {
        if (clusterConnection == null
                || clusterConnection.getClientId() == null
                || clusterConnection.getMemberId() == null) return null;
        if (!rpcConnections.containsKey(clusterConnection.getClientId())) {
            logger.error("rpcConnections not have {} value",
                    clusterConnection.getClientId());
            return null;
        }
        RpcConnection rpcConnection =
                rpcConnections.get(clusterConnection.getClientId());
        if (rpcConnection == null) return null;
        if (rpcConnection.getMemberId().equals(clusterConnection.getMemberId())) {
            return rpcConnection;
        } else {
            return null;
        }
    }

    /**
     * @param clientId
     * @return
     */
    public RpcConnection removeRpcConnection(String clientId) {
        RpcConnection remove = null;
        if (rpcConnections.containsKey(clientId))
            remove = rpcConnections.remove(clientId);
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
