package org.ubonass.media.server.utils;

import org.springframework.stereotype.Service;

import java.net.InetAddress;

@Service
public class GeoLocationByIpDummy implements GeoLocationByIp {

	@Override
	public GeoLocation getLocationByIp(InetAddress ipAddress) throws Exception {
		return null;
	}

}
