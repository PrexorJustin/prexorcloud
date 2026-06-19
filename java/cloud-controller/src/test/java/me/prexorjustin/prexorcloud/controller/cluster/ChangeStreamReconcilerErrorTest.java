package me.prexorjustin.prexorcloud.controller.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no Mongo) for the resume/non-resume classification that decides whether a change
 * stream error forces a full resync (token gone — oplog rolled) or just a reopen from the last token
 * (transient blip). Runs everywhere, no replica set required.
 */
final class ChangeStreamReconcilerErrorTest {

    private static MongoCommandException commandError(int code) {
        BsonDocument response = new BsonDocument()
                .append("ok", new BsonDouble(0.0))
                .append("code", new BsonInt32(code))
                .append("errmsg", new BsonString("err " + code));
        return new MongoCommandException(response, new ServerAddress());
    }

    @Test
    void changeStreamHistoryLostIsNonResumable() {
        assertTrue(ChangeStreamReconciler.isNonResumable(commandError(286)), "286 = ChangeStreamHistoryLost");
    }

    @Test
    void changeStreamFatalErrorIsNonResumable() {
        assertTrue(ChangeStreamReconciler.isNonResumable(commandError(280)), "280 = ChangeStreamFatalError");
    }

    @Test
    void transientAndUnknownErrorsAreResumable() {
        assertFalse(ChangeStreamReconciler.isNonResumable(commandError(11600)), "interrupted-at-shutdown is retryable");
        assertFalse(ChangeStreamReconciler.isNonResumable(new RuntimeException("socket reset")));
        assertFalse(ChangeStreamReconciler.isNonResumable(null));
    }
}
