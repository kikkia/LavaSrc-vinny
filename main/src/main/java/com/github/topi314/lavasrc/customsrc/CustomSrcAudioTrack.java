package com.github.topi314.lavasrc.customsrc;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

public class CustomSrcAudioTrack extends DelegatedAudioTrack {

	private final CustomSrcAudioManager audioManager;

	public CustomSrcAudioTrack(AudioTrackInfo trackInfo, CustomSrcAudioManager manager) {
		super(trackInfo);
		this.audioManager = manager;
	}

	@Override
	public void process(LocalAudioTrackExecutor localAudioTrackExecutor) throws Exception {

	}
}
