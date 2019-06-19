package org.ubonass.media.server.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Data;
import org.ubonass.media.server.rpc.RpcConnection;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.io.IOException;
import java.io.Serializable;

@Data
public class ClusterRpcNotification implements Runnable, Serializable {

    private static final long serialVersionUID = -375075629612750150L;

    private String clientId;
    private String method;
    private String object;

    private transient ClusterRpcService clusterRpcService = ClusterRpcService.getContext();

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
        //ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        RpcNotificationService notificationService =
                clusterRpcService.getRpcNotificationService();
        RpcConnection rpcConnection =
                notificationService.getRpcConnection(
                        clusterRpcService.getConnection(clientId).getParticipantPrivateId());
        if (rpcConnection == null) return;
        try {
            if (object != null) {
                JsonParser parser = new JsonParser();
                JsonObject jsonObject = parser.parse(object).getAsJsonObject();
                rpcConnection.getSession().sendNotification(method, jsonObject);
            } else {
                rpcConnection.getSession().sendNotification(method);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
