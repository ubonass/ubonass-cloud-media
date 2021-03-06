/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
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
 *
 */

ws = new WebSocket('wss://ubonass.com:8443/call');

/*ws.onmessage = function (message) {
    onmessage(message);
}
ws.onopen = function() {
    onopen();
}
var ws;/!* = new WebSocket('ws://localhost:8443/call');*!/*/
var videoInput;
var videoOutput;
var webRtcPeer;
var response;
var callerMessage;
var fromId;

var registerName = null;
var registerState = null;
const NOT_REGISTERED = 0;
const REGISTERING = 1;
const REGISTERED = 2;

var msgId = 0;
var userId;

var iceCandidatesList = new Array();

function setRegisterState(nextState) {
    switch (nextState) {
        case NOT_REGISTERED:
            enableButton('#register', 'register()');
            setCallState(NO_CALL);
            break;
        case REGISTERING:
            disableButton('#register');
            break;
        case REGISTERED:
            disableButton('#register');
            setCallState(NO_CALL);
            break;
        default:
            return;
    }
    registerState = nextState;
}

var callState = null;
const NO_CALL = 0;
const PROCESSING_CALL = 1;
const IN_CALL = 2;

function setCallState(nextState) {
    switch (nextState) {
        case NO_CALL:
            enableButton('#call', 'call()');
            disableButton('#terminate');
            disableButton('#play');
            break;
        case PROCESSING_CALL:
            disableButton('#call');
            disableButton('#terminate');
            disableButton('#play');
            break;
        case IN_CALL:
            disableButton('#call');
            enableButton('#terminate', 'stop()');
            disableButton('#play');
            break;
        default:
            return;
    }
    callState = nextState;
}

window.onload = function () {
    console = new Console();
    setRegisterState(NOT_REGISTERED);
    var drag = new Draggabilly(document.getElementById('videoSmall'));
    videoInput = document.getElementById('videoInput');
    videoOutput = document.getElementById('videoOutput');
    document.getElementById('name').focus();
}

window.onbeforeunload = function () {
    ws.close();
}

/**
 * JSON对象,访问其值直接message.key值
 * @param message
 */
function handlerResult(message) {
    //将JSON对象转换成JSON字符串
    var strMessage = JSON.stringify(message);
    console.info('Result message: ' + strMessage);
    switch (message.method) {
        case 'register':
            resgisterResponse(message);
            break;
        case 'call':
            callResponse(message);
            break;
    }
}

function handlerMethod(message) {
    //var strParamsMessage = JSON.stringify(message.params)
    var paramsMessage = message.params;
    console.info('Method :' + message.method +
        ' paramsMessage: ' + JSON.stringify(paramsMessage));
    //var objectParams = JSON.parse(message.params);
    switch (message.method) {
        case 'incomingCall':
            incomingCall(paramsMessage);
            break;
        case 'onCall':
            onCall(paramsMessage);
            break;
        /*case 'startCommunication':
            startCommunication(paramsMessage);
            break;*/
        case 'stopCommunication':
            console.info('Communication ended by remote peer');
            stop(true);
            break;
        case 'iceCandidate':
            var candidate = paramsMessage.candidate;
            webRtcPeer.addIceCandidate(candidate, function (error) {
                if (error)
                    return console.error('Error adding candidate: ' + error);
            });
            break;
        default:
            console.error('Unrecognized message', message.data);
    }
}

ws.onmessage = function onmessage(message) {
    var parsedMessage = JSON.parse(message.data);
    console.info('Received message: ' + message.data);
    if (parsedMessage.hasOwnProperty('result')) {
        handlerResult(parsedMessage.result);
    } else {
        handlerMethod(parsedMessage);
    }
}

function callResponse(message) {
    if (message.response != 'OK') {
        var errorMessage = message.response ? message.response
            : 'Unknown reason for register rejection.';
        console.log(errorMessage);
        alert('Error registering user. See console for further information.');
    } else {
        //发送ice....
        console.log("callResponse success");
        /*if (iceCandidatesList.length != 0) {
            for (var index in iceCandidatesList) {
                var message = {
                    candidate: iceCandidatesList[index]
                };
                sendMessageParams("onIceCandidate", message, msgId++);
            }
        }*/
    }
}

function resgisterResponse(message) {
    if (message.type == 'accepted') {
        setRegisterState(REGISTERED);
    } else {
        setRegisterState(NOT_REGISTERED);
        var errorMessage = message.message ? message.message
            : 'Unknown reason for register rejection.';
        console.log(errorMessage);
        alert('Error registering user. See console for further information.');
    }
}

function onCall(message) {

    if (message.event == 'accept') {//对方已接听
        setCallState(IN_CALL);
        webRtcPeer.processAnswer(message.sdpAnswer, function (error) {
            if (error)
                return console.error(error);
        });
    } else if (message.event == 'connected'){//已建立连接
        setCallState(IN_CALL);
        webRtcPeer.processAnswer(message.sdpAnswer, function (error) {
            if (error)
                return console.error(error);
        });
    } else if  (message.event == 'reject') {//对方已拒绝
        console.info('Call not accepted by peer. Closing call');
        var errorMessage = message.reason ? message.reason
            : 'Unknown reason for call rejection.';
        console.log(errorMessage);
        stop();
    }

    /*if (message.event != 'accept') {
        console.info('Call not accepted by peer. Closing call');
        var errorMessage = message.reason ? message.reason
            : 'Unknown reason for call rejection.';
        console.log(errorMessage);
        stop();
    } else {
        setCallState(IN_CALL);
        webRtcPeer.processAnswer(message.sdpAnswer, function (error) {
            if (error)
                return console.error(error);
        });
    }*/
}

