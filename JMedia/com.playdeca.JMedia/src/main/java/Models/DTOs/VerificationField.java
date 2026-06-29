package Models.DTOs;

/**
 * Carries a current vs. fetched value pair for a single metadata field.
 */
public class VerificationField<T> {
    public T current;
    public T fetched;

    public VerificationField() {}

    public VerificationField(T current, T fetched) {
        this.current = current;
        this.fetched = fetched;
    }

    public boolean isDifferent() {
        if (current == null && fetched == null) return false;
        if (current == null || fetched == null) return true;
        return !current.equals(fetched);
    }

    public boolean isCurrentEmpty() {
        return current == null || (current instanceof String && ((String) current).isBlank());
    }

    public boolean isFetchedEmpty() {
        return fetched == null || (fetched instanceof String && ((String) fetched).isBlank());
    }
}
