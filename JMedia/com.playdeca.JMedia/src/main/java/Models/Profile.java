package Models;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity; 
import jakarta.persistence.Transient;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper =false) 
public class Profile extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String name;

    @Column(nullable = false)
    public boolean isMainProfile;
    
    public Long userId;
    
    @Column(length = 2048)
    public String hiddenPlaylistIdsJson;
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    @Transient
    public List<Long> getHiddenPlaylistIds() {
        if (hiddenPlaylistIdsJson == null || hiddenPlaylistIdsJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(hiddenPlaylistIdsJson, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
    
    @Transient
    public void setHiddenPlaylistIds(List<Long> ids) {
        try {
            this.hiddenPlaylistIdsJson = mapper.writeValueAsString(ids);
        } catch (Exception e) {
            this.hiddenPlaylistIdsJson = "[]";
        }
    }
    
    @Transient
    public void addHiddenPlaylist(Long playlistId) {
        List<Long> ids = getHiddenPlaylistIds();
        if (!ids.contains(playlistId)) {
            ids.add(playlistId);
            setHiddenPlaylistIds(ids);
        }
    }
    
    @Transient
    public void removeHiddenPlaylist(Long playlistId) {
        List<Long> ids = getHiddenPlaylistIds();
        ids.remove(playlistId);
        setHiddenPlaylistIds(ids);
    }
    
    @Transient
    public boolean isPlaylistHidden(Long playlistId) {
        return getHiddenPlaylistIds().contains(playlistId);
    }

    public static Profile findMainProfile() {
        return find("isMainProfile", true).firstResult();
    }
    
    public static Profile findMainProfileByUser(Long userId) {
        return find("userId = ?1 and isMainProfile = true", userId).firstResult();
    }

    public static Profile findByName(String name) {
        return find("name", name).firstResult();
    }
}
