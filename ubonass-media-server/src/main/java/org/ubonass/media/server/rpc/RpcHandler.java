package org.ubonass.media.server.rpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.kurento.jsonrpc.DefaultJsonRpcHandler;
import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.internal.ws.WebSocketServerSession;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.kurento.KurentoClientProvider;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RpcHandler extends DefaultJsonRpcHandler<JsonObject> {

    private static final Logger logger = LoggerFactory.getLogger(RpcHandler.class);
    private ConcurrentMap<String, Boolean> webSocketEOFTransportError = new ConcurrentHashMap<>();
    /**
     * key为用户远程连的客户唯一标识,Value为Session
     */
    private Map<String, Session> onlineUsers = new ConcurrentHashMap<>();
    @Autowired
    private RpcNotificationService notificationService;

    @Autowired
    private KurentoClientProvider kcProvider;

    @Override
    public void handleRequest(Transaction transaction, Request<JsonObject> request)
            throws Exception {
        /*String participantPrivateId =
                getParticipantPrivateIdByTransaction(transaction);
        logger.info("WebSocket session #{} - Request: {}", participantPrivateId, request);
        RpcConnection rpcConnection;
        if (ProtocolElements.KEEPLIVE_METHOD.equals(request.getMethod())) {
            // Store new RpcConnection information if method 'keepLive'
            rpcConnection = notificationService.newRpcConnection(transaction, request);
        } else if (notificationService.getRpcConnection(participantPrivateId) == null) {
            // Throw exception if any method is called before 'joinCloud'
            logger.warn(
                    "No connection found for participant with privateId {} when trying to execute method '{}'. Method 'Session.connect()' must be the first operation called in any session",
                    participantPrivateId, request.getMethod());
            throw new CloudMediaException(Code.TRANSPORT_ERROR_CODE,
                    "No connection found for participant with privateId " + participantPrivateId
                            + ". Method 'Session.connect()' must be the first operation called in any session");
        }

        rpcConnection = notificationService.addTransaction(transaction, request);

        transaction.startAsync();

        switch (request.getMethod()) {
            case ProtocolElements.KEEPLIVE_METHOD:
                keepLive(rpcConnection, request);
                break;
            case ProtocolElements.INVITED_METHOD:
                invited(rpcConnection, request);
                break;
            case ProtocolElements.ONINVITED_METHOD:
                onInvited(rpcConnection, request);
                break;
            case ProtocolElements.VOIP_CALL_METHOD:
                call(rpcConnection, request);
                break;
            case ProtocolElements.VOIP_CALLANSWER_METHOD:
                callAnswer(rpcConnection, request);
                break;
            default:
                //log.error("Unrecognized request {}", request);
                break;
        }*/
    }

    private void keepLive(RpcConnection rpcConnection, Request<JsonObject> request) {
        JsonObject result = new JsonObject();
        result.addProperty(ProtocolElements.KEEPLIVE_METHOD, "OK");
        notificationService.sendResponse(rpcConnection.getParticipantPrivateId(),
                request.getId(), result);

    }


    private void invited(RpcConnection rpcConnection, Request<JsonObject> request) {
        logger.info("Params :" + request.getParams().toString());
        String fromId = getStringParam(request, ProtocolElements.INVITED_USER_PARAM);
        int number = getIntParam(request, ProtocolElements.INVITED_NUMBER_PARAM);
        String targetUsers = getStringParam(request, ProtocolElements.INVITED_TARGETS_PARAM);
        String typeOfMedia = getStringParam(request, ProtocolElements.INVITED_TYPEMEDIA_PARAM);
        String session = null;
        if (request.getParams().has(ProtocolElements.INVITED_SESSION_PARAM))
            session = getStringParam(request, ProtocolElements.INVITED_SESSION_PARAM);

        JsonObject result = new JsonObject();
        JsonArray resultTargetArray = new JsonArray();
        /** 首先判断这个target id是否在userIdAndPrivateId集合当中有
         * 如果没有说明不在线需要返回,如果有则向目标发起通知,通知其加入房间*/
        if (number > 0) {
            try {
                JsonArray targetArray =
                        new JsonParser().parse(targetUsers).getAsJsonArray();
                logger.info("targetArray size:" + targetArray.size());
                for (int i = 0; i < targetArray.size(); i++) {
                    JsonObject notifParams = new JsonObject();
                    JsonObject target = targetArray.get(i).getAsJsonObject();
                    String targetId = target.get("userId").getAsString();
                    //判断targetId是否在sessions集合当中
                    boolean targetOnline = onlineUsers.containsKey(targetId);
                    if (targetOnline) {
                        Session targetSession = onlineUsers.get(targetId);
                        notifParams.addProperty(ProtocolElements.ONINVITED_FROMUSER_PARAM, fromId);
                        notifParams.addProperty(ProtocolElements.ONINVITED_TARGETUSER_PARAM, targetId);
                        notifParams.addProperty(ProtocolElements.ONINVITED_TYPEMEDIA_PARAM, typeOfMedia);
                        notifParams.addProperty(ProtocolElements.ONINVITED_TYPEEVENT_PARAM,
                                ProtocolElements.ONINVITED_EVENT_CALL);
                        if (session != null)
                            notifParams.addProperty(ProtocolElements.ONINVITED_SESSION_PARAM, session);
                        targetSession.sendNotification(ProtocolElements.ONINVITED_METHOD, notifParams);
                    }
                    //回復客戶端端
                    JsonObject object = new JsonObject();
                    object.addProperty(ProtocolElements.INVITED_USER_PARAM, targetId);
                    object.addProperty("state", targetOnline ? "online" : "offline");
                    resultTargetArray.add(object);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            result.addProperty(ProtocolElements.INVITED_TARGETS_PARAM, String.valueOf(resultTargetArray));
        }

        result.addProperty(ProtocolElements.INVITED_METHOD, "OK");
        result.addProperty(ProtocolElements.INVITED_USER_PARAM, fromId);
        result.addProperty(ProtocolElements.INVITED_NUMBER_PARAM, number);
        if (session != null)
            result.addProperty(ProtocolElements.INVITED_SESSION_PARAM, session);
        result.addProperty(ProtocolElements.INVITED_TYPEMEDIA_PARAM, typeOfMedia);
        notificationService.sendResponse(rpcConnection.getParticipantPrivateId(),
                request.getId(), result);
    }

    private void onInvited(RpcConnection rpcConnection, Request<JsonObject> request) {
        String targetId = getStringParam(request, ProtocolElements.ONINVITED_TARGETUSER_PARAM);//目标接收者的ID
        String fromId = getStringParam(request, ProtocolElements.ONINVITED_FROMUSER_PARAM);//发送者的ID
        String event = getStringParam(request, ProtocolElements.ONINVITED_TYPEEVENT_PARAM);
        String typeOfMedia = null;
        String session = null;
        if (request.getParams().has(ProtocolElements.ONINVITED_SESSION_PARAM))
            session = getStringParam(request, ProtocolElements.ONINVITED_SESSION_PARAM);
        if (request.getParams().has(ProtocolElements.ONINVITED_TYPEMEDIA_PARAM))
            typeOfMedia = getStringParam(request, ProtocolElements.ONINVITED_TYPEMEDIA_PARAM);
        /**
         * 判断目标用户是否存在
         */
        if (onlineUsers.containsKey(targetId)) {
            Session targetSession = onlineUsers.get(targetId);
            JsonObject notifParams = new JsonObject();
            notifParams.addProperty(ProtocolElements.ONINVITED_TARGETUSER_PARAM, targetId);
            notifParams.addProperty(ProtocolElements.ONINVITED_FROMUSER_PARAM, fromId);
            if (typeOfMedia != null)
                notifParams.addProperty(ProtocolElements.ONINVITED_TYPEMEDIA_PARAM, typeOfMedia);
            if (session != null)
                notifParams.addProperty(ProtocolElements.ONINVITED_SESSION_PARAM, session);
            notifParams.addProperty(ProtocolElements.ONINVITED_TYPEEVENT_PARAM, event);
            try {
                targetSession.sendNotification(ProtocolElements.ONINVITED_METHOD, notifParams);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public static String getStringParam(Request<JsonObject> request, String key) {
        if (request.getParams() == null || request.getParams().get(key) == null) {
            throw new RuntimeException("Request element '" + key + "' is missing in method '" + request.getMethod()
                    + "'. CHECK THAT 'openvidu-server' AND 'openvidu-browser' SHARE THE SAME VERSION NUMBER");
        }
        return request.getParams().get(key).getAsString();
    }

    public static int getIntParam(Request<JsonObject> request, String key) {
        if (request.getParams() == null || request.getParams().get(key) == null) {
            throw new RuntimeException("Request element '" + key + "' is missing in method '" + request.getMethod()
                    + "'. CHECK THAT 'openvidu-server' AND 'openvidu-browser' SHARE THE SAME VERSION NUMBER");
        }
        return request.getParams().get(key).getAsInt();
    }

    public static boolean getBooleanParam(Request<JsonObject> request, String key) {
        if (request.getParams() == null || request.getParams().get(key) == null) {
            throw new RuntimeException("Request element '" + key + "' is missing in method '" + request.getMethod()
                    + "'. CHECK THAT 'openvidu-server' AND 'openvidu-browser' SHARE THE SAME VERSION NUMBER");
        }
        return request.getParams().get(key).getAsBoolean();
    }

    public static JsonElement getParam(Request<JsonObject> request, String key) {
        if (request.getParams() == null || request.getParams().get(key) == null) {
            throw new RuntimeException("Request element '" + key + "' is missing in method '" + request.getMethod()
                    + "'. CHECK THAT 'openvidu-server' AND 'openvidu-browser' SHARE THE SAME VERSION NUMBER");
        }
        return request.getParams().get(key);
    }

    public String getParticipantPrivateIdByTransaction(Transaction transaction) {
        String participantPrivateId = null;
        try {
            participantPrivateId = transaction.getSession().getSessionId();
        } catch (Throwable e) {
            logger.error("Error getting WebSocket session ID from transaction {}", transaction, e);
            throw e;
        }
        return participantPrivateId;
    }

    @Override
    public void afterConnectionEstablished(Session rpcSession) throws Exception {
        super.afterConnectionEstablished(rpcSession);
        logger.info("After connection established for WebSocket session: {},attributes={}",
                rpcSession.getSessionId(), rpcSession.getAttributes());
        if (rpcSession instanceof WebSocketServerSession) {
            InetAddress address;
            HttpHeaders headers = ((WebSocketServerSession) rpcSession).getWebSocketSession().getHandshakeHeaders();
            if (headers.containsKey("x-real-ip")) {
                address = InetAddress.getByName(headers.get("x-real-ip").get(0));
            } else {
                address = ((WebSocketServerSession) rpcSession).getWebSocketSession().getRemoteAddress().getAddress();
            }
            rpcSession.getAttributes().put("remoteAddress", address);
            Map<String, Object> attributes =
                    ((WebSocketServerSession) rpcSession).getWebSocketSession().getAttributes();
            if (attributes.containsKey("httpSession")) {
                HttpSession httpSession = (HttpSession) ((WebSocketServerSession) rpcSession).getWebSocketSession()
                        .getAttributes().get("httpSession");
                rpcSession.getAttributes().put("httpSession", httpSession);
            }
            if (attributes.containsKey("userId")) {
                String userId =
                        (String) ((WebSocketServerSession) rpcSession)
                                .getWebSocketSession()
                                .getAttributes()
                                .get("userId");
                onlineUsers.put(userId, rpcSession);
            }
        }
    }

    @Override
    public void afterConnectionClosed(Session rpcSession, String status) throws Exception {
        super.afterConnectionClosed(rpcSession, status);
        logger.info("After connection closed for WebSocket session: {} - Status: {}", rpcSession.getSessionId(), status);
        String rpcSessionId = rpcSession.getSessionId();
        if (rpcSession instanceof WebSocketServerSession) {
            Map<String, Object> attributes =
                    ((WebSocketServerSession) rpcSession).getWebSocketSession().getAttributes();
            if (attributes.containsKey("userId")) {
                String userId = (String) ((WebSocketServerSession) rpcSession)
                        .getWebSocketSession()
                        .getAttributes()
                        .get("userId");
                onlineUsers.remove(userId);
                logger.info("afterConnectionClosed userId:" + userId);
            }
        }
        RpcConnection rpc =
                this.notificationService.closeRpcSession(rpcSessionId);
        if (rpc != null) rpc = null;
    }

    @Override
    public void handleTransportError(Session rpcSession, Throwable exception) throws Exception {
        logger.error("Transport exception for WebSocket session: {} - Exception: {}", rpcSession.getSessionId(),
                exception.getMessage());
        if ("IOException".equals(exception.getClass().getSimpleName())
                && "Broken pipe".equals(exception.getCause().getMessage())) {
            logger.warn("Parcipant with private id {} unexpectedly closed the websocket", rpcSession.getSessionId());
        }
        if ("EOFException".equals(exception.getClass().getSimpleName())) {
            // Store WebSocket connection interrupted exception for this web socket to
            // automatically evict the participant on "afterConnectionClosed" event
            this.webSocketEOFTransportError.put(rpcSession.getSessionId(), true);
        }
    }

    @Override
    public void handleUncaughtException(Session rpcSession, Exception exception) {
        logger.error("Uncaught exception for WebSocket session: {} - Exception: {}",
                rpcSession.getSessionId(), exception);
    }

    @Override
    public List<String> allowedOrigins() {
        return Arrays.asList("*");
    }

}
