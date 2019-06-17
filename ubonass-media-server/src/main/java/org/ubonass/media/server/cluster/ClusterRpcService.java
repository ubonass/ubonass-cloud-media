package org.ubonass.media.server.cluster;

import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.core.MediaSessionManager;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.util.Iterator;
import java.util.concurrent.Callable;
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

    /*@PostConstruct
    public void init() {
        context = this;
    }*/

    public IMap<String, ClusterConnection> getClusterConnections() {
        return clusterConnections;
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
