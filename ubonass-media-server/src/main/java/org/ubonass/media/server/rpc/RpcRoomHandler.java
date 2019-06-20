package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;

public class RpcRoomHandler extends RpcHandler{

    @Override
    public void handleRequest(Transaction transaction, Request<JsonObject> request) throws Exception {
        super.handleRequest(transaction, request);

    }
}
