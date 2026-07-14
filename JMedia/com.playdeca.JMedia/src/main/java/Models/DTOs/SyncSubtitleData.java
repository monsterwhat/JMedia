package Models.DTOs;

import Models.SubtitleTrack;
import Models.Video;
import java.util.ArrayList;
import java.util.List;

public class SyncSubtitleData {

    public Long trackId;
    public Long videoId;
    public String filename;
    public String fullPath;
    public String format;
    public String encoding;
    public Long fileSize;
    public String languageCode;
    public String languageName;
    public String displayName;
    public boolean isForced;
    public boolean isSDH;
    public boolean isDefault;
    public boolean isEmbedded;
    public boolean isManual;
    public boolean isActive;
    public String subtitleContent;

    public void applyTo(SubtitleTrack track) {
        if (languageCode != null) track.languageCode = languageCode;
        if (languageName != null) track.languageName = languageName;
        if (displayName != null) track.displayName = displayName;
        if (format != null) track.format = format;
        track.isForced = isForced;
        track.isSDH = isSDH;
        track.isDefault = isDefault;
        track.isManual = isManual;
        track.isActive = isActive;
    }

    public static SyncSubtitleData fromTrack(SubtitleTrack track) {
        SyncSubtitleData data = new SyncSubtitleData();
        data.trackId = track.id;
        data.videoId = track.video != null ? track.video.id : null;
        data.filename = track.filename;
        data.fullPath = track.fullPath;
        data.format = track.format;
        data.encoding = track.encoding;
        data.fileSize = track.fileSize;
        data.languageCode = track.languageCode;
        data.languageName = track.languageName;
        data.displayName = track.displayName;
        data.isForced = track.isForced;
        data.isSDH = track.isSDH;
        data.isDefault = track.isDefault;
        data.isEmbedded = track.isEmbedded;
        data.isManual = track.isManual != null && track.isManual;
        data.isActive = track.isActive;
        return data;
    }

}
