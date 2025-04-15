package com.github.topi314.lavasrc.radiosrc;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LofiRadioService {
	private final String stationsUrl;
	private final String allUrl;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private static final Logger log = LoggerFactory.getLogger(LofiRadioService.class);
	private final ConcurrentHashMap<String, LofiRadioStation> _radioStations = new ConcurrentHashMap<>();
	private final LofiRadioSrcAudioManager lofiRadio;

	public LofiRadioService(String allUrl, String stationsUrl, LofiRadioSrcAudioManager lofiRadio) {
		this.allUrl = allUrl;
		this.stationsUrl = stationsUrl;
		this.lofiRadio = lofiRadio;
		loadInitialStations();
	}

	private List<Pair<String, String>> fetchStations() {
		try {
			JsonBrowser response = getJson(allUrl);
			return parseStationsJson(response);
		} catch (Exception e) {
			log.error("Error fetching stations: {}", e.getMessage());
			return new ArrayList<>();
		}
	}

	public JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.setHeader("Accept", "application/json");
		return LavaSrcTools.fetchResponseAsJson(lofiRadio.getHttpInterface(), request);
	}

	private List<Pair<String, String>> parseStationsJson(JsonBrowser json) {
		List<Pair<String, String>> stations = new ArrayList<>();
		try {
			JsonBrowser stationJson = json.get("stations");
			for (int i = 0; i < stationJson.values().size(); i++) {
				JsonBrowser stationObject = stationJson.index(i);
				stations.add(new Pair<>(stationObject.get("name").text(), stationObject.get("id").text()));
			}
		} catch (Exception e) {
			log.error("Error parsing stations JSON: {}", e.getMessage());
		}
		return stations;
	}

	private void loadInitialStations() {
		List<Pair<String, String>> stations = fetchStations();
		for (Pair<String, String> station : stations) {
			_radioStations.put(station.getSecond(), new LofiRadioStation(station.getFirst(), station.getSecond(), stationsUrl, 1, lofiRadio));
			log.info("Loaded station: {} (ID: {})", station.getFirst(), station.getSecond());
		}
		scheduler.schedule(this::healthCheck, 30, TimeUnit.SECONDS);
	}

	public LofiRadioStation getStation(String stationId) {
		return _radioStations.get(stationId);
	}

	public void stopAllStations() {
		for (LofiRadioStation station : _radioStations.values()) {
			station.stop();
		}
	}

	public void restartAllStations() {
		stopAllStations();
		loadInitialStations();
	}

	private void healthCheck() {
		for (LofiRadioStation s : _radioStations.values()) {
			if (s.repeatFails > 5) {
				restartAllStations();
				return;
			}
		}
		scheduler.schedule(this::healthCheck, 30, TimeUnit.SECONDS);
	}

	private class Pair<K, V> {
		private final K first;
		private final V second;

		public Pair(K first, V second) {
			this.first = first;
			this.second = second;
		}

		public K getFirst() {
			return first;
		}

		public V getSecond() {
			return second;
		}
	}
}