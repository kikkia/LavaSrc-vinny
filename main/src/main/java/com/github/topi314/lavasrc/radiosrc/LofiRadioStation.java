package com.github.topi314.lavasrc.radiosrc;

import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LofiRadioStation {
	private final String name;
	private final String id;
	private final String playlistUrl;
	private final int refreshBuffer;
	private static final Logger log = LoggerFactory.getLogger(LofiRadioStation.class);
	private final LinkedList<LofiTrackInfo> playlist = new LinkedList<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private volatile ScheduledFuture<?> nextTrackFuture;
	private LofiRadioSrcAudioManager lofiRadio;
	int repeatFails = 0;

	public LofiRadioStation(String name, String id, String playlistUrl, int refreshBuffer, LofiRadioSrcAudioManager lofiRadio) {
		this.name = name;
		this.id = id;
		this.playlistUrl = playlistUrl;
		this.refreshBuffer = refreshBuffer > 0 ? refreshBuffer : 1;
		this.lofiRadio = lofiRadio;
		fetchAndUpdatePlaylist();
	}

	public LofiTrackInfo getNowPlaying() {
		if (playlist.isEmpty()) {
			log.warn("EMPTY PLAYLIST FETCHING NEW");
			fetchAndUpdatePlaylist();
		}
		return playlist.isEmpty() ? null : playlist.getFirst();
	}

	private LinkedList<LofiTrackInfo> fetchPlaylistSync() {
		try {
			JsonBrowser response = getJson(playlistUrl.replace("{id}", id));
			return parsePlaylistJson(response);
		} catch (Exception e) {
			log.error("Exception fetching playlist for station {}: {}", name, e.getMessage(), e);
			return new LinkedList<>();
		}
	}

	public JsonBrowser getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.setHeader("Accept", "application/json");
		return LavaSrcTools.fetchResponseAsJson(lofiRadio.getHttpInterface(), request);
	}

	private void fetchAndUpdatePlaylist() {
		try {
			LinkedList<LofiTrackInfo> newPlaylist = fetchPlaylistSync();
			if (newPlaylist.isEmpty()) {
				log.warn("Fetched empty playlist for station {}, retrying in 30 seconds", name);
				repeatFails++;
				scheduler.schedule(this::fetchAndUpdatePlaylist, 30, TimeUnit.SECONDS);
				return;
			}
			repeatFails = 0;

			playlist.clear();
			playlist.addAll(newPlaylist);
			scheduleNextTrack();
		} catch (Exception e) {
			log.error("Error in fetchAndUpdatePlaylist for station {}: {}", name, e.getMessage(), e);
			scheduler.schedule(this::fetchAndUpdatePlaylist, 30, TimeUnit.SECONDS);
		}
	}

	private LinkedList<LofiTrackInfo> parsePlaylistJson(JsonBrowser json) {
		LinkedList<LofiTrackInfo> playlistItems = new LinkedList<>();
		try {
			for (int i = 0; i < json.values().size(); i++) {
				JsonBrowser jsonObject = json.index(i);
				String slug = jsonObject.get("id").text() + "-" + jsonObject.get("fileId").text();
				playlistItems.addLast(
					new LofiTrackInfo(
						jsonObject.get("id").text(),
						slug,
						null, // Constructed down the line during mapping
						jsonObject.get("artists").text(),
						jsonObject.get("title").text(),
						jsonObject.get("image").isNull() ? null : jsonObject.get("image").text(),
						jsonObject.get("isrc").textOrDefault(""),
						Double.parseDouble(jsonObject.get("duration").text()),
						OffsetDateTime.parse(jsonObject.get("startTime").text()),
						OffsetDateTime.parse(jsonObject.get("endTime").text())
					)
				);
			}
		} catch (Exception e) {
			log.error("Error parsing playlist JSON for station {}: {}", name, e.getMessage(), e);
		}
		return playlistItems;
	}

	private void scheduleNextTrack() {
		try {
			// Cancel any existing scheduled task first
			if (nextTrackFuture != null && !nextTrackFuture.isDone()) {
				nextTrackFuture.cancel(false); // Don't interrupt if it's running
			}

			if (playlist.isEmpty()) {
				log.warn("Cannot schedule next track - playlist is empty for station {}", name);
				fetchAndUpdatePlaylist();
				return;
			}

			LofiTrackInfo currentTrack = playlist.getFirst();
			OffsetDateTime currentTrackEndTime = currentTrack.getEndTime();
			OffsetDateTime now = OffsetDateTime.now(currentTrackEndTime.getOffset());

			long delay = ChronoUnit.MILLIS.between(now, currentTrackEndTime);

			if (delay <= 0) {
				// Track has already ended, move to next track immediately
				log.warn("Track {} for station {} has already ended, moving to next track immediately",
					currentTrack.getTitle(), name);
				handleTrackEnd();
			} else {
				// Schedule for future execution
				nextTrackFuture = scheduler.schedule(
					this::handleTrackEnd,
					delay,
					TimeUnit.MILLISECONDS
				);

				//log.info("Scheduled track {} for station {} to end at {} (delay: {}ms)",
				//	currentTrack.getTitle(), name, currentTrackEndTime, delay);
			}
		} catch (Exception e) {
			log.error("Error in scheduleNextTrack for station {}: {}", name, e.getMessage(), e);
			// Try again with a delay to avoid rapid cycling on persistent errors
			scheduler.schedule(this::scheduleNextTrack, 5, TimeUnit.SECONDS);
		}
	}

	private void handleTrackEnd() {
		try {
			if (!playlist.isEmpty()) {
				playlist.removeFirst();

				if (playlist.size() <= refreshBuffer) {
					fetchAndUpdatePlaylist();
				} else if (!playlist.isEmpty()) {
					scheduleNextTrack();
					//log.info("Moved to next track for {}: {}", name, playlist.getFirst().getTitle());
				} else {
					log.warn("Playlist for {} is empty after removing track, fetching new playlist", name);
					fetchAndUpdatePlaylist();
				}
			} else {
				log.warn("handleTrackEnd called but playlist is empty for station {}", name);
				fetchAndUpdatePlaylist();
			}
		} catch (Exception e) {
			log.error("Error in handleTrackEnd for station {}: {}", name, e.getMessage(), e);
			scheduler.schedule(this::fetchAndUpdatePlaylist, 5, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (nextTrackFuture != null) {
			nextTrackFuture.cancel(false);
			nextTrackFuture = null;
		}

		scheduler.shutdownNow();
		try {
			if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
				log.warn("Scheduler for station {} did not terminate in time", name);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Interrupted while stopping scheduler for station {}", name);
		}
	}
}
