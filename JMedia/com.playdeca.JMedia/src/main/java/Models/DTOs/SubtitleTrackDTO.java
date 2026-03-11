package Models.DTOs;

import Models.SubtitleTrack;

public class SubtitleTrackDTO {
    public Long id;
    public String filename;
    public String languageCode;
    public String languageName;
    public String displayName;
    public String format;
    public boolean isForced;
    public boolean isSDH;
    public boolean isDefault;
    public boolean isEmbedded;
    public boolean isManual;

    public SubtitleTrackDTO() {}

    public SubtitleTrackDTO(SubtitleTrack track) {
        this.id = track.id;
        this.filename = track.filename;
        this.languageCode = track.languageCode;
        this.languageName = track.languageName;
        this.displayName = track.displayName;
        this.format = track.format;
        this.isForced = track.isForced;
        this.isSDH = track.isSDH;
        this.isDefault = track.isDefault;
        this.isEmbedded = track.isEmbedded;
        this.isManual = track.isManual != null && track.isManual;
    }
}
