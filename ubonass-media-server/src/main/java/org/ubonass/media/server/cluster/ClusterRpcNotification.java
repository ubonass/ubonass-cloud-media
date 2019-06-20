package org.ubonass.media.server.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.server.rpc.RpcConnection;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.io.IOException;
import java.io.Serializable;

@Data
public class ClusterRpcNotification implements Runnable, Serializable {

    private static final long serialVersionUID = -375075629612750150L;
    private static final Logger logger = LoggerFactory.getLogger(ClusterRpcNotification.class);
    private String clientId;
    private String method;
    private String object;

    public ClusterRpcNotification(
            String clientId,
            String method,
            String object) {
        this.clientId = clientId;
        this.method = method;
        this.object = object;
    }

    @Override
    public void run() {
        if (clientId == null || method == null) return;
        ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        RpcNotificationService notificationService =
                clusterRpcService.getRpcNotificationService();
        ClusterConnection connection = clusterRpcService.getConnection(clientId);
        logger.info("exist connection {} in current host", connection.toString());
        JsonParser parser = new JsonParser();
        JsonObject jsonObject = parser.parse(object).getAsJsonObject();
        notificationService.sendNotification(connection.getParticipantPrivateId(), method, jsonObject);
    }
}
