package Models.DTOs;

import Services.VideoService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaginatedMovieResponse {
    private List<VideoService.VideoDTO> videos;
    private int currentPage;
    private int limit;
    private long totalItems;
    private int totalPages;
}
