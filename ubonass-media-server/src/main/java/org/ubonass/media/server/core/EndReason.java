
package org.ubonass.media.server.core;

public enum EndReason {

	unsubscribe,
	unpublish,
	disconnect,
	forceUnpublishByUser,
	forceUnpublishByServer,
	forceDisconnectByUser,
	forceDisconnectByServer,
	lastParticipantLeft,
	networkDisconnect,
	mediaServerDisconnect,
	openviduServerStopped,
	recordingStoppedByServer,
	automaticStop,
	sessionClosedByServer

}
