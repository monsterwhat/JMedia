package Models.DTOs;

import Models.AudioTrack;

public class AudioTrackDTO {
    public Long id;
    public String filename;
    public String languageCode;
    public String languageName;
    public String displayName;
    public String format;
    public String codec;
    public boolean isDefault;
    public boolean isEmbedded;
    public Integer channels;
    public Integer bitrate;
    public Integer sampleRate;
    public String title;

    public AudioTrackDTO() {}

    public AudioTrackDTO(AudioTrack track) {
        this.id = track.id;
        this.filename = track.filename;
        this.languageCode = track.languageCode;
        this.languageName = track.languageName;
        this.displayName = track.displayName;
        this.format = track.format;
        this.codec = track.codec;
        this.isDefault = track.isDefault;
        this.isEmbedded = track.isEmbedded;
        this.channels = track.channels;
        this.bitrate = track.bitrate;
        this.sampleRate = track.sampleRate;
        this.title = track.title;
    }
}
