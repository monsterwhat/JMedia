package Models.DTOs;

import Models.Video;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedMovieResponse {
    private List<Video> videos;
    private int currentPage;
    private int limit;
    private long totalItems;
    private int totalPages;
}
