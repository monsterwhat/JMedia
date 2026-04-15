package Services;

import Models.Genre;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
public class GenreService {

    @Transactional
    public List<Genre> findActiveOrdered() {
        return Genre.list("isActive = true ORDER BY sortOrder, name");
    }
}
