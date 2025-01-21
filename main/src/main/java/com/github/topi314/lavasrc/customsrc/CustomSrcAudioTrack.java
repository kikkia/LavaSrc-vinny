package com.github.topi314.lavasrc.customsrc;

import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.net.URI;

public class CustomSrcAudioTrack extends DelegatedAudioTrack {

	private final CustomSrcAudioManager audioManager;

	public CustomSrcAudioTrack(AudioTrackInfo trackInfo, CustomSrcAudioManager manager) {
		super(trackInfo);
		this.audioManager = manager;
	}

	@Override
	public void process(LocalAudioTrackExecutor localAudioTrackExecutor) throws Exception {
		var downloadLink = this.trackInfo.uri;
		try (var httpInterface = this.audioManager.getHttpInterface()) {
			try (var stream = new PersistentHttpStream(httpInterface, new URI(downloadLink), this.trackInfo.length)) {
				processDelegate(new MatroskaAudioTrack(this.trackInfo, stream), localAudioTrackExecutor);
			}
		}
	}

	@Override
	public AudioSourceManager getSourceManager() {
		return this.audioManager;
	}
}
