package org.ubonass.media.server.utils;

import lombok.Data;

@Data
public class GeoLocation {

	private String country;
	private String city;
	private String timezone;
	private Double latitude;
	private Double longitude;

	public GeoLocation(String country, String city, String timezone, Double latitude, Double longitude) {
		super();
		this.country = country;
		this.city = city;
		this.timezone = timezone;
		this.latitude = latitude;
		this.longitude = longitude;
	}

	@Override
	public String toString() {
		return this.city + ", " + this.country;
	}

}
