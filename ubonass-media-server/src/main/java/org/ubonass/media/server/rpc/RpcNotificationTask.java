package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Serializable;

public class RpcNotificationTask implements Runnable, Serializable {

    private static final long serialVersionUID = -375075629612750150L;

    private String clientId;
    private String method;
    private String object;

    public RpcNotificationTask(
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
        RpcNotificationService notificationService =
                RpcNotificationService.getContext();
        RpcConnection rpcConnection =
                notificationService.getRpcConnection(
                        notificationService.getClusterConnection(clientId).getSessionId());
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
