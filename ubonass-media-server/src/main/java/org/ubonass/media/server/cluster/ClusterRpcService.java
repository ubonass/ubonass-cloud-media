package org.ubonass.media.server.cluster;

import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.server.core.MediaSessionManager;
import org.ubonass.media.server.rpc.RpcConnection;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

public class ClusterRpcService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRpcService.class);

    private HazelcastInstance hazelcastInstance;

    private String memberId;

    private IExecutorService executorService;

    private Config config;

    private static ClusterRpcService context;

    private MediaSessionManager sessionManager;

    private RpcNotificationService notificationService;

    /**
     * key为用户远程连的客户唯一标识,Value为ClusterConnection,针对所有集群
     */
    private IMap<String, ClusterConnection> clusterConnections;
    /**
     * 用于管理有mediaSession的用户
     *
     * @Key:mediaSessionId
     * @Value: @Key:ClientId,@Value:ClusterConnection
     * 当离开session的时候需要移除
     */
    private IMap<String, IMap<String, ClusterConnection>> sessionsMap;

    public ClusterRpcService(Config config,
                             RpcNotificationService notificationService,
                             MediaSessionManager sessionManager) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.notificationService = notificationService;
        this.config.setInstanceName("hazelcast-instance");
        hazelcastInstance = Hazelcast.newHazelcastInstance(this.config);
        memberId = hazelcastInstance.getCluster().getLocalMember().getUuid();
        logger.info("this uuid is {}", memberId);
        executorService =
                hazelcastInstance.getExecutorService("streamsConnector");

        clusterConnections =
                hazelcastInstance.getMap("clusterConnections");
        sessionsMap =
                hazelcastInstance.getMap("sessionidPublicidClusterConnections");
        context = this;
    }

    public static ClusterRpcService getContext() {
        return context;
    }

    public MediaSessionManager getSessionManager() {
        return sessionManager;
    }

    public RpcNotificationService getRpcNotificationService() {
        return notificationService;
    }

    /**
     * 根据clientId获取在线用户
     *
     * @return
     */
    public IMap<String, ClusterConnection> getClusterConnections() {
        return clusterConnections;
    }

    /**
     * 根据房间号获取当前房间中的ClusterConnection
     *
     * @return
     */
    public IMap<String, IMap<String, ClusterConnection>> getSessionsMap() {
        return sessionsMap;
    }

    /**
     * 判断远程连接是否存在
     *
     * @param participantPublicId
     * @return
     */
    public boolean connectionExist(String participantPublicId) {
        return clusterConnections.containsKey(participantPublicId);
    }

    public IMap<String, ClusterConnection>
    getSessionConnections(String sessionId) {
        if (sessionsMap.containsKey(sessionId)) {
            return sessionsMap.get(sessionId);
        } else {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_NOT_FOUND_ERROR_CODE,
                    "sessionId : {" + sessionId + "}  not Exist in local and remote member");
        }
    }

    /**
     * 返回null表示成功
     *
     * @param rpcConnection
     * @return
     */
    public ClusterConnection addClusterConnection(RpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        ClusterConnection connection = new ClusterConnection(
                rpcConnection.getParticipantPublicId(),
                rpcConnection.getParticipantPrivateId(),
                rpcConnection.getMemberId());
        ClusterConnection oldConnection =
                clusterConnections.putIfAbsent(connection.getParticipantPublicId(), connection);
        return oldConnection;
    }

    /**
     * 从clusterConnections集合中根据participantPublicId获取ClusterConnection
     *
     * @param participantPublicId
     * @return
     */
    public ClusterConnection getConnection(String participantPublicId) {
        if (clusterConnections.containsKey(participantPublicId)) {
            return clusterConnections.get(participantPublicId);
        } else {
            throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                    "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
        }
    }

    /**
     * 如果已经有会话
     * @param sessionId
     * @param participantPublicId
     * @return
     */
    public ClusterConnection getConnection(String sessionId, String participantPublicId) {
        if (sessionsMap.containsKey(sessionId)) {
            IMap<String, ClusterConnection> childMap =
                    sessionsMap.get(sessionId);
            if (childMap != null
                    && childMap.containsKey(participantPublicId))
                return childMap.get(participantPublicId);
            else {
                throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                        "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
            }
        } else {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_NOT_FOUND_ERROR_CODE,
                    "sessionId : {" + sessionId + "}  not Exist in local and remote member");
        }
    }

    /**
     * 将session进行集群管理
     */
    public void addClusterSession(String sessionId, String participantPublicId) {
        if (!sessionsMap.containsKey(sessionId))
            sessionsMap.putIfAbsent(sessionId, hazelcastInstance.getMap(sessionId));
        if (sessionsMap.get(sessionId) != null) {
            ClusterConnection connection =
                    clusterConnections.get(participantPublicId);
            if (connection != null) {
                connection.setSessionId(sessionId);
                /**
                 * 这里引用ClusterConnection不是再创建一个
                 */
                sessionsMap.get(sessionId).putIfAbsent(participantPublicId, connection);
            }
        }
    }

    public void removeClusterSession(String sessionId) {
        if (sessionsMap.containsKey(sessionId)) {
            sessionsMap.remove(sessionId);
        }
    }

    public boolean isLocalHostMember(String memberId) {
        if (memberId == null) return false;
        return memberId.equals(this.memberId);
    }

    public HazelcastInstance getHazelcastInstance() {
        return hazelcastInstance;
    }

    public String getMemberId() {
        return memberId;
    }

    public Future<?> submitTask(Callable<?> callable) {
        return executorService.submit(callable);
    }

    public Future<?> submitTaskToMembers(Callable<?> callable, MemberSelector selector) {
        return executorService.submit(callable, selector);
    }

    public Future<?> submitTaskToMembers(Callable<?> callable, String memberId) {
        MemberSelector selector = new MemberSelector() {
            @Override
            public boolean select(Member member) {
                return member.getUuid().equals(memberId);
            }
        };
        return submitTaskToMembers(callable, selector);
    }

    public void ececuteTask(Runnable runnable) {
        executorService.submit(runnable);
    }


    public void executeToMembers(Runnable runnable, MemberSelector selector) {
        executorService.executeOnMembers(runnable, selector);
    }

    public void executeToMember(Runnable runnable, String memberId) {
        Iterator<Member> iter =
                hazelcastInstance.getCluster().getMembers().iterator();
        while (iter.hasNext()) {
            Member member = iter.next();
            if (member.getUuid().equals(memberId)) {
                executorService.executeOnMember(runnable, member);
            }
        }
    }
}
