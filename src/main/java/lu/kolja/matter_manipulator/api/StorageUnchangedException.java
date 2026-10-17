package lu.kolja.matter_manipulator.api;

/** Thrown before any storage changed, after which core skips that storage. Never throw it once items may have moved. */
public class StorageUnchangedException extends RuntimeException {

    public StorageUnchangedException(String message, Throwable cause) {
        super(message, cause);
    }
}
