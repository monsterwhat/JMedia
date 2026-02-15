package Models.DTOs;

import java.util.List;

/**
 * Paginated response for movie listings
 */
public class PaginatedMovieResponse {
    public List<Object> videos;
    public int page;
    public int limit;
    public long totalItems;
    public int totalPages;
    
    public PaginatedMovieResponse(List<Object> videos, int page, int limit, long totalItems, int totalPages) {
        this.videos = videos;
        this.page = page;
        this.limit = limit;
        this.totalItems = totalItems;
        this.totalPages = totalPages;
    }
}