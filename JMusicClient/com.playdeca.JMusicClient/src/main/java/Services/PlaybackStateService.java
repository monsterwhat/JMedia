package Services;

import Models.PlaybackState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.util.ArrayList;

@ApplicationScoped
public class PlaybackStateService {

    private static final Long SINGLETON_ID = 1L;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public synchronized PlaybackState getOrCreateState() {
        PlaybackState state = em.find(PlaybackState.class, SINGLETON_ID);
        if (state == null) {
            state = new PlaybackState();
            state.setId(SINGLETON_ID);
            state.setVolume(0.8f);
            state.setCue(new ArrayList<>());
            state.setLastSongs(new ArrayList<>());
            state.setCueIndex(-1);
            em.persist(state);
        }
        return state;
    }

    @Transactional
    public synchronized void saveState(PlaybackState newState) {
        if (newState == null) {
            return;
        }
        newState.setId(SINGLETON_ID);
        em.merge(newState);
        em.flush();
    }

    @Transactional
    public synchronized void resetState() {
        PlaybackState state = new PlaybackState();
        state.setId(SINGLETON_ID);
        em.merge(state);
        em.flush();
    }
}
