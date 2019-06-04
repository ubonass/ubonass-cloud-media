/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ubonass.media.client.internal;

/**
 * This class defines constant values of client-server messages and their
 * parameters.
 *
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class ProtocolElements {


    public static final String RESPONSE_OPERATION_METHOD = "operation";

    // ---------------------------- CLIENT REQUESTS -----------------------

    public static final String SENDMESSAGE_ROOM_METHOD = "sendMessage";
    public static final String SENDMESSAGE_MESSAGE_PARAM = "message";

    public static final String KEEPLIVE_METHOD = "keepLive";
    public static final String KEEPLIVE_USERID_PARAM = "userId";//user or id

    public static final String LEAVEROOM_METHOD = "leaveRoom";

    public static final String JOINROOM_METHOD = "joinRoom";
    public static final String JOINROOM_USER_PARAM = "user";
    public static final String JOINROOM_TOKEN_PARAM = "token";
    public static final String JOINROOM_ROOM_PARAM = "session";
    public static final String JOINROOM_METADATA_PARAM = "metadata";
    public static final String JOINROOM_SECRET_PARAM = "secret";
    public static final String JOINROOM_PLATFORM_PARAM = "platform";
    public static final String JOINROOM_RECORDER_PARAM = "recorder";

    public static final String JOINROOM_PEERID_PARAM = "id";
    public static final String JOINROOM_PEERCREATEDAT_PARAM = "createdAt";
    public static final String JOINROOM_PEERSTREAMS_PARAM = "streams";
    public static final String JOINROOM_PEERSTREAMID_PARAM = "id";
    public static final String JOINROOM_PEERSTREAMHASAUDIO_PARAM = "hasAudio";
    public static final String JOINROOM_PEERSTREAMHASVIDEO_PARAM = "hasVideo";
    public static final String JOINROOM_PEERSTREAMAUDIOACTIVE_PARAM = "audioActive";
    public static final String JOINROOM_PEERSTREAMVIDEOACTIVE_PARAM = "videoActive";
    public static final String JOINROOM_PEERSTREAMTYPEOFVIDEO_PARAM = "typeOfVideo";
    public static final String JOINROOM_PEERSTREAMFRAMERATE_PARAM = "frameRate";
    public static final String JOINROOM_PEERSTREAMVIDEODIMENSIONS_PARAM = "videoDimensions";
    public static final String JOINROOM_PEERSTREAMFILTER_PARAM = "filter";

    public static final String PUBLISHVIDEO_METHOD = "publishVideo";
    public static final String PUBLISHVIDEO_SDPOFFER_PARAM = "sdpOffer";
    public static final String PUBLISHVIDEO_DOLOOPBACK_PARAM = "doLoopback";
    public static final String PUBLISHVIDEO_SDPANSWER_PARAM = "sdpAnswer";
    public static final String PUBLISHVIDEO_STREAMID_PARAM = "id";
    public static final String PUBLISHVIDEO_CREATEDAT_PARAM = "createdAt";
    public static final String PUBLISHVIDEO_HASAUDIO_PARAM = "hasAudio";
    public static final String PUBLISHVIDEO_HASVIDEO_PARAM = "hasVideo";
    public static final String PUBLISHVIDEO_AUDIOACTIVE_PARAM = "audioActive";
    public static final String PUBLISHVIDEO_VIDEOACTIVE_PARAM = "videoActive";
    public static final String PUBLISHVIDEO_TYPEOFVIDEO_PARAM = "typeOfVideo";
    public static final String PUBLISHVIDEO_FRAMERATE_PARAM = "frameRate";
    public static final String PUBLISHVIDEO_VIDEODIMENSIONS_PARAM = "videoDimensions";
    public static final String PUBLISHVIDEO_KURENTOFILTER_PARAM = "filter";

    public static final String UNPUBLISHVIDEO_METHOD = "unpublishVideo";

    public static final String RECEIVEVIDEO_METHOD = "receiveVideoFrom";
    public static final String RECEIVEVIDEO_SDPOFFER_PARAM = "sdpOffer";
    public static final String RECEIVEVIDEO_SENDER_PARAM = "sender";
    public static final String RECEIVEVIDEO_SDPANSWER_PARAM = "sdpAnswer";

    public static final String UNSUBSCRIBEFROMVIDEO_METHOD = "unsubscribeFromVideo";
    public static final String UNSUBSCRIBEFROMVIDEO_SENDER_PARAM = "sender";

    public static final String ONICECANDIDATE_METHOD = "onIceCandidate";
    public static final String ONICECANDIDATE_EPNAME_PARAM = "endpointName";
    public static final String ONICECANDIDATE_CANDIDATE_PARAM = "candidate";
    public static final String ONICECANDIDATE_SDPMIDPARAM = "sdpMid";
    public static final String ONICECANDIDATE_SDPMLINEINDEX_PARAM = "sdpMLineIndex";

    public static final String CUSTOMREQUEST_METHOD = "customRequest";

    public static final String STREAMPROPERTYCHANGED_METHOD = "streamPropertyChanged";
    public static final String STREAMPROPERTYCHANGED_CONNECTIONID_PARAM = "connectionId";
    public static final String STREAMPROPERTYCHANGED_STREAMID_PARAM = "streamId";
    public static final String STREAMPROPERTYCHANGED_PROPERTY_PARAM = "property";
    public static final String STREAMPROPERTYCHANGED_NEWVALUE_PARAM = "newValue";
    public static final String STREAMPROPERTYCHANGED_REASON_PARAM = "reason";

    public static final String FORCEDISCONNECT_METHOD = "forceDisconnect";
    public static final String FORCEDISCONNECT_CONNECTIONID_PARAM = "connectionId";

    public static final String FORCEUNPUBLISH_METHOD = "forceUnpublish";
    public static final String FORCEUNPUBLISH_STREAMID_PARAM = "streamId";

    public static final String APPLYFILTER_METHOD = "applyFilter";
    public static final String FILTER_STREAMID_PARAM = "streamId";
    public static final String FILTER_TYPE_PARAM = "type";
    public static final String FILTER_OPTIONS_PARAM = "options";
    public static final String FILTER_METHOD_PARAM = "method";
    public static final String FILTER_PARAMS_PARAM = "params";
    public static final String EXECFILTERMETHOD_METHOD = "execFilterMethod";
    public static final String EXECFILTERMETHOD_LASTEXECMETHOD_PARAM = "lastExecMethod";
    public static final String REMOVEFILTER_METHOD = "removeFilter";
    public static final String ADDFILTEREVENTLISTENER_METHOD = "addFilterEventListener";
    public static final String REMOVEFILTEREVENTLISTENER_METHOD = "removeFilterEventListener";

    public static final String FILTEREVENTDISPATCHED_METHOD = "filterEventDispatched";
    public static final String FILTEREVENTLISTENER_CONNECTIONID_PARAM = "connectionId";
    public static final String FILTEREVENTLISTENER_STREAMID_PARAM = "streamId";
    public static final String FILTEREVENTLISTENER_FILTERTYPE_PARAM = "filterType";
    public static final String FILTEREVENTLISTENER_EVENTTYPE_PARAM = "eventType";
    public static final String FILTEREVENTLISTENER_DATA_PARAM = "data";

    // ---------------------------- SERVER RESPONSES & EVENTS -----------------

    public static final String PARTICIPANTJOINED_METHOD = "participantJoined";
    public static final String PARTICIPANTJOINED_USER_PARAM = "id";
    public static final String PARTICIPANTJOINED_CREATEDAT_PARAM = "createdAt";
    public static final String PARTICIPANTJOINED_METADATA_PARAM = "metadata";

    public static final String PARTICIPANTLEFT_METHOD = "participantLeft";
    public static final String PARTICIPANTLEFT_NAME_PARAM = "connectionId";
    public static final String PARTICIPANTLEFT_REASON_PARAM = "reason";

    public static final String PARTICIPANTEVICTED_METHOD = "participantEvicted";
    public static final String PARTICIPANTEVICTED_CONNECTIONID_PARAM = "connectionId";
    public static final String PARTICIPANTEVICTED_REASON_PARAM = "reason";

    public static final String PARTICIPANTPUBLISHED_METHOD = "participantPublished";
    public static final String PARTICIPANTPUBLISHED_USER_PARAM = "id";
    public static final String PARTICIPANTPUBLISHED_STREAMS_PARAM = "streams";
    public static final String PARTICIPANTPUBLISHED_STREAMID_PARAM = "id";
    public static final String PARTICIPANTPUBLISHED_CREATEDAT_PARAM = "createdAt";
    public static final String PARTICIPANTPUBLISHED_HASAUDIO_PARAM = "hasAudio";
    public static final String PARTICIPANTPUBLISHED_HASVIDEO_PARAM = "hasVideo";
    public static final String PARTICIPANTPUBLISHED_AUDIOACTIVE_PARAM = "audioActive";
    public static final String PARTICIPANTPUBLISHED_VIDEOACTIVE_PARAM = "videoActive";
    public static final String PARTICIPANTPUBLISHED_TYPEOFVIDEO_PARAM = "typeOfVideo";
    public static final String PARTICIPANTPUBLISHED_FRAMERATE_PARAM = "frameRate";
    public static final String PARTICIPANTPUBLISHED_VIDEODIMENSIONS_PARAM = "videoDimensions";
    public static final String PARTICIPANTPUBLISHED_FILTER_PARAM = "filter";

    public static final String PARTICIPANTUNPUBLISHED_METHOD = "participantUnpublished";
    public static final String PARTICIPANTUNPUBLISHED_NAME_PARAM = "connectionId";
    public static final String PARTICIPANTUNPUBLISHED_REASON_PARAM = "reason";

    public static final String PARTICIPANTSENDMESSAGE_METHOD = "sendMessage";
    public static final String PARTICIPANTSENDMESSAGE_DATA_PARAM = "data";
    public static final String PARTICIPANTSENDMESSAGE_FROM_PARAM = "from";
    public static final String PARTICIPANTSENDMESSAGE_TYPE_PARAM = "type";

    public static final String ROOMCLOSED_METHOD = "roomClosed";
    public static final String ROOMCLOSED_ROOM_PARAM = "sessionId";

    public static final String MEDIAERROR_METHOD = "mediaError";
    public static final String MEDIAERROR_ERROR_PARAM = "error";

    public static final String ICECANDIDATE_METHOD = "iceCandidate";
    public static final String ICECANDIDATE_SENDERCONNECTIONID_PARAM = "senderConnectionId";
    public static final String ICECANDIDATE_EPNAME_PARAM = "endpointName";
    public static final String ICECANDIDATE_CANDIDATE_PARAM = "candidate";
    public static final String ICECANDIDATE_SDPMID_PARAM = "sdpMid";
    public static final String ICECANDIDATE_SDPMLINEINDEX_PARAM = "sdpMLineIndex";

    public static final String RECORDINGSTARTED_METHOD = "recordingStarted";
    public static final String RECORDINGSTARTED_ID_PARAM = "id";
    public static final String RECORDINGSTARTED_NAME_PARAM = "name";
    public static final String RECORDINGSTOPPED_REASON_PARAM = "reason";

    public static final String RECORDINGSTOPPED_METHOD = "recordingStopped";
    public static final String RECORDINGSTOPPED_ID_PARAM = "id";

    public static final String CUSTOM_NOTIFICATION = "custonNotification";

    public static final String RECORDER_PARTICIPANT_PUBLICID = "RECORDER";
    /**
     * A用户发送邀请,消息打包到InvitedInfo
     */
    public static final String INVITED_METHOD = "invited";
    public static final String INVITED_USER_PARAM = "userId";//from user id
    public static final String INVITED_NUMBER_PARAM = "number"; //一共邀请几个
    public static final String INVITED_TARGETS_PARAM = "targets";//目标集合
    public static final String INVITED_TYPEMEDIA_PARAM = "typeOfMedia";//类型,是audio,video,all还是全部
    public static final String INVITED_SESSION_PARAM = "session";//是否使用房间

    /**
     * 服务器收到A的请求后发送通知到指定用户
     * 消息封装到OnInvitedInfo
     */
    public static final String ONINVITED_METHOD = "onInvited";
    public static final String ONINVITED_FROMUSER_PARAM = "fromId";//from user id
    public static final String ONINVITED_TARGETUSER_PARAM = "targetId";//接收者的ID
    public static final String ONINVITED_TYPEMEDIA_PARAM = "typeOfMedia";//类型,是audio,video,all还是全部
    public static final String ONINVITED_SESSION_PARAM = "session";//类型,是audio,video,all还是全部
    public static final String ONINVITED_TYPEEVENT_PARAM = "event";//事件类型
    public static final String ONINVITED_EVENT_CALL = "incall";//来电
    public static final String ONINVITED_EVENT_ACCEPT = "accept";//接听
    public static final String ONINVITED_EVENT_REFUSE = "refuse";//拒接
    public static final String ONINVITED_EVENT_HANGUP = "hangup";//挂断
    public static final String ONINVITED_EVENT_BUSY = "busy";//繁忙
    /**
     * B用户想服务器返回信息
     */
    /*public static final String INVITED_ANSWER_METHOD = "invitedAnswer";
    public static final String INVITED_ANSWER_FROMUSER_PARAM = "fromId";
    public static final String INVITED_ANSWER_USER_PARAM = "userId";
    public static final String INVITED_ANSWER_TYPEMEDIA_PARAM = "typeOfMedia";
    public static final String INVITED_ANSWER_TYPEANSWER_PARAM = "answerType";*/
