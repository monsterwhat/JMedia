package Models.DTOs;

import Models.CollectionEntry;
import Models.MediaCollection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SyncCollectionData {

    public Long collectionId;
    public String name;
    public String description;
    public Boolean isPublic;
    public int sortOrder;
    public Long coverVideoId;
    public LocalDateTime createdDate;
    public LocalDateTime updatedAt;

    public List<SyncCollectionEntryData> entries;

    public static SyncCollectionData fromCollection(MediaCollection collection) {
        SyncCollectionData data = new SyncCollectionData();
        data.collectionId = collection.id;
        data.name = collection.name;
        data.description = collection.description;
        data.isPublic = collection.isPublic;
        data.sortOrder = collection.sortOrder;
        data.coverVideoId = collection.coverVideoId;
        data.createdDate = collection.createdDate;
        data.entries = new ArrayList<>();
        return data;
    }

    public static class SyncCollectionEntryData {
        public Long videoId;
        public Long externalVideoId;
        public int orderIndex;
        public String notes;
    }

}
