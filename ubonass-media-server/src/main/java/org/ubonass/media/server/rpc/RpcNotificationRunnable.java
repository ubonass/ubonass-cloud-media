package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.core.SessionManager;

import java.io.IOException;
import java.io.Serializable;

public class RpcNotificationRunnable implements Runnable, Serializable {
    private static final Logger logger = LoggerFactory.getLogger(RpcNotificationRunnable.class);

    private String clientId;
    private String method;
    private JsonObject object;
    private HazelcastInstance instance;
    private SessionManager sessionManager;

    public RpcNotificationRunnable(
            String clientId,
            String method,
            JsonObject object) {
        this.clientId = clientId;
        this.method = method;
        this.object = object;
        this.instance = ClusterRpcService.getContext().getHazelcastInstance();
        this.sessionManager = SessionManager.getContext();
    }

    @Override
    public void run() {
        if (clientId == null
                || method == null
                || instance == null) return;
        RpcConnection rpcConnection = sessionManager.getOnlineConnection(clientId);
        if (rpcConnection == null) return;
        try {
            if (object != null) {
                rpcConnection.getSession().sendNotification(method, object);
            } else {
                rpcConnection.getSession().sendNotification(method);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
