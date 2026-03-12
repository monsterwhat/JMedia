package Models.DTOs;

public class EpisodeRenameDTO {
    public Long id;
    public String newTitle;

    public EpisodeRenameDTO() {}

    public EpisodeRenameDTO(Long id, String newTitle) {
        this.id = id;
        this.newTitle = newTitle;
    }
}