function startCommunication(message) {
    setCallState(IN_CALL);
    webRtcPeer.processAnswer(message.sdpAnswer, function (error) {
        if (error)
            return console.error(error);
    });
}

function incomingCall(message) {
    // If bussy just reject without disturbing user
    if (callState != NO_CALL) {
        var response = {
            /*id: 'incomingCallResponse',*/
            fromId: message.fromId,
            event: 'busy',
            media: message.media,
            reason: 'bussy'
        };
        return sendMessageParams("onCall", response, msgId++);
    }

    setCallState(PROCESSING_CALL);
    if (confirm('User ' + message.fromId
        + ' is calling you. Do you accept the call?')) {
        showSpinner(videoInput, videoOutput);

        fromId = message.fromId;
        var options = {
            localVideo: videoInput,
            remoteVideo: videoOutput,
            onicecandidate: onIceCandidate,
            onerror: onError
        }
        webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
            function (error) {
                if (error) {
                    return console.error(error);
                }
                webRtcPeer.generateOffer(onOfferIncomingCall);
            });

    } else {
        var response = {
            fromId: message.fromId,
            event: 'reject',
            media: message.media,
            reason: "hangup"
        };
        sendMessageParams("onCall", response, msgId++);
        stop();
    }
}

function onOfferIncomingCall(error, offerSdp) {
    if (error)
        return console.error("Error generating the offer");
    var response = {
        media: 'all',
        fromId: fromId,
        event: 'accept',
        sdpOffer: offerSdp
    };
    sendMessageParams("onCall", response, msgId++);
}

function register() {

    userId = document.getElementById('name').value;
    if (userId == '') {
        window.alert('You must insert your user name');
        return;
    }
    setRegisterState(REGISTERING);

    var message = {
        userId: userId,
    };
    sendMessageParams("register", message, msgId++);
    document.getElementById('peer').focus();
}

function call() {
    if (document.getElementById('peer').value == '') {
        window.alert('You must specify the peer name');
        return;
    }
    setCallState(PROCESSING_CALL);
    showSpinner(videoInput, videoOutput);

    var options = {
        localVideo: videoInput,
        remoteVideo: videoOutput,
        onicecandidate: onIceCandidate,
        onerror: onError
    }
    webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
        function (error) {
            if (error) {
                return console.error(error);
            }
            webRtcPeer.generateOffer(onOfferCall);
        });
}

function onOfferCall(error, offerSdp) {
    if (error)
        return console.error('Error generating the offer');
    console.log('Invoking SDP offer callback function');

    var message = {
        fromId: userId,
        targetId: document.getElementById('peer').value,
        media: "all",
        sdpOffer: offerSdp
    };
    sendMessageParams("call", message, msgId++);
}

function stop(message) {
    setCallState(NO_CALL);
    if (webRtcPeer) {
        webRtcPeer.dispose();
        webRtcPeer = null;
        if (!message) {
            var message = {
                event: 'hangup'
            }
            sendMessage("onCall", message,msgId++);
        }
    }
    hideSpinner(videoInput, videoOutput);
}

function onError() {
    setCallState(NO_CALL);
}

/**
 * 先进行保存当接收到call返回的时候再发送
 * @param candidate
 */
function onIceCandidate(candidate) {
    //iceCandidatesList.push(candidate);
    console.log("Local candidate" + JSON.stringify(candidate));
    var message = {
        candidate: candidate.candidate,
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex
    };
    sendMessageParams("onIceCandidate", message, msgId++);
}

function sendMessage(message) {
    var jsonMessage = JSON.stringify(message);
    console.log('Senging message: ' + jsonMessage);
    ws.send(jsonMessage);
}

/**
 *
 * @param method
 * @param params 为一个map集合
 */
function sendMessageParams(method, params, id) {
    var object = {
        'id': id,
        'method': method,
        'params': params,
        'jsonrpc': '2.0'
    }
    var jsonMessage = JSON.stringify(object);
    console.log('Senging message: ' + jsonMessage);
    ws.send(jsonMessage);
}

function sendMessage(method, id) {
    var object = {
        'id': id,
        'method': method,
        'jsonrpc': '2.0'
    }
    var jsonMessage = JSON.stringify(object);
    console.log('Senging message: ' + jsonMessage);
    ws.send(jsonMessage);
}


function showSpinner() {
    for (var i = 0; i < arguments.length; i++) {
        arguments[i].poster = './img/transparent-1px.png';
        arguments[i].style.background = 'center transparent url("./img/spinner.gif") no-repeat';
    }
}

function hideSpinner() {
    for (var i = 0; i < arguments.length; i++) {
        arguments[i].src = '';
        arguments[i].poster = './img/webrtc.png';
        arguments[i].style.background = '';
    }
}

function disableButton(id) {
    $(id).attr('disabled', true);
    $(id).removeAttr('onclick');
}

function enableButton(id, functionName) {
    $(id).attr('disabled', false);
    $(id).attr('onclick', functionName);
}

/**
 * Lightbox utility (to display media pipeline image in a modal dialog)
 */
$(document).delegate('*[data-toggle="lightbox"]', 'click', function (event) {
    event.preventDefault();
    $(this).ekkoLightbox();
});
