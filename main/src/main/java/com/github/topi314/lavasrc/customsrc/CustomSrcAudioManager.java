package com.github.topi314.lavasrc.customsrc;

import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioSourceManager;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class CustomSrcAudioManager extends ExtendedAudioSourceManager implements HttpConfigurable {
	public static final String SEARCH_PREFIX = "custsearch:";
	public static final String ISRC_PREFIX = "custisrc:";
	private String baseUrl;
	private String key;
	private String name;

	private HttpInterfaceManager httpInterfaceManager;
	private static final Logger log = LoggerFactory.getLogger(CustomSrcAudioManager.class);

	public CustomSrcAudioManager(String key, String baseUrl, @Nullable String name) {
		this.key = key;
		this.name = name;
		this.baseUrl = baseUrl;
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
	}

	@Override
	public String getSourceName() {
		return name != null ? name : "custom";
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager audioPlayerManager, AudioReference audioReference) {
		return this.loadItem(audioReference.identifier);
	}

	public AudioItem loadItem(String identifier) {
		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()));
			}

			if (identifier.startsWith(ISRC_PREFIX)) {
				return this.getTrackByISRC(identifier.substring(ISRC_PREFIX.length()));
			}

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	private AudioItem getTrackByISRC(String isrc) throws IOException {
		var json = this.getJson(getISRCSearchUrl(URLEncoder.encode(isrc, StandardCharsets.UTF_8)));
		if (json == null || json.index(0).get("id").isNull()) {
			return AudioReference.NO_TRACK;
		}
		return this.parseTrack(json.index(0));
	}

	private AudioItem getSearch(String query) throws IOException {
		var json = this.getJson(getSearchUrl(URLEncoder.encode(query, StandardCharsets.UTF_8)));
		if (json == null || json.values().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new BasicAudioPlaylist("Custom Search: " + query, this.parseTracks(json), null, true);
	}

	private AudioTrack parseTrack(JsonBrowser json) {
		var id = json.get("id").text();
		var track = new AudioTrackInfo(
			json.get("title").text(),
			json.get("artist").text(),
			json.get("duration").asLong(0),
			id,
			false,
			json.get("versions").index(0).get("url").text(),
			json.get("picture").text(),
			json.get("isrc").index(0).text()
		);
		return new CustomSrcAudioTrack(track, this);
	}

	private List<AudioTrack> parseTracks(JsonBrowser json) {
		var tracks = new ArrayList<AudioTrack>();
		for (var track : json.values()) {
			tracks.add(this.parseTrack(track));
		}
		return tracks;
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo audioTrackInfo, DataInput dataInput) throws IOException {
		super.decodeTrack(dataInput);
		return new CustomSrcAudioTrack(audioTrackInfo, this);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> function) {
		this.httpInterfaceManager.configureRequests(function);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> consumer) {
		this.httpInterfaceManager.configureBuilder(consumer);
	}

	public JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.setHeader("Accept", "application/json");
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	public String getISRCSearchUrl(String isrc) {
		return baseUrl + "?p=" + key + "&isrcs=" + isrc;
	}

	public String getSearchUrl(String search) {
		return baseUrl + "?p=" + key + "&q=" + search;
	}

	public HttpInterface getHttpInterface() {
		return this.httpInterfaceManager.getInterface();
	}
}