/*
	用户AAA发来的信息
	typeOfMedia：[audio/media/video]
	typeOfSession :"{"type":room,"session":"AA"}",或 "{"type":call}",如果是单人通话就不带session
	{
	"id":1,
		"method":"invited",
		"params":{
			"userId": "AAA",
			"typeOfSession": "{"type":room,"session":"AA"}",
			"typeOfMedia": “all”,
			"number": 4,
			"targets":[{"userId":"egrgreara"},{"userId":"sgsgdg"},{"userId":"gfhdhtrhr"},{"userId":"sfsdfdsfsdf"}]

	},
	"jsonrpc":"2.0"

	发送消息到target_0
	{
		"method":"onInvited",
		"params":{
			"fromId": "AAA",
			"typeOfSession": "{"type":room,"session":"AA"}",
			"typeOfMedia": “all”,
			"event":"call,accept,refuse,hangup,busy"
		},
		"jsonrpc":"2.0"
	}
	//自身返回不需要了
	/*{  "result":
		{
			"invited"："OK"
			"userId": "AAA",
			"typeOfSession": "{"type":room,"session":"AA"}",
			"typeOfMedia": “all”,
			"number": 4,
			"targets":[{"userId":"egrgreara,""state","online"},
						{"userId":"sgsgdg",""state","online"},
						{"userId":"gfhdhtrhr",""state","offline"},
						{"userId":"sfsdfdsfsdf",""state","offline"}]
		},
		"id":1,
		"jsonrpc":"2.0"
	}
    //onInvited 回复
	{
	"id":1,
		"method":"onInvited",
		"params":{
			"fromId":"邀请发起者ID"
			"userId": "回复者的userId",
			"typeOfMedia": “all”,指定媒体类型
			"event":"call,accept,refuse,hangup,busy"
			},
	"jsonrpc":"2.0"
	}
	 */

    public static final String REGISTER_METHOD = "register";//去电由A发给服务端的请求
    public static final String REGISTER_USER_PARAM = "userId";//去电由A发给服务端的请求
    public static final String REGISTER_TYPE_PARAM = "type";//服务器回复参数 rejected,accepted
    public static final String REGISTER_MESSAGE_PARAM = "message";//message 原因
    public static final String REGISTER_TYPE_REJECTED = "rejected";//
    public static final String REGISTER_TYPE_ACCEPTD = "accepted";//
    /**
     * A->服务器
     */
    public static final String CALL_METHOD = "call";//去电由A发给服务端的请求
    public static final String CALL_FROMUSER_PARAM = "fromId";//
    public static final String CALL_TARGETUSER_PARAM = "targetId";
    public static final String CALL_MEDIA_PARAM = "media";//
    public static final String CALL_SDPOFFER_PARAM = "sdpOffer";//
    public static final String CALL_RESPONSE_PARAM = "response";//服务器回复A,OK表示成功


    /**
     * B->服务器->A的通知
     */
    /*public static final String ONCALL_METHOD = "onCall";
    public static final String ONCALL_SDPANSWER_PARAM = "sdpAnswer";//服务器回复A
    public static final String ONCALL_RESPONSE_TYPE_PARAM = "type";//服务器回复A
    public static final String ONCALL_RESPONSE_TYPE_ACCEPT = "accept";//接听
    public static final String ONCALL_RESPONSE_TYPE_REJECTED = "rejected";//拒接
    public static final String ONCALL_RESPONSE_TYPE_HANGUP = "hangup";//挂断
    public static final String ONCALL_RESPONSE_TYPE_BUSY = "busy";//繁忙*/

    /**
     * 服务器->B的来电通知
     */
    public static final String INCOMINGCALL_METHOD = "incomingCall";//来电请求,由服务器发送给用户B
    public static final String INCOMINGCALL_FROMUSER_PARAM = "fromId";//
    //public static final String INCOMINGCALL_TARGETUSER_PARAM = "targetId";
    //public static final String INCOMINGCALL_SESSION_PARAM = "sessionId";//有服务器随机生成
    public static final String INCOMINGCALL_MEDIA_PARAM = "media";//video or audio or all,default all

    /**
     * B->服务器的应答回复
     */
    public static final String ONINCOMING_CALL_METHOD = "onIncomingCall";
    public static final String ONIINCOMING_CALL_FROMUSER_PARAM = "fromId";//谁是发起者,和call中的fromId一至
    //public static final String ONIINCOMING_CALL_TARGETUSER_PARAM = "targetId";
    public static final String ONIINCOMING_CALL_SDPOFFER_PARAM = "sdpOffer";
    public static final String ONIINCOMING_CALL_SDPANSWER_PARAM = "sdpAnswer";//这是服务器回复给发起者的
    //public static final String INCOMING_CALL_SESSION_PARAM = "sessionId";//有服务器随机生成
    public static final String ONIINCOMING_CALL_MEDIA_PARAM = "media";//
    public static final String ONIINCOMING_CALL_TYPE_PARAM = "type";
    public static final String ONIINCOMING_CALL_TYPE_ACCEPT = "accept";//接听
    public static final String ONIINCOMING_CALL_TYPE_REJECT = "reject";//拒接
    public static final String ONIINCOMING_CALL_REJECT_REASON = "reason";//拒接原因
    /**
     * 开始视频通话(服务器->B
     */
    public static final String START_COMMUNICATION_METHOD = "startCommunication";
    public static final String START_COMMUNICATION_SDPANSWER_PARAM= "sdpAnswer";

    public static final String STOP_COMMUNICATION_METHOD = "stopCommunication";

    public static final String CALL_STOP_METHOD = "stop";



}
